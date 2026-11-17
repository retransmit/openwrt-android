package dev.openwrtmanager.app.clients

import dev.openwrtmanager.app.domain.Band
import dev.openwrtmanager.app.domain.WifiNetwork
import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import dev.openwrtmanager.app.uci.parseUciConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientsTest {
    private fun load(name: String, file: String) = parseUciConfig(name, Fixtures.read(file))

    private val router = DeviceConfig(
        network = load("network", "uci_network.json"),
        dhcp = load("dhcp", "uci_dhcp.json"),
        firewall = load("firewall", "uci_firewall.json"),
        l3Devices = mapOf("lan" to "br-lan", "wan" to "wan", "wan6" to "wan"),
        firewallEnabled = true,
        dhcpv4Required = true,
    )
    private val accessPoint = DeviceConfig(
        network = load("network", "uci_network_ap.json"),
        dhcp = load("dhcp", "uci_dhcp_ap.json"),
        firewall = load("firewall", "uci_firewall_ap.json"),
        l3Devices = mapOf("lan" to "br-lan"),
        firewallEnabled = true,
        dhcpv4Required = true,
    )

    private val wifi = listOf(
        WifiNetwork("phy0-ap0", "Home", Band.GHZ_2_4, "psk2", true, 0),
        WifiNetwork("phy1-ap0", "Home", Band.GHZ_5, "psk2", true, 0),
    )

    private fun readings(
        leases: List<Lease> = emptyList(),
        associations: List<Association> = emptyList(),
        neighbours: List<Neighbour> = emptyList(),
        hints: Map<String, String> = emptyMap(),
    ) = ClientReadings(leases, associations, neighbours, hints, wifi, emptyList())

    private val phone = "EA:D5:C5:AA:00:02"
    private val pc = "58:11:22:AA:00:03"

    @Test
    fun wifi_client_gets_network_band_and_signal() {
        val clients = mergeClients(
            readings(
                leases = listOf(Lease(phone, "192.168.1.126", "Phone", 0)),
                associations = listOf(Association(phone, "phy1-ap0", -37, 1280)),
            ),
            router,
        )
        assertEquals(
            listOf(Client(phone, "Phone", "192.168.1.126", ClientLink.WIFI, "Home", Band.GHZ_5, "phy1-ap0", -37, 1280, reserved = false, blocked = false)),
            clients,
        )
    }

    @Test
    fun wired_device_from_neighbours() {
        val client = mergeClients(readings(neighbours = listOf(Neighbour("192.168.1.114", "br-lan.1", pc))), router).single()
        assertEquals(ClientLink.WIRED, client.link)
        assertEquals("192.168.1.114", client.ip)
        assertEquals(null, client.ssid)
    }

    @Test
    fun mac_case_does_not_split_a_client() {
        val clients = mergeClients(
            readings(
                leases = listOf(Lease(phone, "192.168.1.126", "Phone", 0)),
                associations = listOf(Association(phone.lowercase(), "phy1-ap0", -40, 5)),
                neighbours = listOf(Neighbour("192.168.1.126", "br-lan.1", phone.lowercase())),
            ),
            router,
        )
        assertEquals(1, clients.size)
        assertEquals(phone, clients.single().mac)
        assertEquals(ClientLink.WIFI, clients.single().link)
    }

    @Test
    fun name_prefers_reservation_then_lease_then_hint() {
        val reserved = router.copy(
            dhcp = UciConfig(
                "dhcp",
                router.dhcp.sections + UciSection("owm_host_x", "host", false, 50, mapOf("name" to "Desk-PC", "mac" to pc.lowercase(), "ip" to "192.168.1.114"), emptyMap()),
            ),
        )
        val seen = readings(
            leases = listOf(Lease(pc, "192.168.1.114", "dexel", 0)),
            neighbours = listOf(Neighbour("192.168.1.114", "br-lan.1", pc)),
            hints = mapOf(pc to "Hint"),
        )
        assertEquals("Desk-PC", mergeClients(seen, reserved).single().name)
        assertTrue(mergeClients(seen, reserved).single().reserved)
        assertEquals("dexel", mergeClients(seen, router).single().name)
        assertEquals("Hint", mergeClients(seen.copy(leases = emptyList()), router).single().name)
        assertEquals(null, mergeClients(seen.copy(leases = emptyList(), hintNames = emptyMap()), router).single().name)
    }

    @Test
    fun upstream_devices_are_never_listed() {
        val upstream = readings(neighbours = listOf(Neighbour("192.168.0.1", "wan", "8C:90:2D:AA:00:20")))
        assertTrue(mergeClients(upstream, router).isEmpty())
        // WAN down: no l3 device reported, the configured device still counts.
        assertTrue(mergeClients(upstream, router.copy(l3Devices = emptyMap())).isEmpty())
        // PPPoE: traffic shows on the l3 device.
        val pppoe = readings(neighbours = listOf(Neighbour("10.0.0.1", "pppoe-wan", "8C:90:2D:AA:00:20")))
        assertTrue(mergeClients(pppoe, router.copy(l3Devices = mapOf("wan" to "pppoe-wan"))).isEmpty())
        assertEquals(setOf("wan", "pppoe-wan"), wanDevices(router.copy(l3Devices = mapOf("wan" to "pppoe-wan"))))
    }

    @Test
    fun blocked_offline_device_is_listed() {
        val blocked = router.copy(firewall = applyBlock(router.firewall, "02:00:00:aa:00:0e"))
        val client = mergeClients(readings(), blocked).single()
        assertEquals(ClientLink.OFFLINE, client.link)
        assertTrue(client.blocked)
        assertTrue(actionsFor(client, blocked).block)
    }

    @Test
    fun sort_wifi_by_signal_then_wired_by_name() {
        val clients = mergeClients(
            readings(
                leases = listOf(Lease("02:00:00:AA:00:01", "192.168.1.10", "zed", 0), Lease("02:00:00:AA:00:02", "192.168.1.11", "Amy", 0)),
                associations = listOf(Association("02:00:00:AA:00:03", "phy0-ap0", -70, 1), Association("02:00:00:AA:00:04", "phy0-ap0", -40, 1)),
                neighbours = listOf(Neighbour("192.168.1.10", "br-lan.1", "02:00:00:AA:00:01"), Neighbour("192.168.1.11", "br-lan.1", "02:00:00:AA:00:02")),
            ),
            router,
        )
        assertEquals(listOf("02:00:00:AA:00:04", "02:00:00:AA:00:03", "02:00:00:AA:00:02", "02:00:00:AA:00:01"), clients.map { it.mac })
    }

    @Test
    fun reserve_needs_an_active_pool_for_the_client_subnet() {
        val home = Client(pc, null, "192.168.1.114", ClientLink.WIRED, null, null, null, null, null, false, false)
        assertTrue(actionsFor(home, router).reserve)
        assertFalse("outside every pool", actionsFor(home.copy(ip = "10.9.9.9"), router).reserve)
        assertFalse("no address known", actionsFor(home.copy(ip = null), router).reserve)
        val ignored = router.copy(dhcp = UciConfig("dhcp", router.dhcp.sections.map { if (it.name == "lan") it.copy(options = it.options + ("ignore" to "1")) else it }))
        assertFalse(actionsFor(home, ignored).reserve)
        val noServer = router.copy(dhcp = UciConfig("dhcp", router.dhcp.sections.map { if (it.name == "lan") it.copy(options = it.options - "dhcpv4") else it }))
        assertFalse("25.x needs dhcpv4 server", actionsFor(home, noServer).reserve)
        assertTrue("older releases do not", actionsFor(home, noServer.copy(dhcpv4Required = false)).reserve)
    }

    @Test
    fun block_needs_a_wan_zone_and_the_firewall() {
        val home = Client(pc, null, "192.168.1.114", ClientLink.WIRED, null, null, null, null, null, false, false)
        assertTrue(actionsFor(home, router).block)
        assertFalse(actionsFor(home, router.copy(firewallEnabled = false)).block)
    }

    @Test
    fun main_router_with_wan_down_keeps_both() {
        val home = Client(pc, null, "192.168.1.114", ClientLink.WIRED, null, null, null, null, null, false, false)
        val down = router.copy(l3Devices = emptyMap())
        assertEquals(ClientActions(reconnect = false, reserve = true, block = true), actionsFor(home, down))
    }

    @Test
    fun access_point_offers_only_reconnect() {
        val station = Client(phone, null, "192.168.0.50", ClientLink.WIFI, "Home", Band.GHZ_5, "phy1-ap0", -50, 10, false, false)
        assertEquals(ClientActions(reconnect = true, reserve = false, block = false), actionsFor(station, accessPoint))
    }

    @Test
    fun old_style_zone_network_string_is_split() {
        val oldStyle = router.copy(
            firewall = UciConfig(
                "firewall",
                router.firewall.sections.map {
                    if (it.type == "zone" && it.opt("name") == "wan") it.copy(options = it.options + ("network" to "wan wan6"), lists = it.lists - "network") else it
                },
            ),
        )
        val upstream = readings(neighbours = listOf(Neighbour("192.168.0.1", "wan", "8C:90:2D:AA:00:20")))
        assertTrue(mergeClients(upstream, oldStyle).isEmpty())
        val home = Client(pc, null, "192.168.1.114", ClientLink.WIRED, null, null, null, null, null, false, false)
        assertTrue(actionsFor(home, oldStyle).block)
    }

    @Test
    fun access_point_that_kept_its_wan_interface_offers_no_block() {
        val keptWan = accessPoint.copy(
            network = UciConfig(
                "network",
                accessPoint.network.sections + UciSection("wan", "interface", false, 80, mapOf("device" to "wan", "proto" to "dhcp"), emptyMap()),
            ),
        )
        val station = Client(phone, null, "192.168.0.50", ClientLink.WIFI, "Home", Band.GHZ_5, "phy1-ap0", -50, 10, false, false)
        assertFalse("its lan has a gateway: traffic is bridged, not routed here", actionsFor(station, keptWan).block)
        val offline = station.copy(ip = null, link = ClientLink.OFFLINE, blocked = true)
        assertTrue("an existing block can still be lifted", actionsFor(offline, router).block)
    }

    @Test
    fun wired_client_cannot_reconnect() {
        val home = Client(pc, null, "192.168.1.114", ClientLink.WIRED, null, null, null, null, null, false, false)
        assertFalse(actionsFor(home, router).reconnect)
    }

    /** The firewall config as it would look after a block was applied. */
    private fun applyBlock(firewall: UciConfig, mac: String) = UciConfig(
        "firewall",
        firewall.sections + UciSection(BlockPlanner.SET, "ipset", false, 90, mapOf("name" to BlockPlanner.SET), mapOf("entry" to listOf(mac))),
    )
}
