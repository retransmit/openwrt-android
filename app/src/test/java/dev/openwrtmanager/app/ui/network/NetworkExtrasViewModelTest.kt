package dev.openwrtmanager.app.ui.network

import dev.openwrtmanager.app.clients.Pool
import dev.openwrtmanager.app.router.InterfaceStatus
import dev.openwrtmanager.app.testing.FakeClientsGateway
import dev.openwrtmanager.app.testing.MainDispatcherRule
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NetworkExtrasViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val gateway = FakeClientsGateway().apply {
        interfaces = listOf(
            InterfaceStatus("lan", true, "static", "192.168.1.1/24", 7200, 10, 20),
            InterfaceStatus("wan", true, "dhcp", "192.168.0.178/24", 7100, 30, 40),
        )
        config = config.copy(
            dhcp = UciConfig(
                "dhcp",
                config.dhcp.sections + UciSection("cfg0a1b2c", "host", true, 60, mapOf("name" to "printer", "mac" to "11:22:33:44:55:66", "ip" to "192.168.1.50"), emptyMap()),
            ),
        )
    }
    private var connected = true

    private fun viewModel() = NetworkExtrasViewModel { if (connected) gateway else null }.also { it.load() }

    private fun ready(vm: NetworkExtrasViewModel) = vm.ui.value as ExtrasUi.Ready

    @Test
    fun interfaces_come_with_their_pools() {
        val rows = ready(viewModel()).interfaces
        assertEquals(listOf("lan", "wan"), rows.map { it.status.name })
        assertEquals(Pool("lan", "lan", 100, 150, "12h", "192.168.1.0/24"), rows[0].pool)
        assertNull("wan's pool is ignored", rows[1].pool)
    }

    @Test
    fun reservations_listed_with_edit_and_remove() {
        val vm = viewModel()
        val reservation = ready(vm).reservations.single()
        assertEquals("printer", reservation.name)
        val edit = vm.editReservation(reservation, "192.168.1.60", "printer")!!
        assertEquals(listOf("set dhcp.cfg0a1b2c.ip='192.168.1.60'"), edit.batch.script().lines())
        assertEquals(listOf("delete dhcp.cfg0a1b2c"), vm.removeReservation(reservation).batch.script().lines())
    }

    @Test
    fun reservation_problem_is_a_message() {
        val vm = viewModel()
        assertNull(vm.editReservation(ready(vm).reservations.single(), "192.168.1.1", "printer"))
        assertEquals("That is the router's own address.", ready(vm).message)
        vm.clearMessage()
        assertNull(ready(vm).message)
    }

    @Test
    fun pool_edit_problem_is_a_message() {
        val vm = viewModel()
        val pool = ready(vm).interfaces[0].pool!!
        assertNull(vm.editPool(pool, 200, 100, "12h"))
        assertEquals("The pool must fit inside 192.168.1.0/24.", ready(vm).message)
        assertTrue(vm.editPool(pool, 100, 100, "12h")!!.batch.script().contains("limit"))
    }

    @Test
    fun unchanged_pool_is_no_change() {
        val vm = viewModel()
        assertNull(vm.editPool(ready(vm).interfaces[0].pool!!, 100, 150, "12h"))
        assertNull(ready(vm).message)
    }

    @Test
    fun offline_and_failure_states() {
        connected = false
        assertEquals(ExtrasUi.Offline, viewModel().ui.value)
        connected = true
        gateway.failure = IllegalStateException("boom")
        assertEquals(ExtrasUi.Failed, viewModel().ui.value)
    }
}
