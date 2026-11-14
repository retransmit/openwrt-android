package dev.openwrtmanager.app.router

import dev.openwrtmanager.app.domain.Band
import dev.openwrtmanager.app.domain.Counters
import dev.openwrtmanager.app.testing.CMD_BOARD
import dev.openwrtmanager.app.testing.CMD_IFACES
import dev.openwrtmanager.app.testing.CMD_LEASES
import dev.openwrtmanager.app.testing.FakeSshExecutor
import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.testing.healthyRouter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterClientTest {
    private val fake = healthyRouter()
    private val client = RouterClient(fake, clock = { 1000 })

    @Test
    fun systemInfo_maps_fields() = runTest {
        val i = client.systemInfo()
        assertEquals("Home-Router", i.hostname)
        assertEquals("GL.iNet GL-MT6000", i.model)
        assertEquals("OpenWrt 24.10.2 r28739", i.firmware)
        assertEquals(93784L, i.uptimeSeconds)
        assertEquals(0.08, i.load1, 0.001)
        assertEquals(1016201216L, i.memTotalBytes)
        assertEquals(812310528L, i.memAvailableBytes)
    }

    @Test
    fun systemInfo_falls_back_when_available_memory_missing() = runTest {
        fake.respond("ubus call system info", """{"uptime":5,"load":[0,0,0],"memory":{"total":1000,"free":300,"buffered":50}}""")
        assertEquals(350L, client.systemInfo().memAvailableBytes)
    }

    @Test
    fun uplink_is_default_route_device() = runTest {
        assertEquals("eth1", client.uplinkDevice())
    }

    @Test
    fun uplink_null_without_default_route() = runTest {
        fake.respond(CMD_IFACES, Fixtures.read("iface_dump_no_wan.json"))
        assertNull(client.uplinkDevice())
    }

    @Test
    fun counters_quotes_device_name() = runTest {
        assertEquals(Counters(9876543210, 1234567890), client.counters("eth1"))
        assertTrue(fake.commands.last().endsWith("""'{"name":"eth1"}'"""))
    }

    @Test
    fun counters_escapes_hostile_device_name() = runTest {
        runCatching { client.counters("x'; reboot; '") }
        val sent = fake.commands.last()
        assertEquals("""ubus call network.device status '{"name":"x'\''; reboot; '\''"}'""", sent)
    }

    @Test
    fun wifi_lists_only_ap_ifaces() = runTest {
        val n = client.wifiNetworks()
        assertEquals(listOf("Home", "Joe's \"IoT\" 📶", "Home", "Guest"), n.map { it.ssid })
        assertEquals(listOf(Band.GHZ_2_4, Band.GHZ_2_4, Band.GHZ_5, Band.GHZ_5), n.map { it.band })
        assertEquals(2, n[0].clientCount)
        assertEquals(0, n[1].clientCount)
        assertTrue(n[0].up)
        assertFalse(n[3].up)
        assertNull(n[3].ifname)
        assertEquals("none", n[3].encryption)
    }

    @Test
    fun leaseCount_skips_expired() = runTest {
        fake.respond(
            CMD_LEASES,
            "900 aa:bb:cc:00:00:01 192.168.1.10 old *\n" +
                "2000 aa:bb:cc:00:00:02 192.168.1.11 phone 01:aa\n" +
                "0 aa:bb:cc:00:00:03 192.168.1.12 static *\n",
        )
        assertEquals(2, client.leaseCount())
    }

    @Test
    fun leaseCount_null_when_no_file() = runTest {
        fake.respond(CMD_LEASES, "", exitCode = 1)
        assertNull(client.leaseCount())
    }

    @Test(expected = RouterError.NotOpenWrt::class)
    fun ubus_missing() = runTest {
        RouterClient(FakeSshExecutor()).systemInfo()
    }

    @Test(expected = RouterError.BadResponse::class)
    fun garbage_json() = runTest {
        fake.respond(CMD_BOARD, "<html>")
        client.systemInfo()
    }

    @Test(expected = RouterError.CommandFailed::class)
    fun other_failure_is_command_failed() = runTest {
        fake.respond(CMD_BOARD, "", exitCode = 4, stderr = "Command failed: Permission denied")
        client.systemInfo()
    }
}
