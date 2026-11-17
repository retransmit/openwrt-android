package dev.openwrtmanager.app.wifi

import dev.openwrtmanager.app.router.RouterClient
import dev.openwrtmanager.app.testing.CMD_IFACES
import dev.openwrtmanager.app.testing.FakeSshExecutor
import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import dev.openwrtmanager.app.uci.parseUciConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubnetPickerTest {
    @Test
    fun first_free_subnet_starts_at_ten() {
        assertEquals("192.168.10.0/24", pickSubnet(listOf("192.168.1.0/24", "192.168.0.0/24", "127.0.0.0/8")))
    }

    @Test
    fun exact_matches_are_skipped() {
        assertEquals("192.168.12.0/24", pickSubnet(listOf("192.168.10.0/24", "192.168.11.0/24")))
    }

    @Test
    fun wider_ranges_that_cover_a_candidate_are_skipped() {
        // 192.168.8.0/21 covers 192.168.8.0 to 192.168.15.255.
        assertEquals("192.168.16.0/24", pickSubnet(listOf("192.168.8.0/21")))
    }

    @Test
    fun narrower_ranges_inside_a_candidate_are_skipped() {
        assertEquals("192.168.11.0/24", pickSubnet(listOf("192.168.10.128/25")))
    }

    @Test
    fun unrelated_ranges_do_not_matter() {
        assertEquals("192.168.10.0/24", pickSubnet(listOf("100.64.0.0/10", "10.0.0.0/8", "172.16.0.0/12", "not-an-address")))
    }

    @Test
    fun nothing_free_is_null() {
        assertNull(pickSubnet(listOf("192.168.0.0/16")))
    }

    @Test
    fun used_subnets_come_from_live_addresses() = runTest {
        val fake = FakeSshExecutor().apply { respond(CMD_IFACES, Fixtures.read("iface_dump_addresses.json")) }
        assertEquals(
            listOf("192.168.1.0/24", "127.0.0.0/8", "192.168.0.0/24"),
            RouterClient(fake).usedSubnets(),
        )
    }
}

class NetworkWizardTest {
    private fun load(name: String, file: String) = parseUciConfig(name, Fixtures.read(file))

    private val router = DeviceSnapshot(
        wireless = load("wireless", "uci_wireless.json"),
        network = load("network", "uci_network.json"),
        firewall = load("firewall", "uci_firewall.json"),
        dhcp = load("dhcp", "uci_dhcp.json"),
        usedSubnets = listOf("192.168.1.0/24", "127.0.0.0/8", "192.168.0.0/24"),
        hasUplink = true,
    )
    private val accessPoint = DeviceSnapshot(
        wireless = load("wireless", "uci_wireless_ap.json"),
        network = load("network", "uci_network_ap.json"),
        firewall = load("firewall", "uci_firewall_ap.json"),
        dhcp = load("dhcp", "uci_dhcp_ap.json"),
        usedSubnets = listOf("192.168.0.0/24"),
        hasUplink = false,
    )
    private val guest = WizardInput(WizardKind.GUEST, "Home-Guest", Security.WPA2_WPA3, "guest pass 1", setOf("radio0", "radio1"))
    private val iot = WizardInput(WizardKind.IOT, "Home-IoT", Security.WPA2, "iot pass 12", setOf("radio0"))

    private fun ready(snapshot: DeviceSnapshot, input: WizardInput) = NetworkWizard.plan(snapshot, input) as WizardPlan.Ready

