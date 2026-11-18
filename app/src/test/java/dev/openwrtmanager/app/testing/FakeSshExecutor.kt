package dev.openwrtmanager.app.testing

import dev.openwrtmanager.app.ssh.ExecResult
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.ssh.SshExecutor

class FakeSshExecutor(
    val responses: MutableMap<String, ExecResult> = mutableMapOf(),
) : SshExecutor {
    val commands = mutableListOf<String>()

    /** Commands and connection closes in the order they happened. A close is recorded as "<close>". */
    val events = mutableListOf<String>()
    var failWith: SshError? = null

    /** Simulates a bug or platform failure that is not a transport error. */
    var crashWith: RuntimeException? = null

    /** Consulted first. Return null to fall through to the canned responses; throw to simulate a failure. */
    var handler: ((String) -> ExecResult?)? = null
    var closed = false

    /** How long each command takes, in (virtual) milliseconds. */
    var delayMs: Long = 0

    fun respond(command: String, stdout: String, exitCode: Int = 0, stderr: String = "") {
        responses[command] = ExecResult(exitCode, stdout, stderr)
    }

    /** When a script is sent as input, the script is what matters, so that is what gets recorded and matched. */
    override suspend fun exec(command: String, timeoutMs: Long, stdin: String?): ExecResult {
        if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
        return run(stdin ?: command)
    }

    private fun run(command: String): ExecResult {
        failWith?.let { throw it }
        crashWith?.let { throw it }
        commands += command
        events += command
        handler?.invoke(command)?.let { return it }
        return responses[command] ?: ExecResult(127, "", "not found")
    }

    override suspend fun close() {
        closed = true
        events += "<close>"
    }
}
