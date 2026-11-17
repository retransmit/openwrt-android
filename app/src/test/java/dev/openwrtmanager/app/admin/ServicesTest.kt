package dev.openwrtmanager.app.admin

import dev.openwrtmanager.app.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicesTest {
    private val services = parseServices(Fixtures.read("rc_list.json"))

    @Test
    fun services_are_parsed_with_state() {
        val dnsmasq = services.first { it.name == "dnsmasq" }
        assertEquals(Service("dnsmasq", enabled = true, running = true), dnsmasq)
        assertEquals("no daemon, so no running state", null, services.first { it.name == "boot" }.running)
        assertEquals(services.map { it.name }.sorted(), services.map { it.name })
        assertEquals(emptyList<Service>(), parseServices("not json"))
    }

    @Test
    fun firewall_has_no_running_state() {
        // fw4 loads its rules and exits; procd reports it as not running even while it protects.
        assertEquals(null, services.first { it.name == "firewall" }.running)
    }

    @Test
    fun protected_services_cannot_be_stopped() {
        for (name in listOf("dropbear", "network")) {
            assertFalse(actionAllowed(name, ServiceAction.STOP))
            assertFalse(actionAllowed(name, ServiceAction.DISABLE))
            assertTrue(actionAllowed(name, ServiceAction.RESTART))
            assertTrue(actionAllowed(name, ServiceAction.START))
        }
        assertTrue(actionAllowed("cron", ServiceAction.STOP))
    }

    @Test
    fun warnings_name_what_breaks() {
        assertEquals("Devices will lose names and new addresses until it runs again.", actionWarning("dnsmasq", ServiceAction.STOP))
        assertEquals("Your networks will be unprotected and guest isolation stops.", actionWarning("firewall", ServiceAction.DISABLE))
        assertEquals("The app reconnects after a few seconds.", actionWarning("network", ServiceAction.RESTART))
        assertNull(actionWarning("cron", ServiceAction.STOP))
    }

    @Test
    fun command_is_quoted_and_checked() {
        assertEquals("""ubus call rc init '{"name":"cron","action":"restart"}'""", serviceCommand("cron", ServiceAction.RESTART))
        assertNull(serviceCommand("cron; reboot", ServiceAction.STOP))
        assertNull(serviceCommand("", ServiceAction.STOP))
    }
}
