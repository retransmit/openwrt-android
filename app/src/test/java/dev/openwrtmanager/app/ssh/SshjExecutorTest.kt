package dev.openwrtmanager.app.ssh

import dev.openwrtmanager.app.data.AuthType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.security.PublicKey

class SshjExecutorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: SshServer
    private lateinit var fingerprint: String
    private var authorizedKey: PublicKey? = null
    private var port = 0
    private val executors = mutableListOf<SshjExecutor>()

    @Before
    fun setUp() {
        server = newServer(0)
        server.start()
        port = server.port
        fingerprint = opensshFingerprint(server.keyPairProvider.loadKeys(null).first().public)
    }

    @After
    fun tearDown() {
        runBlocking { executors.forEach { it.close() } }
        server.stop(true)
    }

    private fun newServer(onPort: Int): SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = onPort
        keyPairProvider = SimpleGeneratorHostKeyProvider(tmp.root.toPath().resolve("hostkey.ser"))
        setPasswordAuthenticator { user, password, _ -> user == "root" && password == "secret" }
        setPublickeyAuthenticator { user, key, _ ->
            user == "root" && authorizedKey?.let { KeyUtils.compareKeys(it, key) } == true
        }
        setCommandFactory { _, command -> CannedCommand(command) }
    }

    private fun executor(
        authType: AuthType = AuthType.PASSWORD,
        secret: String = "secret",
        trusted: String? = fingerprint,
        onPort: Int = port,
    ) = SshjExecutor(SshTarget("127.0.0.1", onPort, "root", authType, secret, trusted)).also { executors += it }

    @Test
    fun password_exec_returns_stdout_and_zero_exit() = runBlocking<Unit> {
        val result = executor().exec("hello")
        assertEquals(ExecResult(0, "hello world\n", ""), result)
    }

    @Test
    fun non_zero_exit_and_stderr_are_returned_not_thrown() = runBlocking<Unit> {
        val result = executor().exec("fail3")
        assertEquals(3, result.exitCode)
        assertEquals("boom\n", result.stderr)
    }

    @Test
    fun several_commands_reuse_one_executor() = runBlocking<Unit> {
        val ssh = executor()
        repeat(3) { assertEquals(0, ssh.exec("hello").exitCode) }
    }

    @Test
    fun generated_key_authenticates() = runBlocking<Unit> {
        val key = KeyGenerator.generateEd25519()
        assertTrue(key.publicOpenSsh.startsWith("ssh-ed25519 "))
        assertTrue(key.publicOpenSsh.endsWith(" openwrt-manager"))
        authorizedKey = PublicKeyEntry.parsePublicKeyEntry(key.publicOpenSsh).resolvePublicKey(null, emptyMap(), null)
        val result = executor(AuthType.KEY, key.privatePem).exec("hello")
        assertEquals(0, result.exitCode)
    }

    @Test
    fun unauthorized_key_is_auth_failed() = runBlocking<Unit> {
        val key = KeyGenerator.generateEd25519()
        expect<SshError.AuthFailed> { executor(AuthType.KEY, key.privatePem).exec("hello") }
    }

    @Test
    fun wrong_password_is_auth_failed() = runBlocking<Unit> {
        expect<SshError.AuthFailed> { executor(secret = "nope").exec("hello") }
    }

    @Test
    fun unknown_host_key_reports_fingerprint_matching_probe() = runBlocking<Unit> {
        val probed = SshjExecutor.probeFingerprint("127.0.0.1", port)
        assertEquals(fingerprint, probed)
        val error = expect<SshError.HostKeyUnknown> { executor(trusted = null).exec("hello") }
        assertEquals(probed, error.fingerprint)
    }

    @Test
    fun different_host_key_is_host_key_changed() = runBlocking<Unit> {
        val error = expect<SshError.HostKeyChanged> { executor(trusted = "SHA256:other").exec("hello") }
        assertEquals("SHA256:other", error.expected)
        assertEquals(fingerprint, error.actual)
    }

    @Test
    fun closed_port_is_unreachable() = runBlocking<Unit> {
        val closedPort = ServerSocket(0).use { it.localPort }
        expect<SshError.Unreachable> { executor(onPort = closedPort).exec("hello") }
        expect<SshError.Unreachable> { SshjExecutor.probeFingerprint("127.0.0.1", closedPort) }
    }

    @Test
    fun slow_command_times_out() = runBlocking<Unit> {
        val ssh = executor()
        expect<SshError.Timeout> { ssh.exec("sleep", timeoutMs = 200) }
        assertEquals("usable after a timeout", 0, ssh.exec("hello").exitCode)
    }

    @Test
    fun reconnects_after_server_restart() = runBlocking<Unit> {
        val ssh = executor()
        assertEquals(0, ssh.exec("hello").exitCode)
        server.stop(true)
        server = newServer(port)
        server.start()
        assertEquals(0, ssh.exec("hello").exitCode)
    }

    @Test
    fun server_gone_is_unreachable() = runBlocking<Unit> {
        val ssh = executor()
        ssh.exec("hello")
        server.stop(true)
        // The stale connection is dropped, the one reconnect attempt finds nothing listening.
        expect<SshError.Unreachable> { ssh.exec("hello") }
    }

    @Test
    fun host_that_accepts_the_connection_but_never_speaks_is_unreachable_within_the_connect_timeout() = runBlocking<Unit> {
        ServerSocket(0).use { silent ->
            val started = System.nanoTime()
            expect<SshError.Unreachable> { SshjExecutor.probeFingerprint("127.0.0.1", silent.localPort) }
            expect<SshError.Unreachable> { executor(onPort = silent.localPort).exec("hello") }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("took $elapsedMs ms", elapsedMs < 20_000)
        }
    }

    @Test
    fun cancelling_a_running_command_returns_promptly_and_frees_the_executor() = runBlocking<Unit> {
        val ssh = executor()
        ssh.exec("hello")
        val started = System.nanoTime()
        val job = launch(Dispatchers.Default) { ssh.exec("sleep", timeoutMs = 30_000) }
        delay(300)
        job.cancel()
        job.join()
        // The server keeps the command running for 5 s; the caller and the next command must not wait for it.
        val result = withTimeout(2_000) { ssh.exec("hello") }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(0, result.exitCode)
        assertTrue("took $elapsedMs ms", elapsedMs < 3_000)
    }

    private inline fun <reified E : SshError> expect(block: () -> Any?): E {
        try {
            block()
        } catch (e: SshError) {
            if (e is E) return e
            throw AssertionError("expected ${E::class.simpleName} but got $e", e)
        }
        throw AssertionError("expected ${E::class.simpleName} but nothing was thrown")
    }
}

