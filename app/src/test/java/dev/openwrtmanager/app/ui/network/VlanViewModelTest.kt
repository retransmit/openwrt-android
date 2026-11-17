package dev.openwrtmanager.app.ui.network

import dev.openwrtmanager.app.testing.FakeWifiGateway
import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.testing.MainDispatcherRule
import dev.openwrtmanager.app.testing.accessPointSnapshot
import dev.openwrtmanager.app.testing.routerSnapshot
import dev.openwrtmanager.app.uci.UciSection
import dev.openwrtmanager.app.uci.parseUciConfig
import dev.openwrtmanager.app.vlan.PortRole
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VlanViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    /** The router after the guest wizard: VLAN filtering on, guest on VLAN 10 with lan4 tagged. */
    private fun vlanSnapshot() = routerSnapshot().let { base ->
        val guest = listOf(
            UciSection("guest", "dhcp", false, 90, mapOf("interface" to "guest"), emptyMap()),
        )
        base.copy(network = parseUciConfig("network", Fixtures.read("uci_network_vlans.json")), dhcp = base.dhcp.copy(sections = base.dhcp.sections + guest))
    }

    private val gateway = FakeWifiGateway(vlanSnapshot())
    private var connected = true

    private fun viewModel() = VlanViewModel { if (connected) gateway else null }.also { it.load() }

    private fun ready(vm: VlanViewModel) = vm.ui.value as VlanUi.Ready

    @Test
    fun rows_show_ids_names_and_ports() = runTest {
        val state = ready(viewModel())
        assertEquals(listOf("lan1", "lan2", "lan3", "lan4"), state.ports)
        assertTrue(state.filtering)
        val (home, guest) = state.rows
        assertEquals(VlanRow(1, "lan", "192.168.1.0/24", mapOf("lan1" to PortRole.UNTAGGED, "lan2" to PortRole.UNTAGGED, "lan3" to PortRole.UNTAGGED, "lan4" to PortRole.UNTAGGED), removable = false, home = true), home)
        assertEquals(10, guest.vid)
        assertEquals("guest", guest.name)
        assertEquals("192.168.10.0/24", guest.subnet)
        assertEquals(PortRole.TAGGED, guest.roles["lan4"])
        assertTrue(guest.removable)
    }

    @Test
    fun router_without_filtering_shows_one_home_row() = runTest {
        gateway.snapshot = routerSnapshot()
        val state = ready(viewModel())
        assertFalse(state.filtering)
        assertEquals(listOf(1), state.rows.map { it.vid })
        assertTrue(state.rows.single().roles.values.all { it == PortRole.UNTAGGED })
    }

    @Test
    fun non_dsa_device_is_unsupported() = runTest {
        gateway.snapshot = accessPointSnapshot().let { ap ->
            ap.copy(network = ap.network.copy(sections = ap.network.sections.map { if (it.type == "device") it.copy(lists = it.lists - "ports") else it }))
        }
        assertEquals(VlanUi.Unsupported, viewModel().ui.value)
    }

    @Test
    fun offline_and_failure_states() = runTest {
        connected = false
        assertEquals(VlanUi.Offline, viewModel().ui.value)
        connected = true
        gateway.failure = IllegalStateException("boom")
        assertEquals(VlanUi.Failed, viewModel().ui.value)
    }

    @Test
    fun untagging_a_port_moves_it_and_says_so() = runTest {
        val vm = viewModel()
        val change = vm.editRoles(10, mapOf("lan3" to PortRole.UNTAGGED))!!
        assertEquals("Change VLAN ports", change.title)
        assertEquals(listOf("lan3 leaves your home network", "lan3 joins guest as a device port"), change.summary)
        assertTrue("add_list network.vlan_guest.ports='lan3:u*'" in change.batch.script())
        assertTrue(change.mayDropConnection)
    }

    @Test
    fun editing_ports_on_a_plain_router_turns_filtering_on_first() = runTest {
        gateway.snapshot = routerSnapshot()
        val change = viewModel().editRoles(1, mapOf("lan4" to PortRole.NONE))!!
        assertTrue(change.summary.first().startsWith("Turn on VLANs"))
        assertTrue("set network.vlan_lan=bridge-vlan" in change.batch.script())
    }

    @Test
    fun unchanged_roles_give_no_change() = runTest {
        assertNull(viewModel().editRoles(10, mapOf("lan4" to PortRole.TAGGED)))
    }

    @Test
    fun change_that_cuts_off_lan_is_refused_with_a_message() = runTest {
        gateway.snapshot = vlanSnapshot().let { s ->
            s.copy(wireless = s.wireless.copy(sections = s.wireless.sections.filterNot { it.type == "wifi-iface" }))
        }
        val vm = viewModel()
        val all = listOf("lan1", "lan2", "lan3", "lan4").associateWith { PortRole.UNTAGGED }
        assertNull(vm.editRoles(10, all))
        assertEquals(
            "That would leave your home network with no port and no Wi-Fi, so nothing could reach the router on it.",
            ready(vm).message,
        )
        vm.clearMessage()
        assertNull(ready(vm).message)
    }

    @Test
    fun vlan_one_cannot_be_removed() = runTest {
        val vm = viewModel()
        assertNull(vm.remove(1))
    }

    @Test
    fun removing_a_wizard_vlan_removes_its_network() = runTest {
        val change = viewModel().remove(10)!!
        val lines = change.batch.script().lines()
        assertTrue("delete network.vlan_guest" in lines)
        assertTrue("delete network.guest" in lines)
        assertTrue("delete dhcp.guest" in lines)
    }

    @Test
    fun removing_a_pass_through_vlan_removes_only_the_vlan() = runTest {
        val vm = viewModel()
        gateway.snapshot = vlanSnapshot().let { s ->
            s.copy(network = s.network.copy(sections = s.network.sections + UciSection("vlan_cams", "bridge-vlan", false, 50, mapOf("device" to "br-lan", "vlan" to "30"), mapOf("ports" to listOf("lan2:t")))))
        }
        vm.load()
        val change = vm.remove(30)!!
        assertEquals(listOf("delete network.vlan_cams"), change.batch.script().lines())
        assertEquals(listOf("Remove VLAN 30 (cams)"), change.summary)
    }

    @Test
    fun create_routed_vlan_uses_the_custom_wizard() = runTest {
        val change = viewModel().create(30, "cameras", mapOf("lan3" to PortRole.UNTAGGED), routed = true)!!
        val script = change.batch.script()
        assertTrue("set network.cameras.device='br-lan.30'" in script)
        assertTrue("set dhcp.cameras=dhcp" in script)
        assertTrue("set firewall.cameras=zone" in script)
        assertFalse("wireless" in change.batch.configs)
        assertEquals(listOf("cameras"), change.checkInterfaces)
    }

    @Test
    fun create_pass_through_vlan_writes_only_the_bridge_vlan() = runTest {
        val change = viewModel().create(30, "trunk", mapOf("lan2" to PortRole.TAGGED), routed = false)!!
        assertEquals(setOf("network"), change.batch.configs)
        assertEquals(
            listOf(
                "set network.vlan_trunk=bridge-vlan",
                "set network.vlan_trunk.device='br-lan'",
                "set network.vlan_trunk.vlan='30'",
                "add_list network.vlan_trunk.ports='lan2:t'",
            ),
            change.batch.script().lines(),
        )
        assertTrue("Create VLAN 30 (trunk) with no address on this router" in change.summary)
        assertTrue(change.checkInterfaces.isEmpty())
    }

    @Test
    fun taken_id_is_an_error_before_review() = runTest {
        val vm = viewModel()
        assertNull(vm.create(10, "again", emptyMap(), routed = false))
        assertEquals("VLAN 10 is already in use.", ready(vm).message)
        assertNull(vm.create(0, "zero", emptyMap(), routed = false))
        assertEquals("A VLAN ID is a number from 1 to 4094.", ready(vm).message)
    }

    @Test
    fun bad_name_is_an_error_before_review() = runTest {
        val vm = viewModel()
        assertNull(vm.create(30, "Bad Name", emptyMap(), routed = false))
        assertNotNull(ready(vm).message)
        assertNull(vm.create(30, "guest", emptyMap(), routed = true))
        assertNotNull(ready(vm).message)
    }

    @Test
    fun suggested_id_is_the_next_free_one() = runTest {
        assertEquals(20, ready(viewModel()).suggestedVid)
    }
}
