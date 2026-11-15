package dev.openwrtmanager.app

import dev.openwrtmanager.app.apply.ApplyOutcome
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.apply.SafeApply
import dev.openwrtmanager.app.data.AuthType
import dev.openwrtmanager.app.ssh.ExecResult
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.ssh.SshExecutor
import dev.openwrtmanager.app.ssh.SshTarget
import dev.openwrtmanager.app.ssh.SshjExecutor
import dev.openwrtmanager.app.uci.UciBatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Exercises the real stage, timer, commit, confirm and rollback sequence on a real router, against a
 * scratch config directory under /tmp. The router's actual config in /etc/config is never touched and
 * nothing is reloaded. Skipped unless OWM_LIVE_HOSTS and OWM_LIVE_KEY are set; uses the first host.
 */
class LiveSafeApplyTest {
    @Test
    fun confirm_keeps_the_change_and_silence_rolls_it_back() = runBlocking<Unit> {
        val host = System.getenv("OWM_LIVE_HOSTS")?.split(',')?.firstOrNull()?.trim().orEmpty()
        val keyPath = System.getenv("OWM_LIVE_KEY").orEmpty()
        assumeTrue("live router test not configured", host.isNotEmpty() && keyPath.isNotEmpty())

        val fingerprint = SshjExecutor.probeFingerprint(host, 22)
        val ssh = SshjExecutor(SshTarget(host, 22, "root", AuthType.KEY, File(keyPath).readText(), fingerprint))
        val dir = "/tmp/owm-test-" + System.nanoTime().toString(16)
        val uci = "uci -c $dir/config -t $dir/save"
        try {
            val setup = ssh.exec(
                "mkdir -p $dir/config $dir/save && printf \"config thing 'a'\n\toption value 'old'\n\" > $dir/config/owmtest" +
                    " && $uci get owmtest.a.value",
            )
            assertEquals("old", setup.stdout.trim())

            fun change(value: String) = ChangeSet("test", listOf("set value"), UciBatch().set("owmtest", "a", "value", value), false)
            suspend fun current() = ssh.exec("$uci get owmtest.a.value").stdout.trim()

            // 1. Confirmed apply keeps the new value, also after the timer would have fired.
            val confirming = SafeApply(ssh, { System.currentTimeMillis() }, "$dir/config", 6, uci, "true")
            assertEquals(ApplyOutcome.Applied, confirming.apply(change("new")))
            assertEquals("new", current())
            delay(8_000)
            assertEquals("still there after the timer", "new", current())

            // 2. The app goes silent after committing: the router must restore the previous value by itself.
            val silent = object : SshExecutor {
                override suspend fun exec(command: String, timeoutMs: Long): ExecResult {
                    if ("OWM_CONFIRMED" in command) throw SshError.Unreachable()
                    return ssh.exec(command, timeoutMs)
                }

                override suspend fun close() = Unit
            }
            val unconfirmed = SafeApply(silent, { System.currentTimeMillis() }, "$dir/config", 6, uci, "true")
            assertEquals(ApplyOutcome.RolledBack, unconfirmed.apply(change("newer")))
            assertEquals("rolled back to the value before", "new", current())
            assertEquals("nothing left staged", "", ssh.exec("$uci changes").stdout.trim())
            println("LIVE safe apply on $host: confirm kept the change, silence rolled it back")
        } finally {
            ssh.exec("rm -rf $dir /tmp/owm-apply-*")
            ssh.close()
        }
    }
}
