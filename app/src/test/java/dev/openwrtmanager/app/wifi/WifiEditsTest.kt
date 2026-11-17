package dev.openwrtmanager.app.wifi

import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.uci.parseUciConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiValidationTest {
    @Test
    fun ssid_must_be_one_to_thirty_two_bytes() {
        assertEquals(SsidError.EMPTY, validateSsid(""))
        assertNull(validateSsid("a"))
        assertNull(validateSsid("a".repeat(32)))
        assertEquals(SsidError.TOO_LONG, validateSsid("a".repeat(33)))
        assertNull(validateSsid("📶".repeat(8)))
        assertEquals("emoji are four bytes each", SsidError.TOO_LONG, validateSsid("📶".repeat(9)))
    }

    @Test
    fun key_must_be_eight_to_sixty_three_printable_ascii() {
        assertEquals(KeyError.TOO_SHORT, validateKey("1234567", Security.WPA2))
        assertNull(validateKey("12345678", Security.WPA2))
        assertNull(validateKey("a".repeat(63), Security.WPA3))
        assertEquals(KeyError.TOO_LONG, validateKey("a".repeat(64), Security.WPA2_WPA3))
        assertEquals(KeyError.NOT_ASCII, validateKey("pässwörd1", Security.WPA2))
        assertNull(validateKey("with space; 'quotes' \"too\"", Security.WPA2))
    }

    @Test
    fun open_networks_need_no_key() {
        assertNull(validateKey("", Security.OPEN))
    }
}

class WifiEditsTest {
    private val config = wifiConfigOf(parseUciConfig("wireless", Fixtures.read("uci_wireless.json")))
    private val home = config.groups.single()
    private val same = NetworkEdit(
        ssid = "Home", security = Security.WPA2_WPA3, key = "hunter22pass", hidden = false,
        isolate = false, enabled = true, radios = setOf("radio0", "radio1"),
    )

    private fun lines(edit: NetworkEdit) = WifiEdits.editGroup(config, home, edit).batch.script().lines().filter { it.isNotEmpty() }

    @Test
    fun unchanged_edit_is_empty() {
        val change = WifiEdits.editGroup(config, home, same)
        assertTrue(change.isEmpty())
        assertTrue(change.summary.isEmpty())
    }

    @Test
    fun rename_writes_one_line_per_radio() {
        assertEquals(
            listOf("set wireless.default_radio0.ssid='Home 5G'", "set wireless.default_radio1.ssid='Home 5G'"),
            lines(same.copy(ssid = "Home 5G")),
        )
        val change = WifiEdits.editGroup(config, home, same.copy(ssid = "Home 5G"))
        assertEquals(listOf("Rename \"Home\" to \"Home 5G\""), change.summary)
        assertTrue(change.mayDropConnection)
    }

    @Test
    fun password_change_is_quoted_and_never_summarised_in_clear() {
        val change = WifiEdits.editGroup(config, home, same.copy(key = "it's a new; pass"))
        assertTrue("set wireless.default_radio0.key='it'\\''s a new; pass'" in change.batch.script())
        assertEquals(listOf("Change the password of \"Home\""), change.summary)
        assertFalse(change.summary.joinToString().contains("new; pass"))
        assertFalse(change.toString().contains("new; pass"))
    }

    @Test
    fun switching_to_open_removes_the_key() {
        val script = WifiEdits.editGroup(config, home, same.copy(security = Security.OPEN, key = null)).batch.script()
        assertTrue("set wireless.default_radio0.encryption='none'" in script)
        assertTrue("delete wireless.default_radio0.key" in script)
        assertTrue("delete wireless.default_radio1.key" in script)
    }

    @Test
    fun wpa3_requires_protected_management_frames() {
        val script = WifiEdits.editGroup(config, home, same.copy(security = Security.WPA3)).batch.script()
        assertTrue("set wireless.default_radio0.encryption='sae'" in script)
        assertTrue("set wireless.default_radio0.ieee80211w='2'" in script)
    }

    @Test
    fun hide_isolate_and_disable_are_separate_lines_with_plain_summaries() {
        val change = WifiEdits.editGroup(config, home, same.copy(hidden = true, isolate = true, enabled = false))
        val script = change.batch.script()
        assertTrue("set wireless.default_radio0.hidden='1'" in script)
        assertTrue("set wireless.default_radio1.isolate='1'" in script)
        assertTrue("set wireless.default_radio1.disabled='1'" in script)
        assertEquals(
            listOf("Hide \"Home\" from network lists", "Stop devices on \"Home\" from seeing each other", "Turn off \"Home\""),
            change.summary,
        )
    }

