package dev.openwrtmanager.app.ssh

import dev.openwrtmanager.app.data.AuthType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.util.concurrent.TimeUnit

data class SshTarget(
    val host: String,
    val port: Int,
    val username: String,
    val authType: AuthType,
    /** The password, or the private key in PEM form. Never log this. */
    val secret: String,
    /** "SHA256:..." fingerprint the user accepted, or null when the host is not trusted yet. */
    val trustedFingerprint: String?,
) {
    override fun toString(): String = "SshTarget($username@$host:$port, $authType)"
}

/**
 * SshExecutor backed by sshj. One connection is kept open and reused; each command gets its own
 * session channel. Calls are serialised, so concurrent callers never race on connecting.
 */
class SshjExecutor(private val target: SshTarget) : SshExecutor {
    private val mutex = Mutex()
    private var client: SSHClient? = null

    /** Connects and authenticates if not already connected. */
    suspend fun connect() {
        mutex.withLock { ensureConnected() }
    }

    override suspend fun exec(command: String, timeoutMs: Long, stdin: String?): ExecResult = mutex.withLock {
        val reused = client?.let { it.isConnected && it.isAuthenticated } == true
        val first = ensureConnected()
        try {
            run(first, command, timeoutMs, stdin)
        } catch (e: IOException) {
            drop()
            // A connection that was already open may simply have gone stale: try once on a fresh one.
            if (!reused) throw SshError.Disconnected(e)
            val fresh = ensureConnected()
            try {
                run(fresh, command, timeoutMs, stdin)
            } catch (e2: IOException) {
                drop()
                throw SshError.Disconnected(e2)
            }
        }
    }

    override suspend fun close() {
        mutex.withLock { drop() }
    }

    private suspend fun ensureConnected(): SSHClient = withContext(Dispatchers.IO) {
        client?.let { existing ->
            if (existing.isConnected && existing.isAuthenticated) return@withContext existing
        }
        drop()
        val policy = HostKeyPolicy(target.trustedFingerprint)
        val fresh = newClient(policy)
        try {
            fresh.connect(target.host, target.port)
            authenticate(fresh)
            // Connected: from here keep-alive detects a dead peer, and idle periods must not time out.
            fresh.socket.soTimeout = 0
        } catch (e: UserAuthException) {
            fresh.quietDisconnect()
            throw SshError.AuthFailed()
        } catch (e: SshError) {
            fresh.quietDisconnect()
            throw e
        } catch (e: IOException) {
            fresh.quietDisconnect()
            throw policy.rejection ?: SshError.Unreachable(e)
        }
        client = fresh
        fresh
    }

    private fun authenticate(ssh: SSHClient) {
        when (target.authType) {
            AuthType.PASSWORD -> ssh.authPassword(target.username, target.secret)
            AuthType.KEY -> {
                val keys = try {
                    ssh.loadKeys(target.secret, null, null)
                } catch (e: IOException) {
                    // An unreadable stored key can never authenticate.
                    throw SshError.AuthFailed()
                }
                ssh.authPublickey(target.username, keys)
            }
        }
    }

    /**
     * The stream reads below block and cannot be cancelled, so they run detached from the caller.
     * On timeout or cancellation the caller returns at once and the whole transport is closed:
     * closing the socket is the only thing that reliably unblocks a read when the peer has gone silent.
     * The next command reconnects.
     */
    private suspend fun run(ssh: SSHClient, command: String, timeoutMs: Long, stdin: String?): ExecResult {
        val work = blockingWork.async { execBlocking(ssh, command, stdin) }
        try {
            return withTimeout(timeoutMs) { work.await() }
        } catch (e: TimeoutCancellationException) {
            ssh.quietDisconnect()
            throw SshError.Timeout()
        } catch (e: CancellationException) {
            ssh.quietDisconnect()
            throw e
        } catch (e: RuntimeException) {
            // sshj reports some transport failures as unchecked exceptions.
            throw IOException(e)
        }
    }

    private suspend fun execBlocking(ssh: SSHClient, command: String, stdin: String?): ExecResult = coroutineScope {
        val session = ssh.startSession()
        try {
            val cmd = session.exec(command)
            if (stdin != null) cmd.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
            val stderr = async { cmd.errorStream.readBytes() }
            val stdout = cmd.inputStream.readBytes()
            val stderrBytes = stderr.await()
            cmd.join(EXIT_WAIT_SECONDS, TimeUnit.SECONDS)
            ExecResult(
                exitCode = cmd.exitStatus ?: -1,
                stdout = String(stdout, Charsets.UTF_8),
                stderr = String(stderrBytes, Charsets.UTF_8),
            )
        } finally {
            runCatching { session.close() }
        }
    }

    private fun drop() {
        client?.quietDisconnect()
        client = null
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val KEEP_ALIVE_SECONDS = 15
        private const val EXIT_WAIT_SECONDS = 5L

        /** Home for blocking SSH reads that must be able to outlive a cancelled caller for a moment. */
        private val blockingWork = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Fetches the server's host key fingerprint without authenticating. */
        suspend fun probeFingerprint(host: String, port: Int): String = withContext(Dispatchers.IO) {
            val policy = HostKeyPolicy(trusted = null)
            val ssh = newClient(policy)
            try {
                ssh.connect(host, port)
            } catch (e: IOException) {
                // Expected: the policy refuses every key after recording it.
                if (policy.seen == null) throw SshError.Unreachable(e)
            } finally {
                ssh.quietDisconnect()
            }
            policy.seen ?: throw SshError.Unreachable()
        }

        private fun newClient(policy: HostKeyPolicy): SSHClient {
            val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
            return SSHClient(config).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                // Also bounds the handshake, so a host that accepts the connection but never speaks cannot hang us.
                timeout = CONNECT_TIMEOUT_MS
                addHostKeyVerifier(policy)
                connection.keepAlive.keepAliveInterval = KEEP_ALIVE_SECONDS
            }
        }

        private fun SSHClient.quietDisconnect() {
            runCatching { disconnect() }
        }
    }
}
