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
}
