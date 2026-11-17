package dev.openwrtmanager.app.ssh

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

interface SshExecutor {
    /** Runs one command. Throws SshError on transport failure; a non-zero exit is returned, not thrown. */
    /**
     * [stdin], when given, is written to the command and then closed. Use it for anything long:
     * dropbear, the OpenWrt SSH server, drops the connection for a command line over about 9000 bytes.
     */
    suspend fun exec(command: String, timeoutMs: Long = 10_000, stdin: String? = null): ExecResult

    suspend fun close()
}
