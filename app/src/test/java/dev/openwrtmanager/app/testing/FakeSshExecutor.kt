package dev.openwrtmanager.app.testing

import dev.openwrtmanager.app.ssh.ExecResult
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.ssh.SshExecutor

class FakeSshExecutor(
    val responses: MutableMap<String, ExecResult> = mutableMapOf(),
) : SshExecutor {
    val commands = mutableListOf<String>()
    var failWith: SshError? = null

    /** Simulates a bug or platform failure that is not a transport error. */
    var crashWith: RuntimeException? = null

    /** Consulted first. Return null to fall through to the canned responses; throw to simulate a failure. */
    var handler: ((String) -> ExecResult?)? = null
    var closed = false

    fun respond(command: String, stdout: String, exitCode: Int = 0, stderr: String = "") {
        responses[command] = ExecResult(exitCode, stdout, stderr)
    }

    override suspend fun exec(command: String, timeoutMs: Long): ExecResult {
        failWith?.let { throw it }
        crashWith?.let { throw it }
        commands += command
        handler?.invoke(command)?.let { return it }
        return responses[command] ?: ExecResult(127, "", "not found")
    }

    override suspend fun close() {
        closed = true
    }
}