    private val expectedGuestScript = listOf(
        "set network.br_guest=device",
        "set network.br_guest.type='bridge'",
        "set network.br_guest.name='br-guest'",
        "set network.br_guest.bridge_empty='1'",
        "set network.guest=interface",
        "set network.guest.proto='static'",
        "set network.guest.device='br-guest'",
        "set network.guest.ipaddr='192.168.10.1'",
        "set network.guest.netmask='255.255.255.0'",
        "set dhcp.guest=dhcp",
        "set dhcp.guest.interface='guest'",
        "set dhcp.guest.start='100'",
        "set dhcp.guest.limit='150'",
        "set dhcp.guest.leasetime='1h'",
        "set dhcp.guest.dhcpv4='server'",
        "set firewall.guest=zone",
        "set firewall.guest.name='guest'",
        "add_list firewall.guest.network='guest'",
        "set firewall.guest.input='REJECT'",
        "set firewall.guest.output='ACCEPT'",
        "set firewall.guest.forward='REJECT'",
        "set firewall.guest_wan=forwarding",
        "set firewall.guest_wan.src='guest'",
        "set firewall.guest_wan.dest='wan'",
        "set firewall.guest_dns=rule",
        "set firewall.guest_dns.name='Allow-DNS-guest'",
        "set firewall.guest_dns.src='guest'",
        "set firewall.guest_dns.dest_port='53'",
        "set firewall.guest_dns.proto='tcp udp'",
        "set firewall.guest_dns.target='ACCEPT'",
        "set firewall.guest_dhcp=rule",
        "set firewall.guest_dhcp.name='Allow-DHCP-guest'",
        "set firewall.guest_dhcp.src='guest'",
        "set firewall.guest_dhcp.dest_port='67'",
        "set firewall.guest_dhcp.proto='udp'",
        "set firewall.guest_dhcp.target='ACCEPT'",
        "set wireless.guest_radio0=wifi-iface",
        "set wireless.guest_radio0.device='radio0'",
        "set wireless.guest_radio0.mode='ap'",
        "set wireless.guest_radio0.network='guest'",
        "set wireless.guest_radio0.ssid='Home-Guest'",
        "set wireless.guest_radio0.encryption='sae-mixed'",
        "set wireless.guest_radio0.key='guest pass 1'",
        "set wireless.guest_radio0.ieee80211w='1'",
        "set wireless.guest_radio0.isolate='1'",
        "set wireless.guest_radio1=wifi-iface",
        "set wireless.guest_radio1.device='radio1'",
        "set wireless.guest_radio1.mode='ap'",
        "set wireless.guest_radio1.network='guest'",
        "set wireless.guest_radio1.ssid='Home-Guest'",
        "set wireless.guest_radio1.encryption='sae-mixed'",
        "set wireless.guest_radio1.key='guest pass 1'",
        "set wireless.guest_radio1.ieee80211w='1'",
        "set wireless.guest_radio1.isolate='1'",
    )

    @Test
    fun guest_plan_writes_exactly_the_expected_config() {
        val plan = ready(router, guest)
        assertEquals("guest", plan.networkName)
        assertEquals("192.168.10.0/24", plan.subnet)
        assertEquals(expectedGuestScript, plan.change.batch.script().lines())
        assertEquals(setOf("network", "dhcp", "firewall", "wireless"), plan.change.batch.configs)
        assertTrue(plan.change.mayDropConnection)
    }

    @Test
    fun summary_is_plain_and_never_shows_the_password() {
        val summary = ready(router, guest).change.summary
        assertEquals(
            listOf(
                "Create Wi-Fi network \"Home-Guest\" on 2.4 GHz and 5 GHz",
                "Give it its own address range, 192.168.10.0/24",
                "Devices on it can reach the internet, but not your home network or this router's settings",
                "Devices on the same band cannot see each other",
            ),
            summary,
        )
        assertFalse(summary.joinToString().contains("guest pass 1"))
    }

    @Test
    fun iot_plan_uses_long_leases_and_lets_home_devices_in() {
        val plan = ready(router, iot)
        val script = plan.change.batch.script()
        assertEquals("iot", plan.networkName)
        assertTrue("set dhcp.iot.leasetime='12h'" in script)
        assertTrue("set firewall.lan_iot=forwarding" in script)
        assertTrue("set firewall.lan_iot.src='lan'" in script)
        assertTrue("set firewall.lan_iot.dest='iot'" in script)
        assertTrue("set wireless.iot_radio0.encryption='psk2'" in script)
        assertFalse("iot_radio1" in script)
        assertTrue("Your home devices can reach devices on it" in plan.change.summary)
    }

    @Test
    fun iot_can_be_fully_closed_off() {
        val plan = ready(router, iot.copy(lanCanReachIot = false))
        assertFalse("lan_iot" in plan.change.batch.script())
        assertTrue("Your home devices cannot reach devices on it" in plan.change.summary)
    }

    @Test
    fun nothing_ever_forwards_from_the_new_network_into_lan() {
        for (input in listOf(guest, iot, iot.copy(lanCanReachIot = false))) {
            val lines = ready(router, input).change.batch.script().lines()
            assertTrue(lines.none { it.endsWith(".dest='lan'") })
        }
    }

    @Test
    fun open_guest_network_has_no_key() {
        val script = ready(router, guest.copy(security = Security.OPEN, key = null)).change.batch.script()
        assertTrue("set wireless.guest_radio0.encryption='none'" in script)
        assertFalse(".key=" in script)
    }

