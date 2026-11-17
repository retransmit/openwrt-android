package dev.openwrtmanager.app.firewall

import dev.openwrtmanager.app.clients.Client
import dev.openwrtmanager.app.clients.ClientLink
import dev.openwrtmanager.app.clients.DeviceConfig
import dev.openwrtmanager.app.clients.Planned
import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import dev.openwrtmanager.app.uci.parseUciConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortForwardsTest {
    private fun load(name: String, file: String) = parseUciConfig(name, Fixtures.read(file))

    private val config = DeviceConfig(
        network = load("network", "uci_network.json"),
        dhcp = load("dhcp", "uci_dhcp.json"),
        firewall = load("firewall", "uci_firewall_full.json"),
        l3Devices = mapOf("lan" to "br-lan", "wan" to "wan"),
        firewallEnabled = true,
        dhcpv4Required = true,
    )

    private val console = Client("AA:BB:CC:DD:EE:FF", "Console", "192.168.1.50", ClientLink.WIRED, null, null, null, null, null, reserved = true, blocked = false)
    private val reservedConfig = config.copy(
        dhcp = UciConfig(
            "dhcp",
            config.dhcp.sections + UciSection("owm_host_aabbccddeeff", "host", false, 60, mapOf("name" to "Console", "mac" to "AA:BB:CC:DD:EE:FF", "ip" to "192.168.1.60"), emptyMap()),
        ),
    )

    private fun input(port: String = "25565", inside: String? = null, protocols: Set<String> = setOf("tcp"), target: Client = console, name: String = "Minecraft") =
        ForwardInput(name, protocols, port, inside, target)

    private fun ready(result: Planned<ForwardProblem>) = (result as Planned.Ready).change

    private fun withForward(port: String, proto: List<String>, enabled: Boolean = true) = config.copy(
        firewall = UciConfig(
            "firewall",
            config.firewall.sections + UciSection(
                "cfg0f0f0f", "redirect", true, 80,
                buildMap {
                    put("name", "Existing"); put("src", "wan"); put("src_dport", port); put("dest", "lan")
                    put("dest_ip", "192.168.1.9"); put("target", "DNAT"); if (!enabled) put("enabled", "0")
                },
                mapOf("proto" to proto),
            ),
        ),
    )

    @Test
    fun forward_writes_a_dnat_redirect() {
        val change = ready(PortForwards.add(reservedConfig, input(inside = "25566", protocols = setOf("tcp", "udp"))))
        assertEquals(
            listOf(
                "set firewall.owm_fwd_1=redirect",
                "set firewall.owm_fwd_1.name='Minecraft'",
                "set firewall.owm_fwd_1.src='wan'",
                "set firewall.owm_fwd_1.src_dport='25565'",
                "set firewall.owm_fwd_1.dest='lan'",
                "set firewall.owm_fwd_1.dest_ip='192.168.1.60'",
                "set firewall.owm_fwd_1.dest_port='25566'",
                "set firewall.owm_fwd_1.proto='tcp udp'",
                "set firewall.owm_fwd_1.target='DNAT'",
            ),
            change.batch.script().lines(),
        )
        assertEquals(listOf("Forward TCP and UDP port 25565 from the internet to Console (192.168.1.60) port 25566"), change.summary)
    }

    @Test
    fun inside_port_defaults_to_outside() {
        val lines = ready(PortForwards.add(reservedConfig, input())).batch.script().lines()
        assertTrue("set firewall.owm_fwd_1.dest_port='25565'" in lines)
        assertTrue("set firewall.owm_fwd_1.proto='tcp'" in lines)
    }

    @Test
    fun ranges_must_match() {
        assertTrue(PortForwards.add(reservedConfig, input(port = "6000-6010")) is Planned.Ready)
        assertTrue(PortForwards.add(reservedConfig, input(port = "6000-6010", inside = "7000-7010")) is Planned.Ready)
        assertEquals(Planned.Problem(ForwardProblem.RANGE_MISMATCH), PortForwards.add(reservedConfig, input(port = "6000-6010", inside = "7000")))
        assertEquals(Planned.Problem(ForwardProblem.RANGE_MISMATCH), PortForwards.add(reservedConfig, input(port = "6000-6010", inside = "7000-7020")))
    }

    @Test
    fun protocol_decides_overlap() {
        val tcpTaken = withForward("25000-26000", listOf("tcp"))
        assertEquals(Planned.Problem(ForwardProblem.CLASHES_WITH_FORWARD), PortForwards.add(tcpTaken, input()))
        assertTrue("UDP is free", PortForwards.add(tcpTaken, input(protocols = setOf("udp"))) is Planned.Ready)
        assertTrue("a disabled forward does not count", PortForwards.add(withForward("25565", listOf("tcp"), enabled = false), input()) is Planned.Ready)
        assertEquals(
            "both on an existing tcp udp forward",
            Planned.Problem(ForwardProblem.CLASHES_WITH_FORWARD),
            PortForwards.add(withForward("25565", listOf("tcp udp")), input(protocols = setOf("udp"))),
        )
    }

    @Test
    fun router_ports_open_on_wan_are_refused() {
        // The router accepts UDP 68 (DHCP renew) and UDP 500 (ISAKMP to lan is forward, not the router) from wan.
        assertEquals(Planned.Problem(ForwardProblem.CLASHES_WITH_ROUTER), PortForwards.add(reservedConfig, input(port = "68", protocols = setOf("udp"))))
        assertTrue(PortForwards.add(reservedConfig, input(port = "68", protocols = setOf("tcp"))) is Planned.Ready)
    }

    @Test
    fun unreserved_target_is_reserved_in_the_same_change() {
        val unreserved = console.copy(reserved = false)
        val change = ready(PortForwards.add(config, input(target = unreserved)))
        val lines = change.batch.script().lines()
        assertTrue("set dhcp.owm_host_aabbccddeeff=host" in lines)
        assertTrue("set dhcp.owm_host_aabbccddeeff.ip='192.168.1.50'" in lines)
        assertTrue("set firewall.owm_fwd_1.dest_ip='192.168.1.50'" in lines)
        assertEquals(setOf("firewall", "dhcp"), change.batch.configs)
        assertTrue(change.summary.contains("Always give Console the address 192.168.1.50"))
    }

    @Test
    fun reserved_target_uses_its_reserved_ip() {
        val lines = ready(PortForwards.add(reservedConfig, input())).batch.script().lines()
        assertTrue("set firewall.owm_fwd_1.dest_ip='192.168.1.60'" in lines)
        assertTrue(lines.none { it.startsWith("set dhcp.") })
    }

    @Test
    fun target_without_address_or_outside_lan_is_refused() {
        assertEquals(Planned.Problem(ForwardProblem.NO_TARGET_ADDRESS), PortForwards.add(config, input(target = console.copy(ip = null, reserved = false))))
        assertEquals(Planned.Problem(ForwardProblem.TARGET_OUTSIDE_LAN), PortForwards.add(config, input(target = console.copy(ip = "10.9.9.9", reserved = false))))
    }

    @Test
    fun edit_keeps_the_section() {
        val existing = firewallViewOf(withForward("8080", listOf("tcp")).firewall).forwards.single()
        val change = ready(PortForwards.edit(reservedConfig.copy(firewall = withForward("8080", listOf("tcp")).firewall), existing, input(port = "8081")))
        val lines = change.batch.script().lines()
        assertTrue("set firewall.cfg0f0f0f.src_dport='8081'" in lines)
        assertTrue("set firewall.cfg0f0f0f.dest_ip='192.168.1.60'" in lines)
        assertTrue("its own old port is not a clash", lines.none { it.contains("owm_fwd") })
    }

    @Test
    fun disable_and_remove() {
        val existing = firewallViewOf(withForward("8080", listOf("tcp")).firewall).forwards.single()
        assertEquals(listOf("set firewall.cfg0f0f0f.enabled='0'"), PortForwards.setEnabled(existing, false).batch.script().lines())
        assertEquals(listOf("delete firewall.cfg0f0f0f.enabled"), PortForwards.setEnabled(existing.copy(enabled = false), true).batch.script().lines())
        assertEquals(listOf("delete firewall.cfg0f0f0f"), PortForwards.remove(existing).batch.script().lines())
        assertEquals(listOf("Remove the port forward Existing"), PortForwards.remove(existing).summary)
    }

    @Test
    fun bad_ports_and_names() {
        for (bad in listOf("0", "65536", "abc", "", "10-5", "10-10", "1-2-3", " 80")) {
            assertEquals(bad, Planned.Problem(ForwardProblem.BAD_PORT), PortForwards.add(reservedConfig, input(port = bad)))
        }
        assertEquals(Planned.Problem(ForwardProblem.BAD_PORT), PortForwards.add(reservedConfig, input(inside = "99999")))
        assertEquals(Planned.Problem(ForwardProblem.BAD_NAME), PortForwards.add(reservedConfig, input(name = "")))
        assertEquals(Planned.Problem(ForwardProblem.BAD_NAME), PortForwards.add(reservedConfig, input(name = "x".repeat(65))))
        assertEquals(Planned.Problem(ForwardProblem.NO_PROTOCOL), PortForwards.add(reservedConfig, input(protocols = emptySet())))
    }

    @Test
    fun host_entry_without_a_usable_address_is_not_a_reservation() {
        for (ip in listOf(null, "ignore")) {
            val hostOnly = config.copy(
                dhcp = UciConfig(
                    "dhcp",
                    config.dhcp.sections + UciSection(
                        "cfg0e0e0e", "host", true, 60,
                        buildMap { put("name", "console"); put("mac", "AA:BB:CC:DD:EE:FF"); ip?.let { put("ip", it) } },
                        emptyMap(),
                    ),
                ),
            )
            assertEquals("ip=$ip", Planned.Problem(ForwardProblem.CANNOT_RESERVE), PortForwards.add(hostOnly, input()))
        }
    }

    @Test
    fun enabling_an_enabled_forward_is_empty() {
        val existing = firewallViewOf(withForward("8080", listOf("tcp")).firewall).forwards.single()
        assertTrue(PortForwards.setEnabled(existing, true).isEmpty())
    }

    @Test
    fun next_free_section_name() {
        val one = ready(PortForwards.add(reservedConfig, input()))
        val taken = reservedConfig.copy(firewall = UciConfig("firewall", reservedConfig.firewall.sections + UciSection("owm_fwd_1", "redirect", false, 90, mapOf("src_dport" to "1", "proto" to "udp"), emptyMap())))
        assertTrue(one.batch.script().contains("owm_fwd_1"))
        assertTrue(ready(PortForwards.add(taken, input())).batch.script().contains("owm_fwd_2"))
    }
}
