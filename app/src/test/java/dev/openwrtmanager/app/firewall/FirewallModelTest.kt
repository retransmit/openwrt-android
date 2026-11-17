package dev.openwrtmanager.app.firewall

import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import dev.openwrtmanager.app.uci.parseUciConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirewallModelTest {
    private val firewall = parseUciConfig("firewall", Fixtures.read("uci_firewall_full.json"))

    private fun section(name: String, type: String, options: Map<String, String>, lists: Map<String, List<String>> = emptyMap(), index: Int = 50) =
        UciSection(name, type, true, index, options, lists)

    @Test
    fun zones_with_policies_and_forwardings() {
        val view = firewallViewOf(firewall)
        val lan = view.zones.first { it.name == "lan" }
        assertEquals(listOf("lan"), lan.networks)
        assertEquals("ACCEPT", lan.input)
        assertEquals(listOf("wan"), lan.forwardsTo)
        val wan = view.zones.first { it.name == "wan" }
        assertEquals(listOf("wan", "wan6"), wan.networks)
        assertEquals("REJECT", wan.input)
        assertTrue(wan.masq)
        assertEquals(emptyList<String>(), wan.forwardsTo)
        assertEquals("wan", view.wanZone)
        assertTrue(view.zones.any { it.name == "tailscale" })
    }

    @Test
    fun space_separated_networks_are_split() {
        val old = UciConfig("firewall", listOf(section("z", "zone", mapOf("name" to "wan", "network" to "wan wan6"))))
        assertEquals(listOf("wan", "wan6"), firewallViewOf(old).zones.single().networks)
        assertEquals("wan", firewallViewOf(old).wanZone)
    }

    @Test
    fun luci_style_sections_are_read() {
        val luci = UciConfig(
            "firewall",
            firewall.sections +
                section(
                    "cfg0a1b2c", "redirect",
                    mapOf("name" to "Plex", "src" to "wan", "src_dport" to "32400", "dest" to "lan", "dest_ip" to "192.168.1.20", "target" to "DNAT", "enabled" to "0"),
                    mapOf("proto" to listOf("tcp", "udp")),
                ) +
                section("cfg0a1b2d", "rule", mapOf("name" to "Old", "src" to "wan", "dest_port" to "22", "proto" to "tcp", "target" to "ACCEPT", "enabled" to "0")),
        )
        val view = firewallViewOf(luci)
        assertEquals(
            PortForward("cfg0a1b2c", "Plex", setOf("tcp", "udp"), "32400", "192.168.1.20", null, "lan", enabled = false),
            view.forwards.single(),
        )
        assertFalse(view.rules.first { it.section == "cfg0a1b2d" }.enabled)
    }

    @Test
    fun rules_are_described_in_words() {
        val rules = firewallViewOf(firewall).rules.associate { it.name to it.description }
        assertEquals("Allows ping from wan to the router", rules["Allow-Ping"])
        assertEquals("Allows UDP port 68 from wan to the router", rules["Allow-DHCP-Renew"])
        assertEquals("Allows ESP from wan to lan", rules["Allow-IPSec-ESP"])
        assertEquals("Allows UDP port 500 from wan to lan", rules["Allow-ISAKMP"])
        assertEquals("Allows ICMP from wan to any network", rules["Allow-ICMPv6-Forward"])
        val block = section("x", "rule", mapOf("src" to "guest", "dest" to "lan", "target" to "REJECT"))
        assertEquals("Blocks traffic from guest to lan", describeRule(block))
        val drop = section("y", "rule", mapOf("src" to "*", "dest_port" to "80 443", "proto" to "tcp", "target" to "DROP"))
        assertEquals("Drops TCP ports 80, 443 from any network to the router", describeRule(drop))
    }

    @Test
    fun standard_rules_are_marked() {
        val view = firewallViewOf(
            UciConfig("firewall", firewall.sections + section("mine", "rule", mapOf("name" to "Game server", "src" to "wan", "target" to "ACCEPT"))),
        )
        assertTrue(view.rules.first { it.name == "Allow-Ping" }.standard)
        assertFalse(view.rules.first { it.name == "Game server" }.standard)
    }

    @Test
    fun private_wan_detection() {
        for (yes in listOf("192.168.0.178", "10.1.2.3", "172.16.0.1", "172.31.255.1", "100.64.0.1", "100.127.0.1")) assertTrue(yes, isPrivateIpv4(yes))
        for (no in listOf("172.15.0.1", "172.32.0.1", "100.63.0.1", "100.128.0.1", "8.8.8.8", "not an ip")) assertFalse(no, isPrivateIpv4(no))
    }
}
