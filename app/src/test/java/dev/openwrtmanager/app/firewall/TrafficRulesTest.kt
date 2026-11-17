package dev.openwrtmanager.app.firewall

import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.uci.parseUciConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficRulesTest {
    private val rules = firewallViewOf(parseUciConfig("firewall", Fixtures.read("uci_firewall_full.json"))).rules
    private val ping = rules.first { it.name == "Allow-Ping" }
    private val isakmp = rules.first { it.name == "Allow-ISAKMP" }

    @Test
    fun toggling_writes_enabled() {
        assertEquals(listOf("set firewall.${ping.section}.enabled='0'"), TrafficRules.setEnabled(ping, false).batch.script().lines())
        assertEquals(listOf("delete firewall.${ping.section}.enabled"), TrafficRules.setEnabled(ping.copy(enabled = false), true).batch.script().lines())
        assertEquals("nothing to do", true, TrafficRules.setEnabled(ping, true).isEmpty())
    }

    @Test
    fun removals_use_section_names() {
        val lines = TrafficRules.remove(listOf(ping, isakmp)).batch.script().lines()
        assertEquals(listOf("delete firewall.${isakmp.section}", "delete firewall.${ping.section}"), lines)
    }

    @Test
    fun summaries_name_the_rule() {
        assertEquals(listOf("Turn off Allow-Ping: Allows ping from wan to the router"), TrafficRules.setEnabled(ping, false).summary)
        assertEquals(
            listOf("Remove Allow-Ping: Allows ping from wan to the router", "Remove Allow-ISAKMP: Allows UDP port 500 from wan to lan"),
            TrafficRules.remove(listOf(ping, isakmp)).summary,
        )
    }
}
