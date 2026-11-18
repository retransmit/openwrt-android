package dev.openwrtmanager.app.ui.apply

import dev.openwrtmanager.app.apply.ApplyOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplyMessagesTest {
    @Test
    fun every_outcome_has_its_own_title() {
        val titles = listOf(
            ApplyOutcome.Applied,
            ApplyOutcome.RolledBack,
            ApplyOutcome.Rejected("x"),
            ApplyOutcome.ForeignChanges("y"),
            ApplyOutcome.Unknown(IllegalStateException()),
        ).map { outcomeMessage(it).title }
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun unknown_never_claims_success_or_failure() {
        val message = outcomeMessage(ApplyOutcome.Unknown(IllegalStateException()))
        assertFalse(message.title.contains("Applied"))
        assertTrue(message.detail.contains("Check the router"))
    }

    @Test
    fun passwords_are_hidden_in_exact_changes() {
        val raw = "wireless.guest_radio0.ssid='Home-Guest'\nwireless.guest_radio0.key='guest pass 1'\nwireless.x.sae_password='s3cret'"
        val shown = maskSecrets(raw)
        assertFalse(shown.contains("guest pass 1"))
        assertFalse(shown.contains("s3cret"))
        assertTrue(shown.contains("wireless.guest_radio0.ssid='Home-Guest'"))
        assertTrue(shown.contains("wireless.guest_radio0.key='(hidden)'"))
    }

    @Test
    fun other_kinds_of_secret_are_hidden_too() {
        val raw = listOf(
            "network.wg0.private_key='AAAA'",
            "network.peer.preshared_key='BBBB'",
            "wireless.x.auth_secret='CCCC'",
            "wireless.x.key1='DDDD'",
            "system.x.password='EEEE'",
            "network.lan.ipaddr='192.168.1.1'",
        ).joinToString("\n")
        val shown = maskSecrets(raw)
        for (secret in listOf("AAAA", "BBBB", "CCCC", "DDDD", "EEEE")) assertFalse(secret, shown.contains(secret))
        assertTrue(shown.contains("network.lan.ipaddr='192.168.1.1'"))
    }

    @Test
    fun secret_list_additions_are_hidden() {
        // `uci changes` prints list entries as option+='value'.
        val shown = maskSecrets("wireless.x.psk+='FFFF'\nsystem.ntp.server+='pool.ntp.org'")
        assertFalse(shown.contains("FFFF"))
        assertTrue(shown.contains("wireless.x.psk+='(hidden)'"))
        assertTrue(shown.contains("system.ntp.server+='pool.ntp.org'"))
    }
}
