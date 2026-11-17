package dev.openwrtmanager.app.apply

import dev.openwrtmanager.app.ssh.ExecResult
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.testing.FakeSshExecutor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthCheckTest {
    private val ssh = FakeSshExecutor()

    @Test
    fun up_interface_with_a_pool_is_healthy() = runTest {
        ssh.respond(HealthCheck.command("guest"), "true\ndhcp\n")
        assertEquals(emptyList<HealthProblem>(), HealthCheck(ssh).check(listOf("guest")))
    }

    @Test
    fun command_reads_the_interface_and_the_dnsmasq_range() {
        assertEquals(
            "ubus call network.interface.guest status | jsonfilter -e @.up; " +
                "grep -qs 'dhcp-range=set:guest,' /var/etc/dnsmasq.conf.* && echo dhcp",
            HealthCheck.command("guest"),
        )
    }

    @Test
    fun down_interface_is_reported_after_one_retry() = runTest {
        ssh.respond(HealthCheck.command("guest"), "false\n")
        assertEquals(listOf(HealthProblem.Down("guest")), HealthCheck(ssh).check(listOf("guest")))
        assertEquals(2, ssh.commands.size)
    }

    @Test
    fun interface_that_comes_up_on_the_retry_is_healthy() = runTest {
        var calls = 0
        ssh.handler = { if (++calls == 1) ExecResult(0, "false\n", "") else ExecResult(0, "true\ndhcp\n", "") }
        assertEquals(emptyList<HealthProblem>(), HealthCheck(ssh).check(listOf("guest")))
    }

    @Test
    fun missing_pool_is_reported() = runTest {
        ssh.respond(HealthCheck.command("iot"), "true\n")
        assertEquals(listOf(HealthProblem.NoDhcp("iot")), HealthCheck(ssh).check(listOf("iot")))
    }

    @Test
    fun unknown_interface_counts_as_down() = runTest {
        ssh.respond(HealthCheck.command("guest"), "", exitCode = 4)
        assertEquals(listOf(HealthProblem.Down("guest")), HealthCheck(ssh).check(listOf("guest")))
    }

    @Test
    fun ssh_error_reports_nothing_and_does_not_throw() = runTest {
        ssh.failWith = SshError.Unreachable()
        assertEquals(emptyList<HealthProblem>(), HealthCheck(ssh).check(listOf("guest")))
    }

    @Test
    fun names_that_are_not_identifiers_are_never_sent() = runTest {
        assertEquals(emptyList<HealthProblem>(), HealthCheck(ssh).check(listOf("guest; reboot", "")))
        assertEquals(emptyList<String>(), ssh.commands)
    }

    @Test
    fun messages_are_plain() {
        assertEquals("guest is not up. Open the Network tab to check its ports.", problemMessage(HealthProblem.Down("guest")))
        assertEquals("guest has no DHCP, so devices cannot get an address.", problemMessage(HealthProblem.NoDhcp("guest")))
    }
}
