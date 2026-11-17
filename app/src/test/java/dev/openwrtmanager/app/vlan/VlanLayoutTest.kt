package dev.openwrtmanager.app.vlan

import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import dev.openwrtmanager.app.uci.parseUciConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlanLayoutTest {
    private fun load(file: String) = parseUciConfig("network", Fixtures.read(file))

    @Test
    fun plain_router_has_a_bridge_and_no_vlans() {
        val layout = vlanLayoutOf(load("uci_network.json"))!!
        assertEquals("br-lan", layout.bridge)
        assertEquals("cfg030f15", layout.bridgeSection)
        assertEquals(listOf("lan1", "lan2", "lan3", "lan4"), layout.ports)
        assertFalse(layout.filtering)
        assertEquals(emptyMap<Int, String>(), layout.interfaces)
    }

    @Test
    fun vlans_and_their_interfaces_are_read() {
        val layout = vlanLayoutOf(load("uci_network_vlans.json"))!!
        assertTrue(layout.filtering)
        assertEquals(listOf(1, 10), layout.vlans.map { it.vid })
        assertEquals("vlan_guest", layout.vlan(10)!!.section)
        assertEquals(PortRole.TAGGED, layout.vlan(10)!!.roles["lan4"])
        assertEquals(PortRole.NONE, layout.vlan(10)!!.roles["lan1"])
        assertEquals(PortRole.UNTAGGED, layout.vlan(1)!!.roles["lan4"])
        assertEquals(mapOf(1 to "lan", 10 to "guest"), layout.interfaces)
        assertEquals(1, layout.untaggedIn("lan1"))
        assertNull(layout.vlan(20))
    }

    @Test
    fun luci_made_layout_is_read() {
        val layout = vlanLayoutOf(load("uci_network_luci_vlans.json"))!!
        assertEquals("br-lan", layout.bridge)
        assertEquals("cfg010f15", layout.bridgeSection)
        assertEquals("another bridge's VLAN is not ours", listOf(1, 3), layout.vlans.map { it.vid })
        assertEquals("cfg020f15", layout.vlan(1)!!.section)
        assertEquals(PortRole.TAGGED, layout.vlan(1)!!.roles["lan2"])
        assertEquals("a single port stored as a plain option", PortRole.UNTAGGED, layout.vlan(3)!!.roles["lan3"])
        assertEquals(3, layout.untaggedIn("lan3"))
        assertNull(layout.untaggedIn("lan2"))
        assertEquals(mapOf(1 to "lan", 3 to "cams"), layout.interfaces)
    }

    @Test
    fun port_tokens() {
        assertEquals("lan1" to PortRole.UNTAGGED, parsePortRole("lan1:u*"))
        assertEquals("lan1" to PortRole.UNTAGGED, parsePortRole("lan1:u"))
        assertEquals("lan1" to PortRole.UNTAGGED, parsePortRole("lan1:*u"))
        assertEquals("lan1" to PortRole.TAGGED, parsePortRole("lan1:t"))
        assertEquals("lan1" to PortRole.TAGGED, parsePortRole("lan1:t*"))
        assertEquals("lan1" to PortRole.TAGGED, parsePortRole("lan1"))
    }

    @Test
    fun access_point_with_wan_in_the_bridge() {
        val layout = vlanLayoutOf(load("uci_network_ap.json"))!!
        assertTrue("wan" in layout.ports)
        assertFalse(layout.filtering)
    }

    @Test
    fun home_vlan_comes_from_lan_even_when_another_interface_shares_it() {
        val base = load("uci_network_luci_vlans.json")
        val network = base.copy(
            sections = base.sections.map { if (it.name == "lan") it.copy(options = it.options + ("device" to "br-lan.3")) else it } +
                UciSection("alias", "interface", false, 99, mapOf("device" to "br-lan.3", "proto" to "static"), emptyMap()),
        )
        val layout = vlanLayoutOf(network)!!
        assertEquals(3, layout.homeVid)
        assertEquals("lan", layout.interfaces[3])
        assertEquals(3, VlanPlanner.lanVid(layout))
    }

    @Test
    fun swconfig_router_is_not_handled() {
        fun device(ports: List<String>) = UciSection("br", "device", false, 0, mapOf("name" to "br-lan", "type" to "bridge"), mapOf("ports" to ports))
        val lan = UciSection("lan", "interface", false, 1, mapOf("device" to "br-lan"), emptyMap())
        assertNull("VLAN subinterfaces as ports", vlanLayoutOf(UciConfig("network", listOf(device(listOf("eth0.1")), lan))))
        val switch = UciSection("sw", "switch", false, 2, mapOf("name" to "switch0"), emptyMap())
        assertNull("a switch section", vlanLayoutOf(UciConfig("network", listOf(device(listOf("lan1")), lan, switch))))
    }

    @Test
    fun no_bridge_is_null() {
        val network = UciConfig(
            "network",
            listOf(UciSection("lan", "interface", false, 0, mapOf("device" to "eth0", "proto" to "static"), emptyMap())),
        )
        assertNull(vlanLayoutOf(network))
        assertNull("no lan interface at all", vlanLayoutOf(UciConfig("network", emptyList())))
    }
}