    @Test
    fun forwarding_targets_whichever_zone_holds_wan() {
        val renamed = router.copy(
            firewall = router.firewall.copy(
                sections = router.firewall.sections.map {
                    if (it.type == "zone" && it.opt("name") == "wan") it.copy(options = it.options + ("name" to "internet")) else it
                },
            ),
        )
        assertTrue("set firewall.guest_wan.dest='internet'" in ready(renamed, guest).change.batch.script())
    }

    @Test
    fun second_network_of_a_kind_gets_a_numbered_name() {
        val existing = UciSection("guest", "interface", false, 99, mapOf("proto" to "static"), emptyMap())
        val taken = router.copy(
            network = router.network.copy(sections = router.network.sections + existing),
            usedSubnets = router.usedSubnets + "192.168.10.0/24",
        )
        val plan = ready(taken, guest)
        assertEquals("guest2", plan.networkName)
        assertEquals("192.168.11.0/24", plan.subnet)
        val script = plan.change.batch.script()
        assertTrue("set network.br_guest2.name='br-guest2'" in script)
        assertTrue("set wireless.guest2_radio0.network='guest2'" in script)
    }

    @Test
    fun access_point_is_blocked_with_no_uplink() {
        assertEquals(WizardPlan.Blocked(BlockReason.NO_UPLINK), NetworkWizard.plan(accessPoint, guest))
    }

    @Test
    fun missing_wan_zone_is_blocked() {
        val noWan = router.copy(
            firewall = UciConfig("firewall", router.firewall.sections.filterNot { it.type == "zone" && it.opt("name") == "wan" }),
        )
        assertEquals(WizardPlan.Blocked(BlockReason.NO_WAN_ZONE), NetworkWizard.plan(noWan, guest))
    }

    @Test
    fun no_radio_and_no_subnet_are_blocked() {
        assertEquals(WizardPlan.Blocked(BlockReason.NO_RADIO), NetworkWizard.plan(router, guest.copy(radios = emptySet())))
        assertEquals(WizardPlan.Blocked(BlockReason.NO_RADIO), NetworkWizard.plan(router, guest.copy(radios = setOf("radio9"))))
        assertEquals(
            WizardPlan.Blocked(BlockReason.NO_FREE_SUBNET),
            NetworkWizard.plan(router.copy(usedSubnets = listOf("192.168.0.0/16")), guest),
        )
    }

    /** The snapshot as it would look after the guest plan was applied. */
    private fun afterGuest(): DeviceSnapshot {
        fun section(name: String, type: String, options: Map<String, String>, lists: Map<String, List<String>> = emptyMap(), index: Int) =
            UciSection(name, type, false, index, options, lists)
        return router.copy(
            network = router.network.copy(
                sections = router.network.sections +
                    section("br_guest", "device", mapOf("type" to "bridge", "name" to "br-guest"), index = 50) +
                    section("guest", "interface", mapOf("proto" to "static", "device" to "br-guest"), index = 51),
            ),
            dhcp = router.dhcp.copy(sections = router.dhcp.sections + section("guest", "dhcp", mapOf("interface" to "guest"), index = 50)),
            firewall = router.firewall.copy(
                sections = router.firewall.sections +
                    section("guest", "zone", mapOf("name" to "guest"), mapOf("network" to listOf("guest")), 50) +
                    section("guest_wan", "forwarding", mapOf("src" to "guest", "dest" to "wan"), index = 51) +
                    section("guest_dns", "rule", mapOf("src" to "guest", "dest_port" to "53"), index = 52) +
                    section("guest_dhcp", "rule", mapOf("src" to "guest", "dest_port" to "67"), index = 53),
            ),
            wireless = router.wireless.copy(
                sections = router.wireless.sections +
                    section("guest_radio0", "wifi-iface", mapOf("device" to "radio0", "mode" to "ap", "network" to "guest"), index = 50) +
                    section("guest_radio1", "wifi-iface", mapOf("device" to "radio1", "mode" to "ap", "network" to "guest"), index = 51),
            ),
        )
    }

    @Test
    fun remove_deletes_everything_the_wizard_created_and_nothing_else() {
        val change = NetworkWizard.remove(afterGuest(), "guest")
        assertEquals(
            setOf(
                "delete wireless.guest_radio1", "delete wireless.guest_radio0",
                "delete dhcp.guest",
                "delete firewall.guest_dhcp", "delete firewall.guest_dns", "delete firewall.guest_wan", "delete firewall.guest",
                "delete network.guest", "delete network.br_guest",
            ),
            change.batch.script().lines().toSet(),
        )
        assertEquals(9, change.batch.script().lines().size)
    }

