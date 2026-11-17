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

    /** What the simulated router answers to the apply script. Null means the link drops mid-command. */
    private var applyResult: ExecResult? = ExecResult(0, "", "")
    private var applyError: SshError = SshError.Disconnected()

    /** Answers to successive resolve commands. An SshError entry means that attempt fails. The last entry repeats. */
    private val resolveAnswers = mutableListOf<Any>("OWM_CONFIRMED")

    private var commitAt = -1L
    private val resolveAt = mutableListOf<Long>()
    private val resolveCommands = mutableListOf<String>()

    private val change = ChangeSet(
        title = "Rename network",
        summary = listOf("Rename \"Home\" to \"Home 2\""),
        batch = UciBatch().set("wireless", "default_radio0", "ssid", "Home 2").set("network", "lan", "ipaddr", "192.168.1.1"),
        mayDropConnection = true,
    )
    private val quietChange = ChangeSet("Lease time", listOf("Change lease time"), UciBatch().set("dhcp", "lan", "leasetime", "6h"), false)

    private fun TestScope.safeApply(): SafeApply {
        ssh.handler = { cmd ->
            when {
                "setsid sh" in cmd -> {
                    commitAt = currentTime
                    applyResult ?: throw applyError
                }
                cmd.startsWith("d=/tmp/owm-apply-abcdef012345; [ -d") -> {
                    resolveAt += currentTime - commitAt
                    resolveCommands += cmd
                    val answer = if (resolveAnswers.size > 1) resolveAnswers.removeAt(0) else resolveAnswers[0]
                    if (answer is SshError) throw answer
                    ExecResult(0, "$answer\n", "")
                }
                else -> null
            }
        }
        return SafeApply(ssh, time = { currentTime }, tokenSource = { "abcdef012345" })
    }

    @Test
    fun happy_path_commits_on_a_fresh_connection_then_confirms_on_another_after_a_hold_off() = runTest {
        val phases = mutableListOf<ApplyPhase>()
        val outcome = safeApply().apply(change) { phases += it }
        assertEquals(ApplyOutcome.Applied, outcome)
        assertEquals(
            listOf(ApplyPhase.Staging, ApplyPhase.Committing, ApplyPhase.Reconnecting, ApplyPhase.Confirming),
            phases,
        )
        assertEquals(
            listOf(
                "<close>",
                ApplyScripts.apply(change.batch.script().lines(), setOf("wireless", "network"), "abcdef012345", 90),
                "<close>",
                ApplyScripts.resolve("abcdef012345", confirm = true),
            ),
            ssh.events,
        )
        assertEquals("the change must have time to take effect before it is confirmed", listOf(8_000L), resolveAt)
    }

    @Test
    fun a_change_that_cannot_cut_the_link_is_confirmed_sooner() = runTest {
        assertEquals(ApplyOutcome.Applied, safeApply().apply(quietChange))
        assertEquals(listOf(2_000L), resolveAt)
    }

    @Test
    fun empty_change_is_applied_without_touching_the_router() = runTest {
        val outcome = safeApply().apply(ChangeSet("Nothing", emptyList(), UciBatch(), mayDropConnection = false))
        assertEquals(ApplyOutcome.Applied, outcome)
        assertTrue(ssh.events.isEmpty())
    }

    @Test
    fun foreign_changes_stop_everything() = runTest {
        applyResult = ExecResult(11, "firewall.cfg1.name='x'\n", "")
        assertEquals(ApplyOutcome.ForeignChanges("firewall.cfg1.name='x'"), safeApply().apply(change))
        assertTrue(resolveAt.isEmpty())
    }

    @Test
    fun rejected_line_is_reported_and_nothing_is_confirmed() = runTest {
        applyResult = ExecResult(12, "", "uci: Invalid argument\nset wireless.nosuch.ssid\n")
        val outcome = safeApply().apply(change) as ApplyOutcome.Rejected
        assertTrue(outcome.reason.contains("set wireless.nosuch.ssid"))
        assertTrue(resolveAt.isEmpty())
    }

    @Test
    fun failure_before_the_timer_is_armed_is_a_plain_rejection() = runTest {
        for (code in listOf(10, 13)) {
            resolveAt.clear()
            applyResult = ExecResult(code, "", "")
            assertTrue(safeApply().apply(change) is ApplyOutcome.Rejected)
            assertTrue(resolveAt.isEmpty())
        }
    }

    @Test
    fun router_unreachable_from_the_start_is_a_rejection_not_a_long_wait() = runTest {
        applyResult = null
        applyError = SshError.Unreachable()
        assertTrue(safeApply().apply(change) is ApplyOutcome.Rejected)
        assertTrue(resolveAt.isEmpty())
        assertTrue(currentTime < 5_000)
    }

    @Test
    fun dropped_link_during_commit_then_slow_reconnect_still_confirms() = runTest {
        applyResult = null
        resolveAnswers.clear()
        resolveAnswers.addAll(listOf(SshError.Unreachable(), SshError.Unreachable(), SshError.Timeout(), "OWM_CONFIRMED"))
        assertEquals(ApplyOutcome.Applied, safeApply().apply(change))
        assertEquals(listOf(8_000L, 10_000L, 12_000L, 14_000L), resolveAt)
    }

    @Test
    fun the_router_is_the_judge_a_late_answer_of_reverted_is_never_reported_as_applied() = runTest {
        resolveAnswers.clear()
        resolveAnswers.addAll(listOf(SshError.Unreachable(), "OWM_REVERTED"))
        assertEquals(ApplyOutcome.RolledBack, safeApply().apply(change))
    }

    @Test
    fun rollback_in_progress_keeps_polling_until_it_finishes() = runTest {
        resolveAnswers.clear()
        resolveAnswers.addAll(listOf("OWM_BUSY", "OWM_BUSY", "OWM_REVERTED"))
        assertEquals(ApplyOutcome.RolledBack, safeApply().apply(change))
        assertEquals(3, resolveAt.size)
    }

    @Test
    fun failed_commit_is_never_confirmed_only_watched_until_the_router_undoes_it() = runTest {
        applyResult = ExecResult(20, "", "uci: commit failed")
        resolveAnswers.clear()
        resolveAnswers.addAll(listOf("OWM_BUSY", "OWM_REVERTED"))
        assertEquals(ApplyOutcome.RolledBack, safeApply().apply(change))
        assertTrue(resolveCommands.all { it == ApplyScripts.resolve("abcdef012345", confirm = false) })
        assertTrue(resolveCommands.none { "mkdir" in it })
    }

    @Test
    fun router_never_reachable_again_is_unknown_after_waiting_out_the_timer() = runTest {
        resolveAnswers.clear()
        resolveAnswers += SshError.Unreachable()
        val outcome = safeApply().apply(change)
        assertTrue(outcome is ApplyOutcome.Unknown)
        assertTrue("waits past the 90 s timer", currentTime - commitAt >= 90_000)
        assertTrue("but not forever", currentTime - commitAt <= 240_000)
    }

    @Test
    fun missing_work_directory_is_unknown_not_a_guess() = runTest {
        resolveAnswers.clear()
        resolveAnswers += "OWM_ABSENT"
        assertTrue(safeApply().apply(change) is ApplyOutcome.Unknown)
    }

    @Test
    fun preview_is_one_command_and_returns_the_staged_text() = runTest {
        val apply = safeApply()
        ssh.respond(ApplyScripts.preview(change.batch.script().lines(), setOf("wireless", "network")), "wireless.default_radio0.ssid='Home 2'\n")
        assertEquals("wireless.default_radio0.ssid='Home 2'", apply.preview(change))
        assertEquals(1, ssh.commands.size)
    }

    @Test
    fun preview_reports_foreign_changes_and_rejections() = runTest {
        val apply = safeApply()
        val command = ApplyScripts.preview(change.batch.script().lines(), setOf("wireless", "network"))
        ssh.respond(command, "firewall.cfg1.name='x'\n", exitCode = 11)
        val foreign = assertThrows(ForeignChangesException::class.java) { kotlinx.coroutines.runBlocking { apply.preview(change) } }
        assertEquals("firewall.cfg1.name='x'", foreign.pending)
        ssh.respond(command, "", exitCode = 12, stderr = "set wireless.nosuch.ssid")
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { apply.preview(change) } }
    }
}

