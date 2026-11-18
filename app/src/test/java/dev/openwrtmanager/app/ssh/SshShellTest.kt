package dev.openwrtmanager.app.ssh

import dev.openwrtmanager.app.data.AuthType
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.io.OutputStream
import java.security.PublicKey

class SshShellTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: SshServer
    private lateinit var fingerprint: String

    @Before
    fun setUp() {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(tmp.root.toPath().resolve("hostkey.ser"))
            setPasswordAuthenticator { user, password, _ -> user == "root" && password == "secret" }
            setShellFactory { EchoShell() }
        }
        server.start()
        val key = server.keyPairProvider.loadKeys(null).first().public
        fingerprint = opensshFingerprint(key)
    }

    @After
    fun tearDown() = server.stop(true)

    private fun InputStream.readUntil(marker: String, limitMs: Long = 5_000): String {
        val deadline = System.currentTimeMillis() + limitMs
        val seen = StringBuilder()
        while (System.currentTimeMillis() < deadline && marker !in seen) {
            if (available() > 0) seen.append(read().toChar()) else Thread.sleep(10)
        }
        return seen.toString()
    }

    @Test
    fun pty_shell_echoes_and_resizes() = runBlocking<Unit> {
        val ssh = SshjExecutor(SshTarget("127.0.0.1", server.port, "root", AuthType.PASSWORD, "secret", fingerprint))
        val shell = ssh.openShell(80, 24)
        try {
            val greeting = shell.input.readUntil("ready")
            assertTrue(greeting, greeting.contains("term=xterm-256color size=80x24"))
            shell.output.write("hello\n".toByteArray())
            shell.output.flush()
            assertTrue(shell.input.readUntil("echo:hello").contains("echo:hello"))
            shell.resize(100, 30)
            assertTrue(shell.input.readUntil("resized 100x30").contains("resized 100x30"))
            assertTrue(shell.isOpen)
        } finally {
            shell.close()
            ssh.close()
        }
        assertTrue("closed", !shell.isOpen)
    }
}

/** A tiny shell: reports the PTY it got, echoes lines, and reports window changes. */
private class EchoShell : Command {
    private lateinit var input: InputStream
    private lateinit var out: OutputStream
    private lateinit var exit: ExitCallback
    private var worker: Thread? = null

    override fun setInputStream(input: InputStream) { this.input = input }
    override fun setOutputStream(output: OutputStream) { out = output }
    override fun setErrorStream(error: OutputStream) = Unit
    override fun setExitCallback(callback: ExitCallback) { exit = callback }

    override fun start(channel: ChannelSession, env: Environment) {
        fun say(text: String) = synchronized(out) { out.write(text.toByteArray()); out.flush() }
        val e = env.env
        say("term=${e["TERM"]} size=${e["COLUMNS"]}x${e["LINES"]} ready\n")
        env.addSignalListener({ _, _ -> say("resized ${env.env["COLUMNS"]}x${env.env["LINES"]}\n") }, org.apache.sshd.server.Signal.WINCH)
        worker = Thread {
            try {
                val line = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c < 0) break
                    if (c == '\n'.code || c == '\r'.code) {
                        if (line.isNotEmpty()) say("echo:$line\n")
                        line.clear()
                    } else {
                        line.append(c.toChar())
                    }
                }
            } catch (_: Exception) {
            }
            exit.onExit(0)
        }.also { it.isDaemon = true; it.start() }
    }

    override fun destroy(channel: ChannelSession) {
        worker?.interrupt()
    }
}

private fun opensshFingerprint(key: PublicKey): String {
    val blob = java.util.Base64.getDecoder().decode(org.apache.sshd.common.config.keys.PublicKeyEntry.toString(key).split(' ')[1])
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
}
