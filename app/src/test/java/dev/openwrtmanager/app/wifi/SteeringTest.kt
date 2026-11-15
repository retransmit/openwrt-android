package dev.openwrtmanager.app.wifi

import dev.openwrtmanager.app.testing.FakeSshExecutor
import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.uci.parseUciConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SteeringTest {
    private val wireless = parseUciConfig("wireless", Fixtures.read("uci_wireless.json"))
    private val both = wifiConfigOf(wireless)

    /** Only the 2.4 GHz interface, with no roaming options at all. */
    private val single = wifiConfigOf(
        wireless.copy(
            sections = wireless.sections.filterNot { it.name == "default_radio1" }.map {
                if (it.name == "default_radio0") it.copy(options = it.options - "ieee80211k" - "bss_transition") else it
            },
        ),
    )
    private val allRadios = SteeringRequest(setOf("radio0", "radio1"), fastRoaming = false)

    @Test
    fun already_assisted_group_needs_no_change() {
        assertTrue(Steering.enable(both, both.groups.single(), allRadios).isEmpty())
    }

    @Test
    fun enabling_extends_to_the_other_band_and_turns_on_assistance() {
        val change = Steering.enable(single, single.groups.single(), allRadios)
        val script = change.batch.script()
        assertTrue("set wireless.wifinet_radio1=wifi-iface" in script)
        assertTrue("set wireless.wifinet_radio1.ssid='Home'" in script)
        assertTrue("set wireless.wifinet_radio1.key='hunter22pass'" in script)
        for (section in listOf("default_radio0", "wifinet_radio1")) {
            assertTrue("set wireless.$section.ieee80211k='1'" in script)
            assertTrue("set wireless.$section.bss_transition='1'" in script)
        }
        assertFalse("ieee80211r" in script)
        assertEquals(
            listOf("Also broadcast \"Home\" on 5 GHz", "Help devices on \"Home\" move to the better band"),
            change.summary,
        )
    }

    @Test
    fun fast_roaming_adds_its_options_with_a_stable_mobility_domain() {
        val request = allRadios.copy(fastRoaming = true)
        val script = Steering.enable(both, both.groups.single(), request).batch.script()
        val domain = Regex("mobility_domain='([0-9a-f]{4})'").findAll(script).map { it.groupValues[1] }.toSet()
        assertEquals("one domain shared by both radios", 1, domain.size)
        for (section in listOf("default_radio0", "default_radio1")) {
            assertTrue("set wireless.$section.ieee80211r='1'" in script)
            assertTrue("set wireless.$section.ft_over_ds='0'" in script)
            assertTrue("set wireless.$section.ft_psk_generate_local='1'" in script)
        }
        assertEquals(script, Steering.enable(both, both.groups.single(), request).batch.script())
        assertEquals(domain.single(), Steering.mobilityDomain("Home"))
        assertFalse(Steering.mobilityDomain("Home") == Steering.mobilityDomain("Other"))
    }

    @Test
    fun fast_roaming_is_refused_on_open_networks() {
        val open = wifiConfigOf(
            wireless.copy(sections = wireless.sections.map { if (it.type == "wifi-iface") it.copy(options = it.options + ("encryption" to "none") - "key") else it }),
        )
        assertThrows(IllegalArgumentException::class.java) {
            Steering.enable(open, open.groups.single(), allRadios.copy(fastRoaming = true))
        }
    }

    @Test
    fun disabling_removes_the_options_that_are_set_and_keeps_the_radios() {
        val change = Steering.disable(both, both.groups.single())
        assertEquals(
            listOf(
                "delete wireless.default_radio0.ieee80211k",
                "delete wireless.default_radio0.bss_transition",
                "delete wireless.default_radio1.ieee80211k",
                "delete wireless.default_radio1.bss_transition",
            ),
            change.batch.script().lines(),
        )
        assertEquals(listOf("Stop helping devices on \"Home\" move between bands"), change.summary)
    }

    @Test
    fun disabling_an_unassisted_group_is_empty() {
        assertTrue(Steering.disable(single, single.groups.single()).isEmpty())
    }

    @Test
    fun usteer_status_is_read_from_the_router() = runTest {
        val fake = FakeSshExecutor()
        val client = SteeringClient(fake)
        fake.respond(SteeringClient.STATUS_COMMAND, "OWM_INSTALLED\nOWM_RUNNING\n")
        assertEquals(UsteerStatus(installed = true, running = true), client.usteerStatus())
        fake.respond(SteeringClient.STATUS_COMMAND, "OWM_INSTALLED\n")
        assertEquals(UsteerStatus(installed = true, running = false), client.usteerStatus())
        fake.respond(SteeringClient.STATUS_COMMAND, "")
        assertEquals(UsteerStatus(installed = false, running = false), client.usteerStatus())
    }

    @Test
    fun package_manager_is_detected() = runTest {
        val fake = FakeSshExecutor()
        val client = SteeringClient(fake)
        fake.respond(SteeringClient.PACKAGE_MANAGER_COMMAND, "apk\n")
        assertEquals(PackageManager.APK, client.packageManager())
        fake.respond(SteeringClient.PACKAGE_MANAGER_COMMAND, "opkg\n")
        assertEquals(PackageManager.OPKG, client.packageManager())
        fake.respond(SteeringClient.PACKAGE_MANAGER_COMMAND, "")
        assertEquals(PackageManager.NONE, client.packageManager())
    }

    @Test
    fun start_and_install_send_the_expected_commands() = runTest {
        val fake = FakeSshExecutor()
        val client = SteeringClient(fake)
        fake.respond("/etc/init.d/usteer enable && /etc/init.d/usteer start", "")
        assertTrue(client.startUsteer())
        fake.respond("apk add usteer", "")
        assertTrue(client.installUsteer(PackageManager.APK))
        fake.respond("opkg update && opkg install usteer", "", exitCode = 1)
        assertFalse(client.installUsteer(PackageManager.OPKG))
        assertFalse(client.installUsteer(PackageManager.NONE))
    }
}
