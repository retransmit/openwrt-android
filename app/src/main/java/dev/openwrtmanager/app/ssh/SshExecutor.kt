package dev.openwrtmanager.app.ssh

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

interface SshExecutor {
    /** Runs one command. Throws SshError on transport failure; a non-zero exit is returned, not thrown. */
    suspend fun exec(command: String, timeoutMs: Long = 10_000): ExecResult

    suspend fun close()
}
