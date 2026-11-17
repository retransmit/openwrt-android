package dev.openwrtmanager.app.ui.wifi

import dev.openwrtmanager.app.router.RouterError
import dev.openwrtmanager.app.testing.FakeWifiGateway
import dev.openwrtmanager.app.testing.MainDispatcherRule
import dev.openwrtmanager.app.testing.accessPointSnapshot
import dev.openwrtmanager.app.testing.routerSnapshot
import dev.openwrtmanager.app.uci.UciSection
import dev.openwrtmanager.app.wifi.BlockReason
import dev.openwrtmanager.app.wifi.KeyError
import dev.openwrtmanager.app.wifi.NetworkWizard
import dev.openwrtmanager.app.wifi.Security
import dev.openwrtmanager.app.wifi.SsidError
import dev.openwrtmanager.app.wifi.UsteerStatus
import dev.openwrtmanager.app.wifi.WifiGateway
import dev.openwrtmanager.app.wifi.WizardInput
import dev.openwrtmanager.app.wifi.WizardKind
import dev.openwrtmanager.app.wifi.WizardPlan
import dev.openwrtmanager.app.wifi.validateKey
import dev.openwrtmanager.app.wifi.wifiQrPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WifiViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val gateway = FakeWifiGateway()
    private var current: WifiGateway? = gateway

    private fun viewModel() = WifiViewModel { current }

    @Test
    fun loads_networks_and_radios() = runTest {
        val vm = viewModel()
        vm.load()
        val loaded = vm.ui.value as WifiUi.Loaded
        assertEquals(listOf("Home"), loaded.config.groups.map { it.ssid })
        assertEquals(2, loaded.config.radios.size)
        assertTrue(loaded.canCreateIsolatedNetworks)
    }

    @Test
    fun access_point_cannot_create_isolated_networks() = runTest {
        gateway.snapshot = accessPointSnapshot()
        val vm = viewModel()
        vm.load()
        assertFalse((vm.ui.value as WifiUi.Loaded).canCreateIsolatedNetworks)
    }

    @Test
    fun no_connection_is_offline() = runTest {
        current = null
        val vm = viewModel()
        vm.load()
        assertEquals(WifiUi.Offline, vm.ui.value)
    }

    @Test
    fun load_failure_is_an_error_state_not_a_crash() = runTest {
        gateway.failure = RouterError.BadResponse("uci get wireless")
        val vm = viewModel()
        vm.load()
        assertEquals(WifiUi.Failed, vm.ui.value)
        gateway.failure = IllegalStateException("unexpected")
        vm.load()
        assertEquals(WifiUi.Failed, vm.ui.value)
    }

    @Test
    fun reload_picks_up_changes() = runTest {
        val vm = viewModel()
        vm.load()
        val w = gateway.snapshot.wireless
        gateway.snapshot = gateway.snapshot.copy(wireless = w.copy(sections = w.sections.filterNot { it.name == "default_radio1" }))
        vm.load()
        assertEquals(1, (vm.ui.value as WifiUi.Loaded).config.ifaces.size)
    }
}

class NetworkEditViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val gateway = FakeWifiGateway()

    private fun viewModel() = NetworkEditViewModel { gateway }

    private fun editingHome() = viewModel().also { it.start("default_radio0") }

    @Test
    fun existing_network_fills_the_form() = runTest {
        val form = editingHome().form.value
        assertTrue(form.loaded)
        assertFalse(form.isNew)
        assertEquals("Home", form.ssid)
        assertEquals(Security.WPA2_WPA3, form.security)
        assertEquals("hunter22pass", form.key)
        assertEquals(setOf("radio0", "radio1"), form.radios)
        assertTrue(form.steeringAssist)
        assertEquals(UsteerStatus(installed = true, running = false), form.usteer)
        assertFalse(form.toString().contains("hunter22pass"))
    }

    @Test
    fun unchanged_form_cannot_be_saved() = runTest {
        val vm = editingHome()
        assertFalse(vm.form.value.canSave)
        assertNull(vm.save())
    }

    @Test
    fun invalid_name_or_password_blocks_save_with_the_right_error() = runTest {
        val vm = editingHome()
        vm.onSsid("")
        vm.onKey("short")
        assertNull(vm.save())
        assertEquals(SsidError.EMPTY, vm.form.value.ssidError)
        assertEquals(KeyError.TOO_SHORT, vm.form.value.keyError)
        vm.onSsid("Fine")
        assertNull(vm.form.value.ssidError)
    }

    @Test
    fun save_returns_a_change_that_matches_the_edit() = runTest {
        val vm = editingHome()
        vm.onSsid("Home 2")
        vm.onHidden(true)
        assertTrue(vm.form.value.canSave)
        val change = vm.save()!!
        assertEquals(listOf("Rename \"Home\" to \"Home 2\"", "Hide \"Home\" from network lists"), change.summary)
    }

    @Test
    fun at_least_one_radio_is_required() = runTest {
        val vm = editingHome()
        vm.onRadio("radio0", false)
        vm.onRadio("radio1", false)
        assertNull(vm.save())
        assertTrue(vm.form.value.radioError)
    }

    @Test
    fun new_network_starts_empty_on_all_radios_and_attaches_to_lan() = runTest {
        val vm = viewModel()
        vm.start(null)
        assertTrue(vm.form.value.isNew)
        assertEquals(setOf("radio0", "radio1"), vm.form.value.radios)
        vm.onSsid("Second")
        vm.onKey("secondpass1")
        val change = vm.save()!!
        assertEquals(listOf("Add network \"Second\" on 2.4 GHz and 5 GHz"), change.summary)
        assertTrue("set wireless.wifinet_radio0.network='lan'" in change.batch.script())
    }

    @Test
    fun steering_changes_are_built_separately() = runTest {
        val vm = editingHome()
        assertTrue("already on: nothing to do", vm.steeringChange(enable = true, fastRoaming = false)!!.isEmpty())
        val off = vm.steeringChange(enable = false, fastRoaming = false)!!
        assertEquals(listOf("Stop helping devices on \"Home\" move between bands"), off.summary)
        val fast = vm.steeringChange(enable = true, fastRoaming = true)!!
        assertEquals(listOf("Turn on fast roaming for \"Home\""), fast.summary)
    }

    @Test
    fun steering_daemon_can_be_started() = runTest {
        val vm = editingHome()
        vm.startUsteer()
        assertEquals(1, gateway.started)
        assertTrue(vm.form.value.usteer!!.running)
    }

    @Test
    fun removing_a_home_network_deletes_only_its_wifi() = runTest {
        val change = editingHome().removeChange()!!
        assertEquals(listOf("delete wireless.default_radio0", "delete wireless.default_radio1"), change.batch.script().lines())
    }

    @Test
    fun removing_a_wizard_network_removes_the_whole_network() = runTest {
        val plan = NetworkWizard.plan(
            gateway.snapshot,
            WizardInput(WizardKind.GUEST, "Home-Guest", Security.WPA2, "guestpass1", setOf("radio0")),
        ) as WizardPlan.Ready
        // Pretend the plan was applied: add just enough sections for the model to see the guest network.
        fun s(name: String, type: String, o: Map<String, String>, l: Map<String, List<String>> = emptyMap()) = UciSection(name, type, false, 90, o, l)
        val snap = gateway.snapshot
        gateway.snapshot = snap.copy(
            wireless = snap.wireless.copy(
                sections = snap.wireless.sections + s(
                    "guest_radio0", "wifi-iface",
                    mapOf("device" to "radio0", "mode" to "ap", "network" to "guest", "ssid" to "Home-Guest", "encryption" to "psk2", "key" to "guestpass1"),
                ),
            ),
            network = snap.network.copy(sections = snap.network.sections + s("guest", "interface", mapOf("device" to "br-guest"))),
            firewall = snap.firewall.copy(sections = snap.firewall.sections + s("guest", "zone", mapOf("name" to "guest"), mapOf("network" to listOf("guest")))),
        )
        assertEquals("guest", plan.networkName)
        val vm = viewModel()
        vm.start("guest_radio0")
        val script = vm.removeChange()!!.batch.script()
        assertTrue("delete wireless.guest_radio0" in script)
        assertTrue("delete network.guest" in script)
        assertTrue("delete firewall.guest" in script)
    }
}

class WizardViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val gateway = FakeWifiGateway()

    private fun viewModel() = WizardViewModel({ gateway }, { "Abcdefgh2345" })

    @Test
    fun guest_wizard_suggests_a_name_password_and_all_bands() = runTest {
        val vm = viewModel()
        vm.start(WizardKind.GUEST)
        val ui = vm.ui.value
        assertTrue(ui.loaded)
        assertNull(ui.blocked)
        assertEquals("Home-Guest", ui.ssid)
        assertEquals("Abcdefgh2345", ui.key)
        assertEquals(setOf("radio0", "radio1"), ui.radios)
        assertFalse(ui.toString().contains("Abcdefgh2345"))
    }

    @Test
    fun iot_wizard_defaults_to_two_point_four_only() = runTest {
        val vm = viewModel()
        vm.start(WizardKind.IOT)
        assertEquals("Home-IoT", vm.ui.value.ssid)
        assertEquals(setOf("radio0"), vm.ui.value.radios)
        assertTrue(vm.ui.value.lanCanReachIot)
    }

    @Test
    fun access_point_is_blocked_up_front() = runTest {
        gateway.snapshot = accessPointSnapshot()
        val vm = viewModel()
        vm.start(WizardKind.GUEST)
        assertEquals(BlockReason.NO_UPLINK, vm.ui.value.blocked)
    }

    @Test
    fun generated_passwords_are_valid_and_unambiguous() {
        repeat(50) {
            val password = generateWifiPassword(java.security.SecureRandom())
            assertEquals(12, password.length)
            assertNull(validateKey(password, Security.WPA2))
            assertTrue(password.none { it in "0O1lI" })
        }
    }

    @Test
    fun review_validates_then_returns_the_planned_change() = runTest {
        val vm = viewModel()
        vm.start(WizardKind.GUEST)
        vm.onKey("short")
        assertNull(vm.review())
        assertEquals(KeyError.TOO_SHORT, vm.ui.value.keyError)
        vm.onKey("guest pass 1")
        val change = vm.review()!!
        val expected = NetworkWizard.plan(
            routerSnapshot(),
            WizardInput(WizardKind.GUEST, "Home-Guest", Security.WPA2_WPA3, "guest pass 1", setOf("radio0", "radio1")),
        ) as WizardPlan.Ready
        assertEquals(expected.change.batch.script(), change.batch.script())
        assertEquals(expected.change.summary, change.summary)
    }

    @Test
    fun qr_code_is_offered_only_after_the_change_was_applied() = runTest {
        val vm = viewModel()
        vm.start(WizardKind.GUEST)
        assertNotNull(vm.review())
        assertNull(vm.ui.value.qrPayload)
        vm.onApplied()
        assertEquals(wifiQrPayload("Home-Guest", Security.WPA2_WPA3, "Abcdefgh2345", hidden = false), vm.ui.value.qrPayload)
    }

    @Test
    fun load_failure_is_shown_not_thrown() = runTest {
        gateway.failure = IllegalStateException("unexpected")
        val vm = viewModel()
        vm.start(WizardKind.GUEST)
        assertTrue(vm.ui.value.failed)
    }
}

class NetworkEditSafetyTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val gateway = FakeWifiGateway()

    @Test
    fun network_with_a_security_mode_the_app_does_not_understand_is_read_only() = runTest {
        val w = gateway.snapshot.wireless
        gateway.snapshot = gateway.snapshot.copy(
            wireless = w.copy(sections = w.sections.map { if (it.type == "wifi-iface") it.copy(options = it.options + ("encryption" to "wpa3-mixed")) else it }),
        )
        val vm = NetworkEditViewModel { gateway }
        vm.start("default_radio0")
        assertTrue(vm.form.value.readOnly)
        vm.onSsid("Changed")
        assertFalse(vm.form.value.canSave)
        assertNull(vm.save())
        assertNull("steering would rewrite the security mode too", vm.steeringChange(enable = true, fastRoaming = false))
    }

    @Test
    fun removing_a_hand_made_network_only_removes_its_wifi() = runTest {
        fun s(name: String, type: String, o: Map<String, String>) = UciSection(name, type, false, 90, o, emptyMap())
        val snap = gateway.snapshot
        gateway.snapshot = snap.copy(
            wireless = snap.wireless.copy(
                sections = snap.wireless.sections + s(
                    "office_radio0", "wifi-iface",
                    mapOf("device" to "radio0", "mode" to "ap", "network" to "office", "ssid" to "Office", "encryption" to "psk2", "key" to "officepass1"),
                ),
            ),
            network = snap.network.copy(sections = snap.network.sections + s("office", "interface", mapOf("device" to "br-office"))),
        )
        val vm = NetworkEditViewModel { gateway }
        vm.start("office_radio0")
        assertEquals(listOf("delete wireless.office_radio0"), vm.removeChange()!!.batch.script().lines())
    }
}
