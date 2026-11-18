package dev.openwrtmanager.app.ui.editor

import dev.openwrtmanager.app.editor.EditorEdit
import dev.openwrtmanager.app.testing.MainDispatcherRule
import dev.openwrtmanager.app.ui.admin.FakeAdminGateway
import dev.openwrtmanager.app.uci.UciConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EditorViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val gateway = FakeAdminGateway()

    private fun viewModel() = EditorViewModel { gateway }

    @Test
    fun lists_configs() {
        val vm = viewModel().also { it.loadConfigs() }
        assertEquals(listOf("dhcp", "network", "system"), vm.ui.value.configs)
    }

    @Test
    fun edits_are_staged_and_counted() {
        val vm = viewModel().also { it.open("system") }
        assertEquals("system", vm.ui.value.config?.name)
        vm.stage(EditorEdit.SetOption("cfg01e48a", "hostname", "home"))
        vm.stage(EditorEdit.SetOption("cfg01e48a", "zonename", "UTC"))
        assertEquals(2, vm.ui.value.edits.size)
        vm.unstage(0)
        assertEquals(listOf(EditorEdit.SetOption("cfg01e48a", "zonename", "UTC")), vm.ui.value.edits)
    }

    @Test
    fun review_reloads_and_plans() = runTest {
        val vm = viewModel().also { it.open("system") }
        vm.stage(EditorEdit.SetOption("cfg01e48a", "hostname", "home"))
        val loadsBefore = gateway.configLoads
        val change = vm.review()!!
        assertTrue(gateway.configLoads > loadsBefore)
        assertEquals(listOf("set system.cfg01e48a.hostname='home'"), change.batch.script().lines())
    }

    @Test
    fun a_section_removed_elsewhere_is_refused_at_review() = runTest {
        val vm = viewModel().also { it.open("system") }
        vm.stage(EditorEdit.SetOption("cfg01e48a", "hostname", "home"))
        gateway.configs["system"] = UciConfig("system", emptyList())
        assertNull(vm.review())
        assertEquals("A section you edited no longer exists on the device. Reload and try again.", vm.ui.value.message)
    }

    @Test
    fun discard_clears_edits() {
        val vm = viewModel().also { it.open("system") }
        vm.stage(EditorEdit.DeleteSection("ntp"))
        vm.discard()
        assertTrue(vm.ui.value.edits.isEmpty())
    }

    @Test
    fun applying_reloads_and_clears() {
        val vm = viewModel().also { it.open("system") }
        vm.stage(EditorEdit.SetOption("cfg01e48a", "hostname", "home"))
        vm.applied()
        assertTrue(vm.ui.value.edits.isEmpty())
    }
}
