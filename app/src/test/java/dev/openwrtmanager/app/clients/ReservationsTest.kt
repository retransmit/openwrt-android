package dev.openwrtmanager.app.clients

import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import dev.openwrtmanager.app.uci.parseUciConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationsTest {
    private val network = parseUciConfig("network", Fixtures.read("uci_network.json"))
    private val dhcp = parseUciConfig("dhcp", Fixtures.read("uci_dhcp.json"))
    private val tv = "AA:BB:CC:DD:EE:FF"

    private fun withHost(name: String, mac: String, ip: String, section: String = "cfg0a1b2c", anonymous: Boolean = true) =
        UciConfig("dhcp", dhcp.sections + UciSection(section, "host", anonymous, 60, mapOf("name" to name, "mac" to mac, "ip" to ip), emptyMap()))

    private fun ready(result: Planned<ReservationProblem>) = (result as Planned.Ready).change

    @Test
    fun reservation_writes_a_named_host_section() {
        val change = ready(Reservations.add(dhcp, network, "aa:bb:cc:dd:ee:ff", "192.168.1.50", "Living-Room-TV"))
        assertEquals(
            listOf(
                "set dhcp.owm_host_aabbccddeeff=host",
                "set dhcp.owm_host_aabbccddeeff.name='Living-Room-TV'",
                "set dhcp.owm_host_aabbccddeeff.mac='AA:BB:CC:DD:EE:FF'",
                "set dhcp.owm_host_aabbccddeeff.ip='192.168.1.50'",
            ),
            change.batch.script().lines(),
        )
        assertEquals(listOf("Always give Living-Room-TV the address 192.168.1.50"), change.summary)
        assertEquals(false, change.mayDropConnection)
    }

    @Test
    fun ip_must_be_in_the_served_subnet() {
        assertEquals(Planned.Problem(ReservationProblem.OutsideNetwork), Reservations.add(dhcp, network, tv, "10.0.0.5", "tv"))
        assertEquals(Planned.Problem(ReservationProblem.BadIp), Reservations.add(dhcp, network, tv, "192.168.1.300", "tv"))
        assertEquals(Planned.Problem(ReservationProblem.BadIp), Reservations.add(dhcp, network, tv, "192.168.1.255", "tv"))
        assertEquals(Planned.Problem(ReservationProblem.BadIp), Reservations.add(dhcp, network, tv, "", "tv"))
    }

    @Test
    fun only_canonical_addresses_are_accepted() {
        for (odd in listOf("192.168.1.050", "192.168.1.01", "192.168.001.50", "١٩٢.168.1.50", " 192.168.1.50")) {
            assertEquals(odd, Planned.Problem(ReservationProblem.BadIp), Reservations.add(dhcp, network, tv, odd, "tv"))
        }
    }

    @Test
    fun router_address_is_refused() {
        assertEquals(Planned.Problem(ReservationProblem.RouterAddress), Reservations.add(dhcp, network, tv, "192.168.1.1", "tv"))
    }

    @Test
    fun ip_or_mac_used_by_another_reservation_is_refused() {
        val taken = withHost("printer", "11:22:33:44:55:66", "192.168.1.50")
        assertEquals(Planned.Problem(ReservationProblem.IpTaken), Reservations.add(taken, network, tv, "192.168.1.50", "tv"))
        assertEquals(Planned.Problem(ReservationProblem.MacTaken), Reservations.add(taken, network, "11:22:33:44:55:66", "192.168.1.51", "tv"))
    }

    @Test
    fun bad_names_are_refused() {
        for (bad in listOf("", "-tv", "tv-", "living room", "a".repeat(64), "tv_1")) {
            assertEquals(bad, Planned.Problem(ReservationProblem.BadName), Reservations.add(dhcp, network, tv, "192.168.1.50", bad))
        }
    }

    @Test
    fun proposed_names_are_valid_dhcp_names() {
        assertEquals("Living-Room-TV", Reservations.proposeName("Living Room TV", tv))
        assertEquals("Pixel-9-Pro", Reservations.proposeName("Pixel 9 Pro", tv))
        assertEquals("device-ddeeff", Reservations.proposeName(null, tv))
        assertEquals("device-ddeeff", Reservations.proposeName("*", tv))
        assertEquals("Caf", Reservations.proposeName("Café", tv))
        assertEquals("device-ddeeff", Reservations.proposeName("日本", tv))
        assertEquals(63, Reservations.proposeName("x".repeat(70), tv).length)
        assertEquals("a-b", Reservations.proposeName("--a  _b--", tv))
        for (name in listOf("Living Room TV", "日本", "x".repeat(70), "--a  _b--")) {
            assertTrue(name, Reservations.isValidName(Reservations.proposeName(name, tv)))
        }
    }

    @Test
    fun anonymous_reservations_can_be_edited_and_removed() {
        val existing = withHost("printer", "11:22:33:44:55:66", "192.168.1.50")
        val reservation = Reservations.list(existing).single()
        assertEquals(Reservation("cfg0a1b2c", "printer", "11:22:33:44:55:66", "192.168.1.50"), reservation)
        val edit = ready(Reservations.edit(existing, network, reservation, "192.168.1.60", "laser"))
        assertEquals(
            listOf("set dhcp.cfg0a1b2c.ip='192.168.1.60'", "set dhcp.cfg0a1b2c.name='laser'"),
            edit.batch.script().lines(),
        )
        assertEquals("keeping its own address is fine", true, Reservations.edit(existing, network, reservation, "192.168.1.50", "printer") is Planned.Ready)
        val remove = Reservations.remove(reservation)
        assertEquals(listOf("delete dhcp.cfg0a1b2c"), remove.batch.script().lines())
        assertEquals(listOf("Stop reserving 192.168.1.50 for printer"), remove.summary)
    }
}

