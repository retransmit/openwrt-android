package dev.openwrtmanager.app.apply

import dev.openwrtmanager.app.domain.TimeSource
import dev.openwrtmanager.app.ssh.ExecResult
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.ssh.SshExecutor
import kotlinx.coroutines.delay
import java.security.SecureRandom

/**
 * The only code that commits config on a router.
 *
 * It refuses to run on top of someone else's uncommitted changes, stages the batch, and then in one
 * command arms a rollback timer on the router and commits. If the app cannot reach the router again
 * to confirm, the router restores the previous config by itself.
 */
class SafeApply(
    private val ssh: SshExecutor,
    private val time: TimeSource,
    private val configDir: String = "/etc/config",
    private val rollbackSeconds: Int = 45,
    private val uci: String = "uci",
    private val reload: String = "reload_config",
    private val tokenSource: () -> String = ::randomToken,
) : Applier {

    override suspend fun preview(change: ChangeSet): String {
        if (change.isEmpty()) return ""
        val pending = changes()
        if (pending.isNotEmpty()) throw ForeignChangesException(pending)
        try {
            val staged = ssh.exec(ApplyScripts.stage(change.batch.script(), uci))
            if (staged.exitCode != 0) throw IllegalStateException(staged.stderr.trim().ifEmpty { "the router refused the change" })
            return changes()
        } finally {
            revert(change)
        }
    }

    override suspend fun apply(change: ChangeSet, onPhase: (ApplyPhase) -> Unit): ApplyOutcome {
        if (change.isEmpty()) return ApplyOutcome.Applied
        val configs = change.batch.configs

        onPhase(ApplyPhase.Staging)
        try {
            val pending = changes()
            if (pending.isNotEmpty()) return ApplyOutcome.ForeignChanges(pending)
        } catch (e: SshError) {
            return ApplyOutcome.Rejected("Lost the connection before anything was changed")
        }
        try {
            val staged = ssh.exec(ApplyScripts.stage(change.batch.script(), uci))
            if (staged.exitCode != 0) {
                revert(change)
                return ApplyOutcome.Rejected(staged.stderr.trim().ifEmpty { "The router refused the change" })
            }
        } catch (e: SshError) {
            runCatchingSsh { revert(change) }
            return ApplyOutcome.Rejected("Lost the connection before anything was changed")
        }

        onPhase(ApplyPhase.Committing)
        val token = tokenSource()
        val commitAt = time.nowMillis()
        val committed: ExecResult? = try {
            ssh.exec(ApplyScripts.armAndCommit(configs, token, rollbackSeconds, configDir, uci, reload))
        } catch (e: SshError) {
            // Expected when the reload cuts the link mid-command. The timer decides from here.
            null
        }
        val commitFailed = committed != null && committed.exitCode != 0
        if (commitFailed) runCatchingSsh { revert(change) }

        var lastError: Throwable = IllegalStateException("The change was neither confirmed nor undone")
        if (!commitFailed) {
            onPhase(ApplyPhase.Reconnecting)
            val confirmUntil = commitAt + (rollbackSeconds - CONFIRM_MARGIN_SECONDS) * 1_000L
            while (true) {
                try {
                    if ("OWM_CONFIRMED" in ssh.exec(ApplyScripts.confirm(token)).stdout) {
                        onPhase(ApplyPhase.Confirming)
                        return ApplyOutcome.Applied
                    }
                } catch (e: SshError) {
                    lastError = e
                }
                if (time.nowMillis() + RETRY_MS > confirmUntil) break
                delay(RETRY_MS)
            }
        }

        // Not confirmed. Wait for the router's timer to fire, then find out what it did.
        val timerAt = commitAt + rollbackSeconds * 1_000L
        val settleBy = timerAt + SETTLE_MS
        val giveUpAt = timerAt + GIVE_UP_MS
        (timerAt + RETRY_MS - time.nowMillis()).let { if (it > 0) delay(it) }
        while (true) {
            try {
                if ("OWM_REVERTED" in ssh.exec(ApplyScripts.checkReverted(token)).stdout) return ApplyOutcome.RolledBack
                // Reachable but not reverted yet: allow the reload a moment to finish, then stop asking.
                if (time.nowMillis() >= settleBy) break
            } catch (e: SshError) {
                lastError = e
            }
            if (time.nowMillis() + RETRY_MS > giveUpAt) break
            delay(RETRY_MS)
        }
        return ApplyOutcome.Unknown(lastError)
    }

    private suspend fun changes(): String {
        val result = ssh.exec("$uci changes")
        if (result.exitCode != 0) throw IllegalStateException(result.stderr.trim().ifEmpty { "could not read pending changes" })
        return result.stdout.trim()
    }

    private suspend fun revert(change: ChangeSet) {
        for (config in change.batch.configs) ssh.exec("$uci revert $config")
    }

    private suspend fun runCatchingSsh(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: SshError) {
            // Best effort while the link is down.
        }
    }

    private companion object {
        const val RETRY_MS = 2_000L
        const val CONFIRM_MARGIN_SECONDS = 5
        const val SETTLE_MS = 15_000L
        const val GIVE_UP_MS = 30_000L

        fun randomToken(): String {
            val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
