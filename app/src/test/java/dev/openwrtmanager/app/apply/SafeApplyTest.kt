package dev.openwrtmanager.app.apply

import dev.openwrtmanager.app.ssh.ExecResult
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.testing.FakeSshExecutor
import dev.openwrtmanager.app.uci.UciBatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SafeApplyTest {
    private val ssh = FakeSshExecutor()

    // Simulated router behaviour.
    private var pending = ""
    private var rejectStage = false
    private var dropOnCommit = false
    private var confirmFailures = 0
    private var unreachableAfterCommit = false
    private var routerReverted = false
    private var changesThrows = false

    private var commitAt = -1L
    private val confirmAttemptsAt = mutableListOf<Long>()

    private val change = ChangeSet(
        title = "Rename network",
        summary = listOf("Rename \"Home\" to \"Home 2\""),
        batch = UciBatch().set("wireless", "default_radio0", "ssid", "Home 2").set("network", "lan", "ipaddr", "192.168.1.1"),
        mayDropConnection = true,
    )

    private fun ok(stdout: String = "") = ExecResult(0, stdout, "")

    private fun TestScope.safeApply(): SafeApply {
        ssh.handler = { cmd ->
            when {
                cmd == "uci changes" -> if (changesThrows) throw SshError.Disconnected() else ok(pending)
                cmd.startsWith("uci -q batch") -> if (rejectStage) ExecResult(1, "", "uci: Invalid argument") else ok()
                cmd.startsWith("uci revert ") -> ok()
                "setsid sh" in cmd -> {
                    commitAt = currentTime
                    if (dropOnCommit) throw SshError.Disconnected()
                    ok()
                }
                "echo OWM_CONFIRMED" in cmd -> {
                    confirmAttemptsAt += currentTime - commitAt
                    if (unreachableAfterCommit || confirmFailures-- > 0) throw SshError.Unreachable()
                    ok("OWM_CONFIRMED\n")
                }
                "echo OWM_REVERTED" in cmd -> when {
                    unreachableAfterCommit -> throw SshError.Unreachable()
                    routerReverted -> ok("OWM_REVERTED\n")
                    else -> ExecResult(1, "", "")
                }
                else -> null
            }
        }
        return SafeApply(ssh, time = { currentTime }, tokenSource = { "abcdef012345" })
    }

    @Test
    fun happy_path_stages_arms_commits_and_confirms() = runTest {
        val phases = mutableListOf<ApplyPhase>()
        val outcome = safeApply().apply(change) { phases += it }
        assertEquals(ApplyOutcome.Applied, outcome)
        assertEquals(
            listOf(ApplyPhase.Staging, ApplyPhase.Committing, ApplyPhase.Reconnecting, ApplyPhase.Confirming),
            phases,
        )
        assertEquals(4, ssh.commands.size)
        assertEquals("uci changes", ssh.commands[0])
        assertEquals("uci -q batch <<'OWM_EOF'\n${change.batch.script()}\nOWM_EOF", ssh.commands[1])
        assertEquals(ApplyScripts.armAndCommit(setOf("wireless", "network"), "abcdef012345", 45), ssh.commands[2])
        assertEquals("touch /tmp/owm-apply-abcdef012345/confirmed && echo OWM_CONFIRMED", ssh.commands[3])
    }

    @Test
    fun foreign_changes_stop_everything() = runTest {
        pending = "firewall.cfg1.name='x'\n"
        val outcome = safeApply().apply(change)
        assertEquals(ApplyOutcome.ForeignChanges("firewall.cfg1.name='x'"), outcome)
        assertEquals(listOf("uci changes"), ssh.commands)
    }

    @Test
    fun rejected_stage_is_reverted_and_never_committed() = runTest {
        rejectStage = true
        val outcome = safeApply().apply(change)
        assertEquals(ApplyOutcome.Rejected("uci: Invalid argument"), outcome)
        assertTrue("uci revert wireless" in ssh.commands)
        assertTrue("uci revert network" in ssh.commands)
        assertFalse(ssh.commands.any { "commit" in it })
    }

    @Test
    fun empty_change_is_applied_without_touching_the_router() = runTest {
        val outcome = safeApply().apply(ChangeSet("Nothing", emptyList(), UciBatch(), mayDropConnection = false))
        assertEquals(ApplyOutcome.Applied, outcome)
        assertTrue(ssh.commands.isEmpty())
    }

    @Test
    fun dropped_link_during_commit_then_slow_reconnect_still_confirms() = runTest {
        dropOnCommit = true
        confirmFailures = 3
        assertEquals(ApplyOutcome.Applied, safeApply().apply(change))
        assertEquals(listOf(0L, 2_000L, 4_000L, 6_000L), confirmAttemptsAt)
    }

    @Test
    fun no_confirm_in_time_and_router_reverted_is_rolled_back() = runTest {
        confirmFailures = Int.MAX_VALUE
        routerReverted = true
        assertEquals(ApplyOutcome.RolledBack, safeApply().apply(change))
        assertTrue("no confirm later than 40 s after commit", confirmAttemptsAt.all { it <= 40_000 })
        assertTrue(confirmAttemptsAt.size >= 20)
    }

    @Test
    fun router_never_reachable_again_is_unknown() = runTest {
        unreachableAfterCommit = true
        val outcome = safeApply().apply(change)
        assertTrue(outcome is ApplyOutcome.Unknown)
        assertTrue("gives the router time to revert before giving up", currentTime - commitAt >= 45_000)
        assertTrue("but does not wait forever", currentTime - commitAt <= 90_000)
    }

    @Test
    fun reachable_but_neither_confirmed_nor_reverted_is_unknown() = runTest {
        confirmFailures = Int.MAX_VALUE
        routerReverted = false
        assertTrue(safeApply().apply(change) is ApplyOutcome.Unknown)
    }

    @Test
    fun preview_returns_staged_changes_then_unstages() = runTest {
        val apply = safeApply()
        var calls = 0
        val base = ssh.handler!!
        ssh.handler = { cmd ->
            if (cmd == "uci changes") {
                if (calls++ == 0) ok("") else ok("wireless.default_radio0.ssid='Home 2'\n")
            } else {
                base(cmd)
            }
        }
        assertEquals("wireless.default_radio0.ssid='Home 2'", apply.preview(change))
        assertEquals(listOf("uci revert wireless", "uci revert network"), ssh.commands.takeLast(2))
        assertFalse(ssh.commands.any { "commit" in it })
    }

    @Test
    fun preview_unstages_even_when_reading_changes_fails() = runTest {
        val apply = safeApply()
        var calls = 0
        val base = ssh.handler!!
        ssh.handler = { cmd ->
            if (cmd == "uci changes" && calls++ > 0) throw SshError.Timeout() else base(cmd)
        }
        assertThrows(SshError.Timeout::class.java) { kotlinx.coroutines.runBlocking { apply.preview(change) } }
        assertEquals(listOf("uci revert wireless", "uci revert network"), ssh.commands.takeLast(2))
    }

    @Test
    fun preview_never_stages_on_top_of_foreign_changes() = runTest {
        pending = "firewall.cfg1.name='x'\n"
        val error = assertThrows(ForeignChangesException::class.java) {
            kotlinx.coroutines.runBlocking { safeApply().preview(change) }
        }
        assertEquals("firewall.cfg1.name='x'", error.pending)
        assertEquals(listOf("uci changes"), ssh.commands)
    }
}