    @Test
    fun remove_deletes_later_sections_first_so_earlier_ones_keep_their_ids() {
        val lines = NetworkWizard.remove(afterGuest(), "guest").batch.script().lines()
        assertTrue(lines.indexOf("delete firewall.guest_dhcp") < lines.indexOf("delete firewall.guest"))
        assertTrue(lines.indexOf("delete wireless.guest_radio1") < lines.indexOf("delete wireless.guest_radio0"))
    }

    @Test
    fun built_in_networks_cannot_be_removed() {
        for (name in listOf("lan", "wan", "wan6", "loopback")) {
            assertThrows(IllegalArgumentException::class.java) { NetworkWizard.remove(router, name) }
        }
    }

    @Test
    fun the_lan_bridge_is_never_deleted() {
        val odd = afterGuest().let { s ->
            s.copy(
                network = s.network.copy(
                    sections = s.network.sections.map {
                        if (it.name == "guest") it.copy(options = it.options + ("device" to "br-lan")) else it
                    },
                ),
            )
        }
        val script = NetworkWizard.remove(odd, "guest").batch.script()
        assertFalse("br_guest" in script)
        assertFalse(router.network.ofType("device").first().name in script)
    }

    @Test
    fun isolation_claim_matches_what_one_radio_can_promise() {
        assertTrue("Devices on it cannot see each other" in ready(router, iot).change.summary)
        assertTrue("Devices on the same band cannot see each other" in ready(router, guest).change.summary)
    }

    /** A hand-made network that shares the lan zone: list network 'lan' 'office'. */
    private fun withOfficeInLanZone(): DeviceSnapshot {
        val office = UciSection("office", "interface", false, 60, mapOf("proto" to "static", "device" to "br-office"), emptyMap())
        val bridge = UciSection("br_office", "device", false, 61, mapOf("type" to "bridge", "name" to "br-office"), emptyMap())
        val iface = UciSection(
            "office_radio0", "wifi-iface", false, 60,
            mapOf("device" to "radio0", "mode" to "ap", "network" to "office", "ssid" to "Office"), emptyMap(),
        )
        return router.copy(
            network = router.network.copy(sections = router.network.sections + office + bridge),
            wireless = router.wireless.copy(sections = router.wireless.sections + iface),
            firewall = router.firewall.copy(
                sections = router.firewall.sections.map {
                    if (it.type == "zone" && it.opt("name") == "lan") it.copy(lists = it.lists + ("network" to listOf("lan", "office"))) else it
                },
            ),
        )
    }

    @Test
    fun removing_a_network_never_deletes_a_zone_it_shares() {
        val snapshot = withOfficeInLanZone()
        val lanZone = snapshot.firewall.ofType("zone").first { it.opt("name") == "lan" }
        val lanToWan = snapshot.firewall.ofType("forwarding").first { it.opt("src") == "lan" }
        val lines = NetworkWizard.remove(snapshot, "office").batch.script().lines()
        assertTrue("del_list firewall.${lanZone.name}.network='office'" in lines)
        assertFalse("delete firewall.${lanZone.name}" in lines)
        assertFalse("delete firewall.${lanToWan.name}" in lines)
        assertTrue("delete network.office" in lines)
        assertTrue("delete wireless.office_radio0" in lines)
    }

    @Test
    fun configured_addresses_count_as_used_even_when_the_interface_is_down() {
        fun s(name: String, type: String, o: Map<String, String>, l: Map<String, List<String>> = emptyMap()) = UciSection(name, type, false, 70, o, l)
        val network = router.network.copy(
            sections = router.network.sections +
                s("vpn", "interface", mapOf("proto" to "static", "ipaddr" to "192.168.10.1", "netmask" to "255.255.255.0")) +
                s("lab", "interface", mapOf("proto" to "static"), mapOf("ipaddr" to listOf("192.168.11.1/24"))) +
                s("r1", "route", mapOf("interface" to "wan", "target" to "192.168.12.0", "netmask" to "255.255.255.0")) +
                s("r2", "route", mapOf("interface" to "wan", "target" to "192.168.13.0/24")),
        )
        val configured = configuredSubnets(network)
        for (expected in listOf("192.168.10.0/24", "192.168.11.0/24", "192.168.12.0/24", "192.168.13.0/24")) {
            assertTrue(expected, expected in configured)
        }
        assertEquals("192.168.14.0/24", pickSubnet(configured))
    }
}