    @Test
    fun removing_a_radio_deletes_its_section() {
        val change = WifiEdits.editGroup(config, home, same.copy(radios = setOf("radio0")))
        assertEquals(listOf("delete wireless.default_radio1"), change.batch.script().lines())
        assertEquals(listOf("Stop broadcasting \"Home\" on 5 GHz"), change.summary)
    }

    @Test
    fun adding_a_radio_creates_a_complete_section() {
        val single = wifiConfigOf(
            parseUciConfig("wireless", Fixtures.read("uci_wireless.json")).let { w ->
                w.copy(sections = w.sections.filterNot { it.name == "default_radio1" })
            },
        )
        val change = WifiEdits.editGroup(single, single.groups.single(), same)
        assertEquals(
            listOf(
                "set wireless.wifinet_radio1=wifi-iface",
                "set wireless.wifinet_radio1.device='radio1'",
                "set wireless.wifinet_radio1.mode='ap'",
                "set wireless.wifinet_radio1.network='lan'",
                "set wireless.wifinet_radio1.ssid='Home'",
                "set wireless.wifinet_radio1.encryption='sae-mixed'",
                "set wireless.wifinet_radio1.key='hunter22pass'",
                "set wireless.wifinet_radio1.ieee80211w='1'",
            ),
            change.batch.script().lines(),
        )
        assertEquals(listOf("Also broadcast \"Home\" on 5 GHz"), change.summary)
    }

    @Test
    fun add_network_creates_one_section_per_radio() {
        val edit = same.copy(ssid = "Second", key = "secondpass1", security = Security.WPA2, radios = setOf("radio0"))
        val change = WifiEdits.addNetwork(config, edit, network = "lan")
        val script = change.batch.script()
        assertTrue(script.startsWith("set wireless.wifinet_radio0=wifi-iface"))
        assertTrue("set wireless.wifinet_radio0.encryption='psk2'" in script)
        assertFalse("radio1" in script)
        assertEquals(listOf("Add network \"Second\" on 2.4 GHz"), change.summary)
    }

    @Test
    fun new_section_names_avoid_existing_ones() {
        val first = WifiEdits.addNetwork(config, same.copy(ssid = "A"), "lan")
        val taken = config.copy(sectionNames = config.sectionNames + "wifinet_radio0")
        val second = WifiEdits.addNetwork(taken, same.copy(ssid = "B"), "lan")
        assertTrue("wifinet_radio0=" in first.batch.script())
        assertTrue("wifinet_radio0_2=" in second.batch.script())
    }

    @Test
    fun delete_warns_when_it_is_the_last_network() {
        val change = WifiEdits.deleteGroup(config, home)
        assertEquals(listOf("delete wireless.default_radio0", "delete wireless.default_radio1"), change.batch.script().lines())
        assertEquals(listOf("Remove \"Home\"", "This device will have no Wi-Fi networks left"), change.summary)
    }

    @Test
    fun radio_edit_writes_only_differences() {
        val radio = config.radios[1]
        assertTrue(WifiEdits.editRadio(radio, "100", "VHT80", null, "PA", enabled = true).isEmpty())
        val change = WifiEdits.editRadio(radio, "36", "VHT80", 20, "PA", enabled = true)
        assertEquals(
            listOf("set wireless.radio1.channel='36'", "set wireless.radio1.txpower='20'"),
            change.batch.script().lines(),
        )
        assertEquals(listOf("Set the 5 GHz radio to channel 36", "Set the 5 GHz radio power to 20 dBm"), change.summary)
    }

    @Test
    fun radio_can_be_turned_off() {
        val change = WifiEdits.editRadio(config.radios[0], "1", "HE20", null, null, enabled = false)
        assertEquals(listOf("set wireless.radio0.disabled='1'"), change.batch.script().lines())
        assertEquals(listOf("Turn off the 2.4 GHz radio"), change.summary)
    }
}

class WifiValidationControlCharsTest {
    @Test
    fun control_characters_are_rejected_before_they_reach_the_router() {
        assertEquals(SsidError.INVALID_CHARACTER, validateSsid("Home\nNet"))
        assertEquals(SsidError.INVALID_CHARACTER, validateSsid("Home\u0000"))
        assertEquals(SsidError.INVALID_CHARACTER, validateSsid("Tab\there"))
        assertNull(validateSsid("Plain name"))
    }
}