class ApplyScriptsTest {
    private val script = ApplyScripts.armAndCommit(setOf("wireless", "network"), "abcdef012345", 45)

    @Test
    fun timer_is_detached_with_setsid_before_any_commit() {
        assertTrue("setsid sh" in script)
        assertFalse("nohup" in script)
        assertTrue(script.indexOf("setsid sh") < script.indexOf("uci commit"))
        assertTrue(script.indexOf("uci commit network") < script.lastIndexOf("reload_config"))
    }

    @Test
    fun backups_are_taken_before_the_timer_starts() {
        assertTrue(script.indexOf("cp /etc/config/wireless") < script.indexOf("setsid sh"))
        assertTrue(script.indexOf("cp /etc/config/network") < script.indexOf("setsid sh"))
    }

    @Test
    fun rollback_restores_exactly_the_affected_configs() {
        val rollback = script.substringAfter("<<'OWM_RB'\n").substringBefore("\nOWM_RB")
        assertTrue(rollback.startsWith("sleep 45\n"))
        assertTrue("[ -e /tmp/owm-apply-abcdef012345/confirmed ]" in rollback)
        assertTrue("cp /tmp/owm-apply-abcdef012345/wireless /etc/config/wireless" in rollback)
        assertTrue("cp /tmp/owm-apply-abcdef012345/network /etc/config/network" in rollback)
        assertFalse("firewall" in rollback)
        assertTrue(rollback.trimEnd().endsWith("touch /tmp/owm-apply-abcdef012345/reverted"))
        assertTrue(rollback.indexOf("reload_config") < rollback.indexOf("reverted"))
    }

    @Test
    fun unsafe_names_are_refused() {
        assertThrows(IllegalArgumentException::class.java) { ApplyScripts.armAndCommit(setOf("wire less"), "abcdef012345", 45) }
        assertThrows(IllegalArgumentException::class.java) { ApplyScripts.armAndCommit(setOf("x;reboot"), "abcdef012345", 45) }
        assertThrows(IllegalArgumentException::class.java) { ApplyScripts.armAndCommit(setOf("wireless"), "abc;def", 45) }
        assertThrows(IllegalArgumentException::class.java) { ApplyScripts.armAndCommit(emptySet(), "abcdef012345", 45) }
    }

    @Test
    fun scratch_directory_mode_never_names_the_real_config() {
        val scratch = ApplyScripts.armAndCommit(
            setOf("owmtest"), "abcdef012345", 6,
            configDir = "/tmp/owm-test-1/config", uci = "uci -c /tmp/owm-test-1/config -t /tmp/owm-test-1/save", reload = "true",
        )
        assertFalse("/etc/config" in scratch)
        assertFalse("reload_config" in scratch)
        assertTrue("uci -c /tmp/owm-test-1/config -t /tmp/owm-test-1/save commit owmtest" in scratch)
    }
}
