package dev.openwrtmanager.app.clients

import dev.openwrtmanager.app.ssh.ExecResult
import dev.openwrtmanager.app.testing.CMD_WIRELESS
import dev.openwrtmanager.app.testing.FakeSshExecutor
import dev.openwrtmanager.app.testing.Fixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientSourcesTest {
    @Test
    fun leases_drop_expired_and_star_names() {
        val leases = parseLeases(Fixtures.read("leases.txt"))
        assertEquals(
            listOf(
                Lease("C0:47:54:AA:00:01", "192.168.1.192", "Lee", 1794962944),
                Lease("EA:D5:C5:AA:00:02", "192.168.1.126", "I2302", 1794975344),
                Lease("58:11:22:AA:00:03", "192.168.1.114", null, 1794975515),
                Lease("02:00:00:AA:00:0A", "192.168.1.160", "Printer", 0),
            ),
            leases,
        )
        assertEquals("no clock line means nothing can be trusted", emptyList<Lease>(), parseLeases("garbage\n1 aa:bb:cc:dd:ee:ff 1.2.3.4 x y"))
    }

    @Test
    fun assoclist_reads_signal_and_time() {
        assertEquals(
            listOf(
                Association("EA:D5:C5:AA:00:02", "phy1-ap0", -37, 1280),
                Association("02:00:00:AA:00:0D", "phy1-ap0", -71, 60),
            ),
            parseAssoclist(Fixtures.read("assoclist_phy1-ap0.json"), "phy1-ap0"),
        )
        assertEquals(emptyList<Association>(), parseAssoclist("""{"results":[]}""", "phy0-ap0"))
        assertEquals(emptyList<Association>(), parseAssoclist("not json", "phy0-ap0"))
    }

    @Test
    fun odd_neighbour_lines_are_skipped() {
        assertEquals(
            listOf(
                Neighbour("192.168.1.114", "br-lan.1", "58:11:22:AA:00:03"),
                Neighbour("192.168.1.126", "br-lan.1", "EA:D5:C5:AA:00:02"),
                Neighbour("192.168.1.170", "br-lan.1", "02:00:00:AA:00:0B"),
                Neighbour("192.168.1.171", "br-lan.1", "02:00:00:AA:00:0C"),
                Neighbour("192.168.0.1", "wan", "8C:90:2D:AA:00:20"),
                Neighbour("192.168.0.2", "wan", "10:27:F5:AA:00:21"),
            ),
            parseNeighbours(Fixtures.read("ip_neigh.txt")),
        )
    }

    @Test
    fun host_hints_are_names_by_mac() {
        assertEquals(
            mapOf("58:11:22:AA:00:03" to "Dexel", "02:00:00:AA:00:0D" to "Kitchen-Speaker"),
            parseHostHints(Fixtures.read("host_hints.json")),
        )
        assertEquals(emptyMap<String, String>(), parseHostHints("nope"))
    }

    private fun router(): FakeSshExecutor = FakeSshExecutor().apply {
        respond(CMD_WIRELESS, Fixtures.read("wireless.json"))
        respond(ClientSources.CMD_LEASES, Fixtures.read("leases.txt"))
        respond(ClientSources.CMD_NEIGH, Fixtures.read("ip_neigh.txt"))
        respond(ClientSources.CMD_HINTS, Fixtures.read("host_hints.json"))
        handler = { command ->
            if (command.startsWith("ubus call iwinfo assoclist")) ExecResult(0, Fixtures.read("assoclist_phy1-ap0.json"), "") else null
        }
    }

    @Test
    fun all_sources_are_read() = runTest {
        val readings = ClientSources(router()).read()
        assertEquals(4, readings.leases.size)
        assertTrue(readings.associations.isNotEmpty())
        assertEquals(6, readings.neighbours.size)
        assertEquals("Dexel", readings.hintNames["58:11:22:AA:00:03"])
        assertTrue(readings.wifi.isNotEmpty())
        assertEquals(emptyList<String>(), readings.missing)
    }

    @Test
    fun one_failing_source_is_reported_not_thrown() = runTest {
        val ssh = router().apply {
            respond(ClientSources.CMD_NEIGH, "", exitCode = 1, stderr = "ip: not found")
            handler = { command -> if (command.startsWith("ubus call iwinfo")) ExecResult(4, "", "Not found") else null }
        }
        val readings = ClientSources(ssh).read()
        assertEquals(listOf("Wi-Fi details", "neighbours"), readings.missing)
        assertEquals(4, readings.leases.size)
    }

    @Test
    fun missing_luci_is_not_reported() = runTest {
        val ssh = router().apply { respond(ClientSources.CMD_HINTS, "", exitCode = 4, stderr = "Not found") }
        val readings = ClientSources(ssh).read()
        assertEquals(emptyList<String>(), readings.missing)
        assertEquals(emptyMap<String, String>(), readings.hintNames)
    }

    @Test
    fun ifnames_are_checked_before_use() {
        assertEquals("""ubus call iwinfo assoclist '{"device":"phy1-ap0"}'""", ClientSources.assoclistCommand("phy1-ap0"))
        assertEquals(null, ClientSources.assoclistCommand("phy1; reboot"))
    }
}