/** Server-side stand-in for a shell: "hello" prints, "fail3" exits 3 with stderr, "sleep" hangs for 5 s. */
private class CannedCommand(private val command: String) : Command {
    private lateinit var out: OutputStream
    private lateinit var err: OutputStream
    private lateinit var exit: ExitCallback
    private var worker: Thread? = null

    override fun setInputStream(input: InputStream) = Unit
    override fun setOutputStream(output: OutputStream) { out = output }
    override fun setErrorStream(error: OutputStream) { err = error }
    override fun setExitCallback(callback: ExitCallback) { exit = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        worker = Thread {
            try {
                when (command) {
                    "hello" -> { out.write("hello world\n".toByteArray()); out.flush(); exit.onExit(0) }
                    "fail3" -> { err.write("boom\n".toByteArray()); err.flush(); exit.onExit(3) }
                    "sleep" -> { Thread.sleep(5_000); exit.onExit(0) }
                    else -> exit.onExit(127)
                }
            } catch (_: InterruptedException) {
            } catch (_: java.io.IOException) {
            }
        }.also { it.isDaemon = true; it.start() }
    }

    override fun destroy(channel: ChannelSession) {
        worker?.interrupt()
    }
}

/** Computed from the server library's own key encoding, independently of the code under test. */
private fun opensshFingerprint(key: PublicKey): String {
    val blob = java.util.Base64.getDecoder().decode(PublicKeyEntry.toString(key).split(' ')[1])
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
}
