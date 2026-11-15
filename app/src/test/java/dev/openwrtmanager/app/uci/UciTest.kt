package dev.openwrtmanager.app.uci

import dev.openwrtmanager.app.router.RouterError
import dev.openwrtmanager.app.testing.FakeSshExecutor
import dev.openwrtmanager.app.testing.Fixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UciConfigTest {
    private val wireless = parseUciConfig("wireless", Fixtures.read("uci_wireless.json"))
    private val network = parseUciConfig("network", Fixtures.read("uci_network.json"))

    @Test
    fun sections_keep_router_order() {
        assertEquals(listOf("radio0", "default_radio0", "radio1", "default_radio1"), wireless.sections.map { it.name })
    }

    @Test
    fun options_and_types_are_readable() {
        assertEquals("5g", wireless["radio1"]!!.opt("band"))
        assertEquals("wifi-device", wireless["radio1"]!!.type)
        assertEquals(listOf("default_radio0", "default_radio1"), wireless.ofType("wifi-iface").map { it.name })
        assertNull(wireless["nope"])
        assertNull(wireless["radio1"]!!.opt("nope"))
    }

    @Test
    fun flags_read_uci_booleans() {
        val iface = wireless["default_radio0"]!!
        assertTrue(iface.flag("ieee80211k"))
        assertFalse(iface.flag("hidden"))
        assertTrue(iface.flag("missing", default = true))
    }

    @Test
    fun list_options_and_anonymous_sections() {
        val bridge = network.ofType("device").first()
        assertEquals(listOf("lan1", "lan2", "lan3", "lan4"), bridge.list("ports"))
        assertEquals("lan1", bridge.opt("ports"))
        assertTrue(bridge.anonymous)
        assertEquals(listOf("br-lan"), bridge.list("name"))
        assertFalse(network["lan"]!!.anonymous)
    }

    @Test(expected = RouterError.BadResponse::class)
    fun garbage_is_a_bad_response() {
        parseUciConfig("wireless", "<html>")
    }
}

class UciBatchTest {
    @Test
    fun script_quotes_values_and_tracks_configs() {
        val batch = UciBatch()
            .section("wireless", "guest_radio0", "wifi-iface")
            .set("wireless", "guest_radio0", "ssid", "Joe's \"Café\"; rm -rf / 📶")
            .addList("firewall", "guest", "network", "guest")
            .delete("dhcp", "old")
            .delete("wireless", "x", "key")
        val expected = listOf(
            "set wireless.guest_radio0=wifi-iface",
            "set wireless.guest_radio0.ssid='Joe'\\''s \"Café\"; rm -rf / 📶'",
            "add_list firewall.guest.network='guest'",
            "delete dhcp.old",
            "delete wireless.x.key",
        ).joinToString("\n")
        assertEquals(expected, batch.script())
        assertEquals(setOf("wireless", "firewall", "dhcp"), batch.configs)
        assertFalse(batch.isEmpty())
    }

    @Test
    fun new_batch_is_empty() {
        assertTrue(UciBatch().isEmpty())
        assertEquals("", UciBatch().script())
    }

    @Test
    fun identifiers_must_be_plain() {
        assertThrows(IllegalArgumentException::class.java) { UciBatch().set("wireless", "a b", "ssid", "x") }
        assertThrows(IllegalArgumentException::class.java) { UciBatch().set("wire.less", "a", "ssid", "x") }
        assertThrows(IllegalArgumentException::class.java) { UciBatch().set("wireless", "a", "ss'id", "x") }
        assertThrows(IllegalArgumentException::class.java) { UciBatch().section("wireless", "a", "wifi iface") }
        assertThrows(IllegalArgumentException::class.java) { UciBatch().delete("wireless", "a;b") }
    }

    @Test
    fun values_cannot_span_lines() {
        assertThrows(IllegalArgumentException::class.java) { UciBatch().set("wireless", "a", "ssid", "x\ny") }
        assertThrows(IllegalArgumentException::class.java) { UciBatch().addList("wireless", "a", "ssid", "x\u0000y") }
    }
}

class UciClientTest {
    private val fake = FakeSshExecutor()
    private val client = UciClient(fake)

    @Test
    fun load_sends_one_ubus_call_and_parses_it() = runTest {
        fake.respond("ubus call uci get '{\"config\":\"wireless\"}'", Fixtures.read("uci_wireless.json"))
        assertEquals(4, client.load("wireless").sections.size)
        assertEquals(1, fake.commands.size)
    }

    @Test
    fun missing_config_is_empty() = runTest {
        fake.respond("ubus call uci get '{\"config\":\"usteer\"}'", "", exitCode = 4, stderr = "Command failed: Not found")
        assertTrue(client.load("usteer").sections.isEmpty())
    }

    @Test(expected = RouterError.NotOpenWrt::class)
    fun ubus_missing_is_not_openwrt() = runTest {
        client.load("wireless")
    }

    @Test
    fun config_name_must_be_plain() = runTest {
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { client.load("x'; reboot") } }
    }

    @Test
    fun pending_changes_are_trimmed() = runTest {
        fake.respond("uci changes", "wireless.radio0.channel='6'\n")
        assertEquals("wireless.radio0.channel='6'", client.pendingChanges())
    }
}
