package dev.openwrtmanager.app.ui.terminal

import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.terminal.FakeShell
import dev.openwrtmanager.app.terminal.SessionState
import dev.openwrtmanager.app.testing.MainDispatcherRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TerminalViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val shells = mutableListOf<FakeShell>()
    private val sizes = mutableListOf<Pair<Int, Int>>()
    private var failure: Exception? = null
    private var connected = true

    private fun viewModel() = TerminalViewModel { cols, rows ->
        if (!connected) return@TerminalViewModel null
        failure?.let { throw it }
        sizes += cols to rows
        FakeShell().also { shells += it }
    }

    private suspend fun waitFor(check: () -> Boolean) = withTimeout(3_000) { while (!check()) delay(10) }

    @Test
    fun opens_on_its_own_connection_at_the_measured_size() = runBlocking<Unit> {
        val vm = viewModel()
        vm.start(80, 24)
        waitFor { vm.ui.value is TerminalUi.Open }
        assertEquals(listOf(80 to 24), sizes)
        vm.start(80, 24)
        assertEquals("a second start does not open another shell", 1, shells.size)
    }

    @Test
    fun disconnect_is_shown_and_input_is_refused() = runBlocking<Unit> {
        val vm = viewModel()
        vm.start(80, 24)
        waitFor { vm.ui.value is TerminalUi.Open }
        val session = (vm.ui.value as TerminalUi.Open).session
        shells.single().close()
        waitFor { session.state.value is SessionState.Closed }
        session.type("ls\n")
        assertEquals("nothing sent after the drop", "", shells.single().sent.toString())
    }

    @Test
    fun reconnect_opens_a_new_shell() = runBlocking<Unit> {
        val vm = viewModel()
        vm.start(80, 24)
        waitFor { vm.ui.value is TerminalUi.Open }
        shells.single().close()
        vm.reconnect()
        waitFor { shells.size == 2 && vm.ui.value is TerminalUi.Open }
        assertTrue((vm.ui.value as TerminalUi.Open).session.state.value == SessionState.Open)
    }

    @Test
    fun failures_are_messages() = runBlocking<Unit> {
        connected = false
        val vm = viewModel()
        vm.start(80, 24)
        waitFor { vm.ui.value is TerminalUi.Failed }
        assertEquals("Connect to a device on the Home tab first.", (vm.ui.value as TerminalUi.Failed).message)
        connected = true
        failure = SshError.AuthFailed()
        vm.reconnect()
        waitFor { (vm.ui.value as? TerminalUi.Failed)?.message == "Could not open a shell on the device." }
    }

    @Test
    fun leaving_closes_the_connection() = runBlocking<Unit> {
        val vm = viewModel()
        vm.start(80, 24)
        waitFor { vm.ui.value is TerminalUi.Open }
        vm.close()
        assertTrue(!shells.single().isOpen)
    }
}
