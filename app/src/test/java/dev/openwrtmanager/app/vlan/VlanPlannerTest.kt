package dev.openwrtmanager.app.vlan

import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.uci.UciBatch
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import dev.openwrtmanager.app.uci.parseUciConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VlanPlannerTest {
    private val network = parseUciConfig("network", Fixtures.read("uci_network.json"))
    private val vlanNetwork = parseUciConfig("network", Fixtures.read("uci_network_vlans.json"))
    private val plain = vlanLayoutOf(network)!!
    private val vlans = vlanLayoutOf(vlanNetwork)!!

    @Test
    fun enabling_filtering_writes_vlan_one_with_every_port_untagged() {
        val batch = UciBatch()
        val after = VlanPlanner.enableFiltering(plain, network, batch)
        assertEquals(
            listOf(
                "set network.vlan_lan=bridge-vlan",
                "set network.vlan_lan.device='br-lan'",
                "set network.vlan_lan.vlan='1'",
                "add_list network.vlan_lan.ports='lan1:u*'",
                "add_list network.vlan_lan.ports='lan2:u*'",
                "add_list network.vlan_lan.ports='lan3:u*'",
                "add_list network.vlan_lan.ports='lan4:u*'",
                "set network.lan.device='br-lan.1'",
            ),
            batch.script().lines(),
        )
        assertTrue(after.filtering)
        assertEquals(1, after.untaggedIn("lan3"))
        assertEquals(mapOf(1 to "lan"), after.interfaces)
    }

    @Test
    fun every_interface_on_the_bridge_moves_to_vlan_one() {
        val alias = UciSection("alias", "interface", false, 99, mapOf("device" to "br-lan", "proto" to "static"), emptyMap())
        val withAlias = UciConfig("network", network.sections + alias)
        val batch = UciBatch()
        VlanPlanner.enableFiltering(vlanLayoutOf(withAlias)!!, withAlias, batch)
        val lines = batch.script().lines()
        assertTrue("set network.lan.device='br-lan.1'" in lines)
        assertTrue("set network.alias.device='br-lan.1'" in lines)
        assertFalse("wan is not on the bridge", lines.any { it.startsWith("set network.wan.") })
    }

    @Test
    fun already_filtering_is_not_converted_again() {
        val batch = UciBatch()
        assertEquals(vlans, VlanPlanner.enableFiltering(vlans, vlanNetwork, batch))
        assertTrue(batch.isEmpty())
    }

    @Test
    fun adding_a_vlan_writes_explicit_port_roles() {
        val batch = UciBatch()
        val after = VlanPlanner.addVlan(vlans, batch, 20, "iot", mapOf("lan4" to PortRole.TAGGED))
        assertEquals(
            listOf(
                "set network.vlan_iot=bridge-vlan",
                "set network.vlan_iot.device='br-lan'",
                "set network.vlan_iot.vlan='20'",
                "add_list network.vlan_iot.ports='lan4:t'",
            ),
            batch.script().lines(),
        )
        assertEquals(listOf(1, 10, 20), after.vlans.map { it.vid })
    }

    @Test
    fun wifi_only_vlan_has_no_ports() {
        val batch = UciBatch()
        val after = VlanPlanner.addVlan(vlans, batch, 20, "iot", emptyMap())
        assertFalse(batch.script().contains("ports"))
        assertTrue(after.vlan(20)!!.roles.values.all { it == PortRole.NONE })
    }

    @Test
    fun untagged_elsewhere_moves_the_port() {
        val batch = UciBatch()
        val after = VlanPlanner.setRoles(vlans, batch, 10, mapOf("lan3" to PortRole.UNTAGGED))
        assertEquals(PortRole.NONE, after.vlan(1)!!.roles["lan3"])
        assertEquals(PortRole.UNTAGGED, after.vlan(10)!!.roles["lan3"])
        assertEquals("lan4 is untouched", PortRole.TAGGED, after.vlan(10)!!.roles["lan4"])
        assertEquals(
            listOf(
                "delete network.vlan_lan.ports",
                "add_list network.vlan_lan.ports='lan1:u*'",
                "add_list network.vlan_lan.ports='lan2:u*'",
                "add_list network.vlan_lan.ports='lan4:u*'",
                "delete network.vlan_guest.ports",
                "add_list network.vlan_guest.ports='lan3:u*'",
                "add_list network.vlan_guest.ports='lan4:t'",
            ),
            batch.script().lines(),
        )
        assertEquals(emptyList<VlanProblem>(), VlanPlanner.validate(after, lanHasWifi = false))
    }

    @Test
    fun tagged_link_keeps_the_home_network() {
        val batch = UciBatch()
        val withIot = VlanPlanner.addVlan(vlans, batch, 20, "iot", mapOf("lan2" to PortRole.TAGGED))
        val after = VlanPlanner.setRoles(withIot, batch, 10, mapOf("lan2" to PortRole.TAGGED))
        assertEquals(PortRole.UNTAGGED, after.vlan(1)!!.roles["lan2"])
        assertEquals(PortRole.TAGGED, after.vlan(10)!!.roles["lan2"])
        assertEquals(PortRole.TAGGED, after.vlan(20)!!.roles["lan2"])
        assertFalse("VLAN 1 is not rewritten", batch.script().contains("vlan_lan"))
    }

    @Test
    fun unchanged_roles_write_nothing() {
        val batch = UciBatch()
        assertEquals(vlans, VlanPlanner.setRoles(vlans, batch, 10, mapOf("lan4" to PortRole.TAGGED)))
        assertTrue(batch.isEmpty())
    }

    @Test
    fun lan_with_no_port_and_no_wifi_is_refused() {
        var layout = vlans
        for (port in vlans.ports) layout = VlanPlanner.setRoles(layout, UciBatch(), 10, mapOf(port to PortRole.UNTAGGED))
        assertEquals(listOf(VlanProblem.LanUnreachable), VlanPlanner.validate(layout, lanHasWifi = false))
        assertEquals(emptyList<VlanProblem>(), VlanPlanner.validate(layout, lanHasWifi = true))
        assertEquals("a filtering-off bridge is always reachable", emptyList<VlanProblem>(), VlanPlanner.validate(plain, false))
    }

    @Test
    fun a_port_untagged_twice_is_a_problem() {
        val broken = vlans.copy(
            vlans = vlans.vlans.map { if (it.vid == 10) it.copy(roles = it.roles + ("lan1" to PortRole.UNTAGGED)) else it },
        )
        assertEquals(listOf(VlanProblem.TwoUntagged("lan1")), VlanPlanner.validate(broken, lanHasWifi = true))
    }

    @Test
    fun ids_are_checked() {
        assertEquals(VlanProblem.BadId(0), VlanPlanner.checkNewId(vlans, 0))
        assertEquals(VlanProblem.BadId(4095), VlanPlanner.checkNewId(vlans, 4095))
        assertEquals(VlanProblem.IdTaken(10), VlanPlanner.checkNewId(vlans, 10))
        assertNull(VlanPlanner.checkNewId(vlans, 4094))
        assertThrows(IllegalArgumentException::class.java) { VlanPlanner.addVlan(vlans, UciBatch(), 10, "again", emptyMap()) }
    }

    @Test
    fun next_free_vid() {
        assertEquals(10, VlanPlanner.nextFreeVid(plain, 10))
        assertEquals(20, VlanPlanner.nextFreeVid(vlans, 10))
        assertEquals(20, VlanPlanner.nextFreeVid(vlans, 20))
        val crowded = VlanPlanner.addVlan(vlans, UciBatch(), 20, "iot", emptyMap())
        assertEquals(30, VlanPlanner.nextFreeVid(crowded, 10))
    }

    @Test
    fun lan_vlan_cannot_be_removed() {
        assertThrows(IllegalArgumentException::class.java) { VlanPlanner.removeVlan(vlans, UciBatch(), 1) }
    }

    @Test
    fun removing_a_vlan_deletes_its_section() {
        val batch = UciBatch()
        val after = VlanPlanner.removeVlan(vlans, batch, 10)
        assertEquals(listOf("delete network.vlan_guest"), batch.script().lines())
        assertEquals(listOf(1), after.vlans.map { it.vid })
        assertEquals(mapOf(1 to "lan"), after.interfaces)
    }

    @Test
    fun device_port_whose_vlan_is_removed_goes_back_home() {
        val withTv = VlanPlanner.setRoles(vlans, UciBatch(), 10, mapOf("lan3" to PortRole.UNTAGGED))
        val batch = UciBatch()
        val after = VlanPlanner.removeVlan(withTv, batch, 10)
        assertEquals(PortRole.UNTAGGED, after.vlan(1)!!.roles["lan3"])
        assertTrue("add_list network.vlan_lan.ports='lan3:u*'" in batch.script().lines())
        assertTrue("lan3 is back on your home network" in VlanPlanner.summary(withTv, after))
    }

    @Test
    fun device_port_set_to_none_or_tagged_goes_back_home() {
        val withTv = VlanPlanner.setRoles(vlans, UciBatch(), 10, mapOf("lan3" to PortRole.UNTAGGED))
        for (role in listOf(PortRole.NONE, PortRole.TAGGED)) {
            val after = VlanPlanner.setRoles(withTv, UciBatch(), 10, mapOf("lan3" to role))
            assertEquals(role.name, PortRole.UNTAGGED, after.vlan(1)!!.roles["lan3"])
            assertEquals(role, after.vlan(10)!!.roles["lan3"])
        }
    }

    @Test
    fun port_taken_off_the_home_network_on_purpose_stays_off() {
        val after = VlanPlanner.setRoles(vlans, UciBatch(), 1, mapOf("lan2" to PortRole.NONE))
        assertEquals(PortRole.NONE, after.vlan(1)!!.roles["lan2"])
    }

    @Test
    fun summary_says_what_happens_in_plain_words() {
        val batch = UciBatch()
        var after = VlanPlanner.enableFiltering(plain, network, batch)
        after = VlanPlanner.addVlan(after, batch, 10, "guest", mapOf("lan3" to PortRole.UNTAGGED, "lan4" to PortRole.TAGGED))
        assertEquals(
            listOf(
                "Turn on VLANs for your LAN ports. Wired and Wi-Fi devices stay on your home network.",
                "lan3 leaves your home network",
                "lan3 joins guest as a device port",
                "lan4 carries guest to an access point",
            ),
            VlanPlanner.summary(plain, after),
        )
        assertEquals(listOf("Remove VLAN 10 (guest)"), VlanPlanner.summary(vlans, VlanPlanner.removeVlan(vlans, UciBatch(), 10)))
    }
}
