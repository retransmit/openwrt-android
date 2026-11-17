package dev.openwrtmanager.app.apply

import dev.openwrtmanager.app.domain.TimeSource
import dev.openwrtmanager.app.ssh.ExecResult
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.ssh.SshExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/**
 * The only code that commits config on a router.
 *
 * The whole change runs as one script on the router (see [ApplyScripts.apply]), which arms a timer
 * that restores the previous config. The app then waits for the change to take effect, opens a
 * NEW connection, and asks the router to keep it. Being able to do that is the proof that the
 * change did not cut the phone off. The router decides atomically between "kept" and "undone",
 * so the answer shown to the user is what actually happened, even if it arrives late.
 */
class SafeApply(
    private val ssh: SshExecutor,
    private val time: TimeSource,
    private val configDir: String = "/etc/config",
    private val rollbackSeconds: Int = DEFAULT_ROLLBACK_SECONDS,
    private val uci: String = "uci",
    private val reload: String = "reload_config",
    /** Wait after committing before the first confirm. Null picks by how disruptive the change is. */
    private val holdOffMs: Long? = null,
    private val tokenSource: () -> String = ::randomToken,
) : Applier {

    override suspend fun preview(change: ChangeSet): String {
        if (change.isEmpty()) return ""
        val script = ApplyScripts.preview(change.batch.script().lines(), change.batch.configs, uci)
        val result = ssh.exec(ApplyScripts.runner(tokenSource()), timeoutMs = 30_000, stdin = script)
        return when (result.exitCode) {
            0 -> result.stdout.trim()
            ApplyScripts.EXIT_FOREIGN -> throw ForeignChangesException(result.stdout.trim())
            else -> throw IllegalStateException(result.stderr.trim().ifEmpty { "the router refused the change" })
        }
    }

    override suspend fun apply(change: ChangeSet, onPhase: (ApplyPhase) -> Unit): ApplyOutcome {
        if (change.isEmpty()) return ApplyOutcome.Applied
        val token = tokenSource()
        val script = ApplyScripts.apply(change.batch.script().lines(), change.batch.configs, token, rollbackSeconds, configDir, uci, reload)

        onPhase(ApplyPhase.Staging)
        // Start from a fresh connection: the executor only retries a command on a connection that was
        // already open, and this command must never be sent twice.
        ssh.close()
        onPhase(ApplyPhase.Committing)
        val commitAt = time.nowMillis()
        val result: ExecResult? = try {
            ssh.exec(ApplyScripts.runner(token), timeoutMs = 30_000, stdin = script)
        } catch (e: SshError.Unreachable) {
            return ApplyOutcome.Rejected("Could not reach the router. Nothing was changed.")
        } catch (e: SshError.AuthFailed) {
            return ApplyOutcome.Rejected("The router refused the login. Nothing was changed.")
        } catch (e: SshError) {
            // The link dropped while the script was running, which a network reload can cause.
            // The script carries on by itself; the router will tell us how it ended.
            null
        }
        when (result?.exitCode) {
            // Committed, or the commit failed after the timer was armed, or the channel closed without
            // a status (treated like a dropped link): the router will say how it ended.
            null, 0, ApplyScripts.EXIT_COMMIT, -1 -> Unit
            ApplyScripts.EXIT_FOREIGN -> return ApplyOutcome.ForeignChanges(result.stdout.trim())
            ApplyScripts.EXIT_REFUSED -> return ApplyOutcome.Rejected(
                "The router refused part of the change: " + result.stderr.trim().lines().last(),
            )
            ApplyScripts.EXIT_PREPARE -> return ApplyOutcome.Rejected("The router could not prepare the change. Its temporary storage may be full.")
            ApplyScripts.EXIT_UPLOAD -> return ApplyOutcome.Rejected("The connection was interrupted while sending the change. Nothing was changed.")
            ApplyScripts.EXIT_BUSY -> return ApplyOutcome.Rejected(
                "Another change on this router is still waiting to be confirmed. Try again in a couple of minutes.",
            )
            // Any other status means the script stopped before the timer was armed, so nothing was committed.
            else -> return ApplyOutcome.Rejected(
                "The router could not run the change: " + result.stderr.trim().lines().last().ifEmpty { "exit ${result.exitCode}" },
            )
        }
        // A commit that failed after the timer was armed must not be kept: only watch it being undone.
        val mayConfirm = result?.exitCode != ApplyScripts.EXIT_COMMIT

        onPhase(ApplyPhase.Reconnecting)
        // Give the reload time to take effect, then drop the old connection. Confirming over the
        // connection that was open before the change would prove nothing.
        delay(holdOffMs ?: if (change.mayDropConnection) DISRUPTIVE_HOLD_OFF_MS else QUIET_HOLD_OFF_MS)
        ssh.close()

        val giveUpAt = commitAt + rollbackSeconds * 1_000L + GIVE_UP_AFTER_TIMER_MS
        var lastError: Throwable = IllegalStateException("The change was neither confirmed nor undone")
        while (true) {
            try {
                val answer = withTimeoutOrNull(ATTEMPT_MS) {
                    ssh.exec(ApplyScripts.resolve(token, mayConfirm), timeoutMs = 8_000).stdout
                }
                when {
                    answer == null -> lastError = SshError.Timeout()
                    "OWM_CONFIRMED" in answer -> {
                        onPhase(ApplyPhase.Confirming)
                        return ApplyOutcome.Applied
                    }
                    "OWM_REVERTED" in answer -> return ApplyOutcome.RolledBack
                    // Gone without a verdict: for example the router rebooted. Do not guess.
                    "OWM_ABSENT" in answer -> return ApplyOutcome.Unknown(IllegalStateException("The router has no record of this change"))
                    // OWM_BUSY (being undone right now) or OWM_NOT_ARMED: ask again.
                }
            } catch (e: SshError) {
                lastError = e
            }
            if (time.nowMillis() + RETRY_MS > giveUpAt) break
            delay(RETRY_MS)
        }
        return ApplyOutcome.Unknown(lastError)
    }

    companion object {
        const val DEFAULT_ROLLBACK_SECONDS = 90

        private const val RETRY_MS = 2_000L
        private const val ATTEMPT_MS = 15_000L
        private const val DISRUPTIVE_HOLD_OFF_MS = 8_000L
        private const val QUIET_HOLD_OFF_MS = 2_000L

        /** After its timer fires the router needs to reload, and the phone may need to rejoin Wi-Fi. */
        private const val GIVE_UP_AFTER_TIMER_MS = 120_000L

        private fun randomToken(): String {
            val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
