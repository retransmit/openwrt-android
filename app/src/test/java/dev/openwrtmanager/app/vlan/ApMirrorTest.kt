package dev.openwrtmanager.app.vlan

import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.testing.accessPointSnapshot
import dev.openwrtmanager.app.uci.UciSection
import dev.openwrtmanager.app.uci.parseUciConfig
import dev.openwrtmanager.app.wifi.Security
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApMirrorTest {
    private fun iface(name: String, network: String, ssid: String, encryption: String, key: String?, disabled: Boolean = false) =
        UciSection(
            name, "wifi-iface", false, 50,
            buildMap {
                put("device", "radio0"); put("mode", "ap"); put("network", network); put("ssid", ssid); put("encryption", encryption)
                key?.let { put("key", it) }
                if (disabled) put("disabled", "1")
            },
            emptyMap(),
        )

    private val routerNetwork = parseUciConfig("network", Fixtures.read("uci_network_vlans.json")).let { n ->
        n.copy(
            sections = n.sections +
                UciSection("iot", "interface", false, 60, mapOf("proto" to "static", "device" to "br-lan.20"), emptyMap()) +
                UciSection("vlan_iot", "bridge-vlan", false, 61, mapOf("device" to "br-lan", "vlan" to "20"), emptyMap()) +
                UciSection("cams", "interface", false, 62, mapOf("proto" to "static", "device" to "br-lan.30"), emptyMap()),
        )
    }
    private val routerWireless = parseUciConfig("wireless", Fixtures.read("uci_wireless.json")).let { w ->
        w.copy(
            sections = w.sections +
                iface("guest_radio0", "guest", "Home-Guest", "sae-mixed", "guest secret 1") +
                iface("guest_radio1", "guest", "Home-Guest", "sae-mixed", "guest secret 1") +
                iface("iot_radio0", "iot", "Home-IoT", "psk2", "iot secret 12"),
        )
    }

    private val guest = SourceNetwork("guest", 10, "Home-Guest", Security.WPA2_WPA3, "guest secret 1")
    private val iot = SourceNetwork("iot", 20, "Home-IoT", Security.WPA2, "iot secret 12")
    private val ap = accessPointSnapshot()

    private fun ready(vararg sources: SourceNetwork, uplink: String = "wan") = ApMirror.plan(ap, sources.toList(), uplink) as MirrorPlan.Ready

    @Test
    fun guest_and_iot_are_read_from_the_router() {
        assertEquals(listOf(guest, iot), sourceNetworksOf(routerNetwork, routerWireless))
    }

    @Test
    fun disabled_wifi_is_not_a_source() {
        val wireless = routerWireless.copy(
            sections = routerWireless.sections.map { if (it.name.startsWith("guest_")) it.copy(options = it.options + ("disabled" to "1")) else it },
        )
        assertEquals(listOf(iot), sourceNetworksOf(routerNetwork, wireless))
    }

    @Test
    fun mirror_tags_the_uplink_and_adds_unmanaged_interfaces() {
        val change = ready(guest).change
        val lines = change.batch.script().lines()
        assertTrue("set network.vlan_lan=bridge-vlan" in lines)
        assertTrue("add_list network.vlan_lan.ports='wan:u*'" in lines)
        assertTrue("set network.lan.device='br-lan.1'" in lines)
        assertTrue("set network.vlan_guest.vlan='10'" in lines)
        assertTrue("add_list network.vlan_guest.ports='wan:t'" in lines)
        assertTrue("set network.guest=interface" in lines)
        assertTrue("set network.guest.proto='none'" in lines)
        assertTrue("set network.guest.device='br-lan.10'" in lines)
        for (radio in listOf("radio0", "radio1")) {
            assertTrue("set wireless.guest_$radio.network='guest'" in lines)
            assertTrue("set wireless.guest_$radio.ssid='Home-Guest'" in lines)
            assertTrue("set wireless.guest_$radio.key='guest secret 1'" in lines)
            assertTrue("set wireless.guest_$radio.isolate='1'" in lines)
        }
        assertEquals(setOf("network", "wireless"), change.batch.configs)
        assertTrue(change.checkInterfaces.isEmpty())
        assertTrue(change.mayDropConnection)
    }

    @Test
    fun two_networks_share_one_uplink() {
        val lines = ready(guest, iot).change.batch.script().lines()
        assertTrue("add_list network.vlan_guest.ports='wan:t'" in lines)
        assertTrue("add_list network.vlan_iot.ports='wan:t'" in lines)
        assertTrue("set network.iot.device='br-lan.20'" in lines)
    }

    @Test
    fun device_with_its_own_uplink_is_blocked() {
        assertEquals(MirrorPlan.Blocked(MirrorBlock.NOT_AN_ACCESS_POINT), ApMirror.plan(ap.copy(hasUplink = true), listOf(guest), "wan"))
    }

    @Test
    fun device_without_a_dsa_bridge_is_blocked() {
        val noDsa = ap.copy(network = ap.network.copy(sections = ap.network.sections.map { if (it.type == "device") it.copy(lists = it.lists - "ports") else it }))
        assertEquals(MirrorPlan.Blocked(MirrorBlock.NOT_DSA), ApMirror.plan(noDsa, listOf(guest), "wan"))
    }

    @Test
    fun uplink_must_be_a_bridge_port() {
        assertEquals(MirrorPlan.Blocked(MirrorBlock.UPLINK_NOT_A_PORT), ApMirror.plan(ap, listOf(guest), "eth9"))
    }

    @Test
    fun same_vid_already_on_the_ap_is_blocked() {
        val withVlans = ap.copy(
            network = ap.network.copy(
                sections = ap.network.sections + UciSection("vlan_x", "bridge-vlan", false, 70, mapOf("device" to "br-lan", "vlan" to "10"), emptyMap()),
            ),
        )
        assertEquals(MirrorPlan.Blocked(MirrorBlock.VID_TAKEN), ApMirror.plan(withVlans, listOf(guest), "wan"))
    }

    @Test
    fun same_name_already_on_the_ap_is_blocked() {
        val taken = ap.copy(
            network = ap.network.copy(sections = ap.network.sections + UciSection("guest", "interface", false, 70, mapOf("proto" to "none"), emptyMap())),
        )
        assertEquals(MirrorPlan.Blocked(MirrorBlock.NAME_TAKEN), ApMirror.plan(taken, listOf(guest), "wan"))
    }

    @Test
    fun nothing_to_add_or_no_radio_is_blocked() {
        assertEquals(MirrorPlan.Blocked(MirrorBlock.NOTHING_CHOSEN), ApMirror.plan(ap, emptyList(), "wan"))
        val noRadios = ap.copy(wireless = ap.wireless.copy(sections = ap.wireless.sections.filterNot { it.type == "wifi-device" }))
        assertEquals(MirrorPlan.Blocked(MirrorBlock.NO_RADIO), ApMirror.plan(noRadios, listOf(guest), "wan"))
    }

    @Test
    fun summary_never_shows_the_key() {
        val change = ready(guest, iot).change
        val text = change.summary.joinToString("\n")
        assertFalse("guest secret 1" in text)
        assertFalse("iot secret 12" in text)
        assertTrue("Add Wi-Fi network \"Home-Guest\" on VLAN 10" in change.summary)
        assertTrue("wan carries guest to an access point" !in change.summary)
        assertFalse(guest.toString().contains("guest secret 1"))
    }
}