class ApplyScriptsTest {
    private val lines = UciBatch()
        .set("wireless", "default_radio0", "key", "s3cret pass")
        .set("network", "lan", "ipaddr", "192.168.1.1")
        .script().lines()
    private val script = ApplyScripts.apply(lines, setOf("wireless", "network"), "abcdef012345", 90)
    private val dir = "/tmp/owm-apply-abcdef012345"

    private fun before(first: String, second: String) {
        assertTrue("missing: $first", first in script)
        assertTrue("missing: $second", second in script)
        assertTrue("'$first' must come before '$second'", script.indexOf(first) < script.indexOf(second))
    }

    @Test
    fun survives_the_ssh_session_ending_and_refuses_to_run_twice() {
        assertTrue(script.startsWith("trap '' HUP\n"))
        assertTrue("mkdir $dir 2>/dev/null || exit 10" in script)
        assertFalse("a rerun must not reuse the directory", "mkdir -p $dir" in script)
    }

    @Test
    fun order_is_checks_then_backup_then_stage_then_timer_then_commit() {
        before("uci changes", "cp /etc/config/wireless $dir/wireless")
        before("cp /etc/config/network $dir/network", "uci set wireless.default_radio0.key=")
        before("uci set network.lan.ipaddr=", "setsid sh $dir/rollback.sh")
        before("setsid sh $dir/rollback.sh", "touch $dir/armed")
        before("touch $dir/armed", "uci commit wireless")
        before("uci commit network", "reload_config || exit 20")
        assertFalse("nohup" in script)
    }

