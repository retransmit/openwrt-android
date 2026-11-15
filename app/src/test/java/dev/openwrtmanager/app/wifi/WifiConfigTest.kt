package dev.openwrtmanager.app.wifi

import dev.openwrtmanager.app.domain.Band
import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import dev.openwrtmanager.app.uci.parseUciConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiConfigTest {
    private val wireless = parseUciConfig("wireless", Fixtures.read("uci_wireless.json"))
    private val config = wifiConfigOf(wireless)

    private fun withOption(section: String, option: String, value: String?): UciConfig =
        wireless.copy(
            sections = wireless.sections.map {
                if (it.name != section) it
                else it.copy(options = if (value == null) it.options - option else it.options + (option to value))
            },
        )

    @Test
    fun radios_are_read_with_band_channel_and_width() {
        assertEquals(listOf("radio0", "radio1"), config.radios.map { it.name })
        assertEquals(listOf(Band.GHZ_2_4, Band.GHZ_5), config.radios.map { it.band })
        assertEquals("1", config.radios[0].channel)
        assertEquals("HE20", config.radios[0].htmode)
        assertEquals("PA", config.radios[1].country)
        assertNull(config.radios[0].country)
        assertFalse(config.radios[0].disabled)
    }

    @Test
    fun ifaces_are_read_with_their_settings() {
        val iface = config.ifaces.first()
        assertEquals("default_radio0", iface.section)
        assertEquals("radio0", iface.radio)
        assertEquals("Home", iface.ssid)
        assertEquals(Security.WPA2_WPA3, iface.security)
        assertEquals("hunter22pass", iface.key)
        assertEquals("lan", iface.network)
        assertTrue(iface.ieee80211k)
        assertTrue(iface.bssTransition)
        assertFalse(iface.ieee80211r)
        assertFalse(iface.hidden)
        assertFalse(iface.isolate)
    }

    @Test
    fun same_network_on_two_radios_is_one_group_with_assist() {
        val group = config.groups.single()
        assertEquals("Home", group.ssid)
        assertEquals(listOf(Band.GHZ_2_4, Band.GHZ_5), group.bands)
        assertEquals(2, group.ifaces.size)
        assertTrue(group.steeringAssist)
    }

    @Test
    fun different_key_or_network_splits_the_group() {
        assertEquals(2, wifiConfigOf(withOption("default_radio1", "key", "anotherpass")).groups.size)
        assertEquals(2, wifiConfigOf(withOption("default_radio1", "network", "guest")).groups.size)
    }

    @Test
    fun assist_needs_k_and_v_on_every_iface() {
        assertFalse(wifiConfigOf(withOption("default_radio1", "bss_transition", null)).groups.single().steeringAssist)
    }

    @Test
    fun single_band_group_has_no_assist() {
        val one = wireless.copy(sections = wireless.sections.filterNot { it.name == "default_radio1" })
        assertFalse(wifiConfigOf(one).groups.single().steeringAssist)
    }

    @Test
    fun security_maps_known_values_and_keeps_unknown_ones_raw() {
        assertEquals(Security.WPA2_WPA3, Security.fromUci("sae-mixed"))
        assertEquals(Security.WPA2, Security.fromUci("psk2"))
        assertEquals(Security.WPA2, Security.fromUci("psk-mixed+tkip+ccmp"))
        assertEquals(Security.WPA2, Security.fromUci("psk2+ccmp"))
        assertEquals(Security.WPA3, Security.fromUci("sae"))
        assertEquals(Security.OPEN, Security.fromUci("none"))
        assertEquals(Security.OPEN, Security.fromUci(null))
        assertNull(Security.fromUci("wep-open"))
        val wep = wifiConfigOf(withOption("default_radio0", "encryption", "wep-open")).ifaces.first()
        assertNull(wep.security)
        assertEquals("wep-open", wep.rawEncryption)
    }

    @Test
    fun only_access_point_ifaces_are_listed() {
        val sta = UciSection("wifinet9", "wifi-iface", false, 9, mapOf("device" to "radio0", "mode" to "sta", "ssid" to "Up"), emptyMap())
        assertEquals(2, wifiConfigOf(wireless.copy(sections = wireless.sections + sta)).ifaces.size)
    }

    @Test
    fun keys_never_appear_in_text_form() {
        assertFalse(config.ifaces.first().toString().contains("hunter22pass"))
        assertFalse(config.groups.single().toString().contains("hunter22pass"))
        assertFalse(config.toString().contains("hunter22pass"))
    }
}
