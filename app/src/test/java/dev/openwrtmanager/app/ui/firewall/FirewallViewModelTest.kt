package dev.openwrtmanager.app.ui.firewall

import dev.openwrtmanager.app.firewall.ForwardInput
import dev.openwrtmanager.app.router.InterfaceStatus
import dev.openwrtmanager.app.testing.FakeClientsGateway
import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.testing.MainDispatcherRule
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import dev.openwrtmanager.app.uci.parseUciConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FirewallViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val gateway = FakeClientsGateway().apply {
        config = config.copy(firewall = parseUciConfig("firewall", Fixtures.read("uci_firewall_full.json")))
        interfaces = listOf(
            InterfaceStatus("lan", true, "static", "192.168.1.1/24", 100, 1, 1),
            InterfaceStatus("wan", true, "dhcp", "192.168.0.178/24", 100, 1, 1),
        )
    }
    private var connected = true

    private fun viewModel() = FirewallViewModel { if (connected) gateway else null }.also { it.load() }

    private fun ready(vm: FirewallViewModel) = vm.ui.value as FirewallUi.Ready

    private val pc = "58:11:22:AA:00:03"

    @Test
    fun loads_view_clients_and_private_wan() {
        val state = ready(viewModel())
        assertEquals("wan", state.view.wanZone)
        assertTrue(state.view.rules.isNotEmpty())
        assertTrue(state.clients.any { it.mac == pc })
        assertTrue("only devices with an address can be targets", state.clients.all { it.ip != null })
        assertTrue(state.privateWan)
        gateway.interfaces = listOf(InterfaceStatus("wan", true, "dhcp", "81.2.3.4/24", 1, 1, 1))
        assertFalse(ready(viewModel()).privateWan)
    }

    @Test
    fun add_is_planned_from_fresh_config() = runTest {
        val vm = viewModel()
        val target = ready(vm).clients.first { it.mac == pc }
        // A forward on 8080 appeared after the screen loaded.
        gateway.config = gateway.config.copy(
            firewall = UciConfig(
                "firewall",
                gateway.config.firewall.sections + UciSection("owm_fwd_1", "redirect", false, 90, mapOf("src_dport" to "8080", "proto" to "tcp", "dest_ip" to "192.168.1.9"), emptyMap()),
            ),
        )
        assertNull(vm.addForward(ForwardInput("Web", setOf("tcp"), "8080", null, target)))
        assertEquals("Another port forward already uses that outside port.", ready(vm).message)
        vm.clearMessage()
        val change = vm.addForward(ForwardInput("Web", setOf("tcp"), "8081", null, target))!!
        assertTrue(change.batch.script().contains("owm_fwd_2"))
    }

    @Test
    fun problems_are_messages() = runTest {
        val vm = viewModel()
        val target = ready(vm).clients.first { it.mac == pc }
        assertNull(vm.addForward(ForwardInput("Web", setOf("tcp"), "70000", null, target)))
        assertEquals("Ports are numbers from 1 to 65535, or a range like 6000-6010.", ready(vm).message)
    }

    @Test
    fun toggle_and_remove_return_changes() = runTest {
        val vm = viewModel()
        val ping = ready(vm).view.rules.first { it.name == "Allow-Ping" }
        assertTrue(vm.toggleRule(ping).batch.script().contains("enabled='0'"))
        assertEquals(listOf("delete firewall.${ping.section}"), vm.removeRule(ping).batch.script().lines())
    }

    @Test
    fun offline_and_failure_states() {
        connected = false
        assertEquals(FirewallUi.Offline, viewModel().ui.value)
        connected = true
        gateway.failure = IllegalStateException("boom")
        assertEquals(FirewallUi.Failed, viewModel().ui.value)
    }
}
