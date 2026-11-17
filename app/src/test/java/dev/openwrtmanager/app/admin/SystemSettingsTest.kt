package dev.openwrtmanager.app.admin

import dev.openwrtmanager.app.ssh.ExecResult
import dev.openwrtmanager.app.ssh.SshExecutor
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemSettingsTest {
    private val system = UciConfig(
        "system",
        listOf(
            UciSection("cfg01e48a", "system", true, 0, mapOf("hostname" to "OpenWrt", "timezone" to "IST-5:30", "zonename" to "Asia/Kolkata"), emptyMap()),
            UciSection("ntp", "timeserver", false, 1, emptyMap(), mapOf("server" to listOf("0.openwrt.pool.ntp.org"))),
        ),
    )
    private val current = systemConfigOf(system)!!

    @Test
    fun hostname_uses_the_real_section_name() {
        assertEquals(SystemConfig("cfg01e48a", "OpenWrt", "Asia/Kolkata", "IST-5:30"), current)
        val change = SystemPlanner.hostname(current, "home-router")!!
        assertEquals(listOf("set system.cfg01e48a.hostname='home-router'"), change.batch.script().lines())
        assertEquals(listOf("Rename this device from OpenWrt to home-router"), change.summary)
        assertFalse(change.mayDropConnection)
        assertNull("unchanged", SystemPlanner.hostname(current, "OpenWrt"))
    }

    @Test
    fun hostname_rules() {
        for (ok in listOf("a", "home-router", "AX6000", "x".repeat(63))) assertTrue(ok, isValidHostname(ok))
        for (bad in listOf("", "-a", "a-", "has space", "dot.ted", "x".repeat(64), "under_score")) assertFalse(bad, isValidHostname(bad))
    }

    @Test
    fun timezone_writes_both_options() {
        val change = SystemPlanner.timezone(current, "Europe/London", "GMT0BST,M3.5.0/1,M10.5.0")
        assertEquals(
            listOf("set system.cfg01e48a.zonename='Europe/London'", "set system.cfg01e48a.timezone='GMT0BST,M3.5.0/1,M10.5.0'"),
            change.batch.script().lines(),
        )
        assertEquals(listOf("Set the time zone to Europe/London"), change.summary)
    }

    @Test
    fun zones_are_parsed() {
        val zones = parseZones("Africa/Abidjan\tGMT0\nAsia/Kolkata\tIST-5:30\nbroken line\n\n")
        assertEquals(listOf("Africa/Abidjan" to "GMT0", "Asia/Kolkata" to "IST-5:30"), zones)
    }

    @Test
    fun password_rules() {
        assertTrue(isValidPassword("correct horse"))
        assertTrue(isValidPassword(" spaces at ends "))
        assertFalse(isValidPassword("short"))
        assertFalse(isValidPassword("x".repeat(129)))
        assertFalse(isValidPassword("new\nline12"))
        assertFalse(isValidPassword("pässwörd1"))
    }

    @Test
    fun password_goes_only_to_stdin() = runTest {
        val sent = mutableListOf<Pair<String, String?>>()
        val ssh = object : SshExecutor {
            override suspend fun exec(command: String, timeoutMs: Long, stdin: String?): ExecResult {
                sent += command to stdin
                return ExecResult(0, "", "")
            }

            override suspend fun close() = Unit
        }
        assertTrue(SshAdminGateway(ssh).setRootPassword("s3cret pass'\"x"))
        val (command, stdin) = sent.single()
        assertEquals(PASSWORD_COMMAND, command)
        assertFalse(command.contains("s3cret"))
        assertEquals("s3cret pass'\"x\n", stdin)
        assertTrue("whitespace kept intact", PASSWORD_COMMAND.contains("IFS= read -r p"))
    }

    @Test
    fun invalid_password_is_never_sent() = runTest {
        var calls = 0
        val ssh = object : SshExecutor {
            override suspend fun exec(command: String, timeoutMs: Long, stdin: String?): ExecResult {
                calls++
                return ExecResult(0, "", "")
            }

            override suspend fun close() = Unit
        }
        assertFalse(SshAdminGateway(ssh).setRootPassword("short"))
        assertEquals(0, calls)
    }
}