    @Test
    fun every_staged_line_is_its_own_checked_shell_command() {
        assertTrue("uci set wireless.default_radio0.key='s3cret pass' || fail 'set wireless.default_radio0.key'" in script)
        assertTrue("uci set network.lan.ipaddr='192.168.1.1' || fail 'set network.lan.ipaddr'" in script)
        assertFalse("uci batch must not be used: it ignores rejected lines", "batch" in script)
    }

    @Test
    fun failure_labels_never_contain_values() {
        val labels = Regex("fail '([^']*)'").findAll(script).map { it.groupValues[1] }.toList()
        assertEquals(2, labels.size)
        assertTrue(labels.none { "s3cret" in it })
    }

    @Test
    fun rollback_takes_the_lock_restores_only_affected_configs_and_wipes_its_copies() {
        val rollback = script.substringAfter("<<'OWM_RB'\n").substringBefore("\nOWM_RB")
        val steps = rollback.lines()
        assertEquals("sleep 90", steps[0])
        assertEquals("mkdir $dir/lock 2>/dev/null || { rm -rf $dir; exit 0; }", steps[1])
        assertTrue("cp $dir/wireless /etc/config/wireless" in steps)
        assertTrue("cp $dir/network /etc/config/network" in steps)
        assertFalse(rollback.contains("firewall"))
        assertTrue(steps.indexOf("reload_config") > steps.indexOf("cp $dir/network /etc/config/network"))
        assertTrue("copies hold Wi-Fi passwords", "rm -f $dir/wireless $dir/network" in steps)
        assertEquals("touch $dir/reverted", steps.last())
    }

    @Test
    fun resolve_lets_the_router_arbitrate_atomically() {
        val confirm = ApplyScripts.resolve("abcdef012345", confirm = true)
        assertTrue(confirm.startsWith("d=$dir; [ -d"))
        assertTrue("mkdir \$d/lock 2>/dev/null" in confirm)
        for (word in listOf("OWM_ABSENT", "OWM_NOT_ARMED", "OWM_CONFIRMED", "OWM_REVERTED", "OWM_BUSY")) assertTrue(word in confirm)
        val watch = ApplyScripts.resolve("abcdef012345", confirm = false)
        assertFalse("mkdir" in watch)
        assertTrue("OWM_REVERTED" in watch)
    }

    @Test
    fun unsafe_names_are_refused() {
        assertThrows(IllegalArgumentException::class.java) { ApplyScripts.apply(lines, setOf("wire less"), "abcdef012345", 90) }
        assertThrows(IllegalArgumentException::class.java) { ApplyScripts.apply(lines, setOf("x;reboot"), "abcdef012345", 90) }
        assertThrows(IllegalArgumentException::class.java) { ApplyScripts.apply(lines, setOf("wireless"), "abc;def", 90) }
        assertThrows(IllegalArgumentException::class.java) { ApplyScripts.apply(lines, emptySet(), "abcdef012345", 90) }
        assertThrows(IllegalArgumentException::class.java) { ApplyScripts.apply(listOf("reboot"), setOf("wireless"), "abcdef012345", 90) }
    }

    @Test
    fun scratch_directory_mode_never_names_the_real_config() {
        val scratch = ApplyScripts.apply(
            UciBatch().set("owmtest", "a", "value", "new").script().lines(), setOf("owmtest"), "abcdef012345", 6,
            configDir = "/tmp/owm-test-1/config", uci = "uci -c /tmp/owm-test-1/config -t /tmp/owm-test-1/save", reload = "true",
        )
        assertFalse("/etc/config" in scratch)
        assertFalse("reload_config" in scratch)
        assertTrue("uci -c /tmp/owm-test-1/config -t /tmp/owm-test-1/save commit owmtest" in scratch)
        assertTrue("uci -c /tmp/owm-test-1/config -t /tmp/owm-test-1/save set owmtest.a.value='new' || fail" in scratch)
    }

    @Test
    fun preview_stages_shows_and_always_unstages_in_one_script() {
        val preview = ApplyScripts.preview(lines, setOf("wireless", "network"))
        assertTrue(preview.startsWith("trap '' HUP\n"))
        assertFalse("commit" in preview)
        assertFalse("reload_config" in preview)
        assertTrue(preview.endsWith("uci revert wireless; uci revert network\n# OWM_END"))
        assertTrue(preview.indexOf("exit 11") < preview.indexOf("uci set"))
    }

    @Test
    fun scripts_end_with_the_marker_and_the_runner_refuses_a_truncated_upload() {
        assertTrue(script.endsWith("\n# OWM_END"))
        val runner = ApplyScripts.runner("abcdef012345")
        assertTrue("short enough for any ssh server's command line", runner.length < 400)
        assertTrue("cat > /tmp/owm-run-abcdef012345.sh" in runner)
        assertTrue("tail -n 1 /tmp/owm-run-abcdef012345.sh" in runner)
        assertTrue(runner.indexOf("exit 14") < runner.indexOf("sh /tmp/owm-run-abcdef012345.sh;"))
        assertThrows(IllegalArgumentException::class.java) { ApplyScripts.runner("x; reboot") }
    }
}
