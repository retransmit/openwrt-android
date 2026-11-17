package dev.openwrtmanager.app.ui.clients

import dev.openwrtmanager.app.clients.ClientLink
import dev.openwrtmanager.app.clients.SshClientsGateway
import dev.openwrtmanager.app.clients.needsDhcpv4Server
import dev.openwrtmanager.app.testing.FakeClientsGateway
import dev.openwrtmanager.app.testing.MainDispatcherRule
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClientsViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val gateway = FakeClientsGateway()
    private var connected = true

    private fun viewModel() = ClientsViewModel { if (connected) gateway else null }

    private fun ready(vm: ClientsViewModel) = vm.ui.value as ClientsUi.Ready

    private val phone = "EA:D5:C5:AA:00:02"

    @Test
    fun lists_online_and_blocked_separately() = runTest {
        gateway.config = gateway.config.copy(
            firewall = UciConfig(
                "firewall",
                gateway.config.firewall.sections +
                    UciSection("owm_blocked", "ipset", false, 90, mapOf("name" to "owm_blocked"), mapOf("entry" to listOf("02:00:00:AA:00:0E"))),
            ),
        )
        val vm = viewModel()
        vm.refresh()
        val state = ready(vm)
        assertTrue(state.online.any { it.mac == phone && it.link == ClientLink.WIFI })
        assertTrue("upstream never listed", state.online.none { it.ip == "192.168.0.1" })
        assertEquals(listOf("02:00:00:AA:00:0E"), state.blocked.map { it.mac })
        assertTrue(state.online.none { it.blocked && it.link == ClientLink.OFFLINE })
    }

    @Test
    fun refreshes_every_five_seconds_while_polling() = runTest {
        val vm = viewModel()
        val job = launch { vm.poll() }
        runCurrent()
        assertEquals(1, gateway.reads)
        advanceTimeBy(5_001)
        assertEquals(2, gateway.reads)
        advanceTimeBy(10_000)
        assertEquals(4, gateway.reads)
        job.cancel()
    }

    @Test
    fun missing_sources_are_shown() = runTest {
        gateway.readings = gateway.readings.copy(missing = listOf("neighbours"))
        val vm = viewModel().also { it.refresh() }
        assertEquals(listOf("neighbours"), ready(vm).missing)
    }

    @Test
    fun reconnect_calls_the_router_and_reports() = runTest {
        val vm = viewModel().also { it.refresh() }
        val client = ready(vm).online.first { it.mac == phone }
        vm.reconnect(client)
        assertEquals(listOf("phy1-ap0" to phone), gateway.reconnected)
        assertEquals("Asked I2302 to reconnect.", ready(vm).message)
    }

    @Test
    fun reconnect_failure_is_a_message() = runTest {
        gateway.reconnectWorks = false
        val vm = viewModel().also { it.refresh() }
        vm.reconnect(ready(vm).online.first { it.mac == phone })
        assertEquals("Could not reconnect I2302. It may have left already.", ready(vm).message)
    }

    @Test
    fun block_writes_the_file_before_returning_a_change() = runTest {
        val vm = viewModel().also { it.refresh() }
        val change = vm.block(ready(vm).online.first { it.mac == phone })!!
        assertEquals(1, gateway.fileWrites)
        assertTrue("add_list firewall.owm_blocked.entry='$phone'" in change.batch.script())
    }

    @Test
    fun block_is_refused_if_the_file_cannot_be_written() = runTest {
        gateway.fileWorks = false
        val vm = viewModel().also { it.refresh() }
        assertNull(vm.block(ready(vm).online.first { it.mac == phone }))
        assertEquals("Could not prepare the block on the router. Nothing was changed.", ready(vm).message)
    }

    @Test
    fun unblock_returns_the_change() = runTest {
        val vm = viewModel().also { it.refresh() }
        val change = vm.unblock(ready(vm).online.first { it.mac == phone })!!
        assertTrue("not blocked, nothing to do", change.isEmpty())
    }

    private fun blocking(vararg macs: String) = gateway.config.copy(
        firewall = UciConfig(
            "firewall",
            routerFirewall().sections +
                UciSection("owm_blocked", "ipset", false, 90, mapOf("name" to "owm_blocked", "match" to "src_mac"), mapOf("entry" to macs.toList())) +
                UciSection("owm_blocked_drop", "include", false, 91, mapOf("type" to "nftables"), emptyMap()),
        ),
    )

    private fun routerFirewall() = dev.openwrtmanager.app.testing.routerDeviceConfig().firewall

    @Test
    fun block_is_planned_from_the_routers_current_config() = runTest {
        val vm = viewModel().also { it.refresh() }
        val pc = ready(vm).online.first { it.mac == "58:11:22:AA:00:03" }
        // Another block landed after the screen last refreshed.
        gateway.config = blocking(phone)
        val lines = vm.block(pc)!!.batch.script().lines()
        assertEquals(listOf("add_list firewall.owm_blocked.entry='58:11:22:AA:00:03'"), lines)
    }

    @Test
    fun unblock_is_planned_from_the_routers_current_config() = runTest {
        gateway.config = blocking(phone)
        val vm = viewModel().also { it.refresh() }
        val client = ready(vm).online.first { it.mac == phone }
        // A second block landed meanwhile: lifting this one must not remove the whole set.
        gateway.config = blocking(phone, "58:11:22:AA:00:03")
        assertEquals(listOf("del_list firewall.owm_blocked.entry='$phone'"), vm.unblock(client)!!.batch.script().lines())
    }

    @Test
    fun a_missing_block_file_is_restored_when_blocks_exist() = runTest {
        gateway.config = blocking(phone)
        viewModel().also { it.refresh() }
        assertEquals(1, gateway.fileWrites)
        gateway.config = routerDeviceConfigNoBlocks()
        val other = FakeClientsGateway()
        ClientsViewModel { other }.also { it.refresh() }
        assertEquals("no blocks, no file write", 0, other.fileWrites)
    }

    private fun routerDeviceConfigNoBlocks() = dev.openwrtmanager.app.testing.routerDeviceConfig()

    @Test
    fun this_phone_is_recognised() = runTest {
        val vm = viewModel().also { it.refresh() }
        assertTrue(vm.isThisPhone(ready(vm).online.first { it.mac == phone }))
        assertFalse(vm.isThisPhone(ready(vm).online.first { it.mac == "58:11:22:AA:00:03" }))
    }

    @Test
    fun reserve_problem_is_a_message() = runTest {
        val vm = viewModel().also { it.refresh() }
        val client = ready(vm).online.first { it.mac == phone }
        assertNull(vm.reserve(client, "192.168.1.1", "phone"))
        assertEquals("That is the router's own address.", ready(vm).message)
        vm.clearMessage()
        val change = vm.reserve(client, "192.168.1.126", "phone")!!
        assertTrue("set dhcp.owm_host_ead5c5aa0002.ip='192.168.1.126'" in change.batch.script())
    }

    @Test
    fun actions_follow_the_config() = runTest {
        val vm = viewModel().also { it.refresh() }
        val actions = vm.actions(ready(vm).online.first { it.mac == phone })
        assertTrue(actions.reconnect && actions.reserve && actions.block)
    }

    @Test
    fun offline_and_failure_states() = runTest {
        connected = false
        assertEquals(ClientsUi.Offline, viewModel().also { it.refresh() }.ui.value)
        connected = true
        gateway.failure = IllegalStateException("boom")
        assertEquals(ClientsUi.Failed, viewModel().also { it.refresh() }.ui.value)
    }

    @Test
    fun dhcpv4_server_is_needed_from_25_and_on_snapshots() {
        assertTrue(needsDhcpv4Server("OpenWrt SNAPSHOT r35553+1410-fdb162fb81"))
        assertTrue(needsDhcpv4Server("OpenWrt 25.12.6 r1234"))
        assertFalse(needsDhcpv4Server("OpenWrt 24.10.4 r28959"))
        assertFalse(needsDhcpv4Server("unknown"))
    }

    @Test
    fun reconnect_command_is_checked() {
        assertEquals(
            """ubus call hostapd.phy1-ap0 del_client '{"addr":"ea:d5:c5:aa:00:02","reason":5,"deauth":true,"ban_time":0}'""",
            SshClientsGateway.reconnectCommand("phy1-ap0", phone),
        )
        assertNull(SshClientsGateway.reconnectCommand("phy1;reboot", phone))
        assertNull(SshClientsGateway.reconnectCommand("phy1-ap0", "nope"))
    }
}