class DhcpPoolsTest {
    private val network = parseUciConfig("network", Fixtures.read("uci_network.json"))
    private val dhcp = parseUciConfig("dhcp", Fixtures.read("uci_dhcp.json"))
    private val lan = DhcpPools.pools(dhcp, network).single()

    @Test
    fun active_pools_with_defaults() {
        assertEquals(Pool("lan", "lan", 100, 150, "12h", "192.168.1.0/24"), lan)
    }

    @Test
    fun pool_edit_writes_only_what_changed() {
        val change = (DhcpPools.edit(lan, 100, 100, "12h") as Planned.Ready).change
        assertEquals(listOf("set dhcp.lan.limit='100'"), change.batch.script().lines())
        assertEquals(listOf("Hand out addresses 192.168.1.100 to 192.168.1.199 on lan, for 12h"), change.summary)
        assertTrue((DhcpPools.edit(lan, 100, 150, "12h") as Planned.Ready).change.isEmpty())
    }

    @Test
    fun pool_must_stay_inside_the_subnet() {
        assertEquals(Planned.Problem(PoolProblem.START_OUT_OF_RANGE), DhcpPools.edit(lan, 0, 10, "12h"))
        assertEquals(Planned.Problem(PoolProblem.START_OUT_OF_RANGE), DhcpPools.edit(lan, 255, 1, "12h"))
        assertEquals(Planned.Problem(PoolProblem.POOL_OUTSIDE_SUBNET), DhcpPools.edit(lan, 200, 60, "12h"))
        assertEquals(Planned.Problem(PoolProblem.POOL_OUTSIDE_SUBNET), DhcpPools.edit(lan, 100, 0, "12h"))
        assertTrue(DhcpPools.edit(lan, 200, 54, "12h") is Planned.Ready)
    }

    @Test
    fun lease_times_are_checked() {
        for (ok in listOf("12h", "30m", "1d", "infinite", "2m", "3600")) assertTrue(ok, DhcpPools.edit(lan, 100, 150, ok) is Planned.Ready)
        for (bad in listOf("1m", "abc", "", "119", "12 h")) {
            assertEquals(bad, Planned.Problem(PoolProblem.BAD_LEASE_TIME), DhcpPools.edit(lan, 100, 150, bad))
        }
    }

    @Test
    fun ignored_pools_are_not_listed() {
        assertEquals(listOf("lan"), DhcpPools.pools(dhcp, network).map { it.iface })
    }
}
