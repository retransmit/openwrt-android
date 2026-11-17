package dev.openwrtmanager.app.clients

import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.uci.UciBatch
import dev.openwrtmanager.app.uci.UciConfig

sealed interface ReservationProblem {
    data object BadName : ReservationProblem
    data object BadIp : ReservationProblem
    data object OutsideNetwork : ReservationProblem
    data object RouterAddress : ReservationProblem
    data object IpTaken : ReservationProblem
    data object MacTaken : ReservationProblem
}

/** Static DHCP leases: always the same address for one device. */
object Reservations {
    private val NAME = Regex("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

    fun list(dhcp: UciConfig): List<Reservation> = reservationsOf(dhcp)

    fun isValidName(name: String): Boolean = NAME.matches(name)

    /** A DHCP host name made from the device's name, or from its MAC when that leaves nothing. */
    fun proposeName(deviceName: String?, mac: String): String {
        val cleaned = deviceName.orEmpty()
            .replace(Regex("[^A-Za-z0-9]+"), "-")
            .trim('-')
            .take(63)
            .trimEnd('-')
        return cleaned.ifEmpty { "device-" + mac.replace(":", "").takeLast(6).lowercase() }
    }

    fun add(dhcp: UciConfig, network: UciConfig, mac: String, ip: String, name: String): Planned<ReservationProblem> {
        require(isMac(mac)) { "not a MAC address" }
        val target = normalMac(mac)
        check(dhcp, network, ip, name, ignoring = null)?.let { return Planned.Problem(it) }
        if (list(dhcp).any { it.mac == target }) return Planned.Problem(ReservationProblem.MacTaken)
        val section = "owm_host_" + target.replace(":", "").lowercase()
        val batch = UciBatch()
            .section("dhcp", section, "host")
            .set("dhcp", section, "name", name)
            .set("dhcp", section, "mac", target)
            .set("dhcp", section, "ip", ip)
        return Planned.Ready(ChangeSet.of("Reserve address", listOf("Always give $name the address $ip"), batch))
    }

    fun edit(dhcp: UciConfig, network: UciConfig, reservation: Reservation, ip: String, name: String): Planned<ReservationProblem> {
        check(dhcp, network, ip, name, ignoring = reservation.section)?.let { return Planned.Problem(it) }
        val batch = UciBatch()
        if (ip != reservation.ip) batch.set("dhcp", reservation.section, "ip", ip)
        if (name != reservation.name) batch.set("dhcp", reservation.section, "name", name)
        return Planned.Ready(ChangeSet.of("Change reservation", listOf("Always give $name the address $ip"), batch))
    }

    fun remove(reservation: Reservation): ChangeSet = ChangeSet.of(
        "Remove reservation",
        listOf("Stop reserving ${reservation.ip ?: "an address"} for ${reservation.name ?: reservation.mac}"),
        UciBatch().delete("dhcp", reservation.section),
    )

    private fun check(dhcp: UciConfig, network: UciConfig, ip: String, name: String, ignoring: String?): ReservationProblem? {
        if (!isValidName(name)) return ReservationProblem.BadName
        val address = ipv4ToLong(ip) ?: return ReservationProblem.BadIp
        val pool = DhcpPools.pools(dhcp, network).firstOrNull { pool ->
            val (base, size) = cidrRange(pool.subnet) ?: return@firstOrNull false
            address in base until base + size
        } ?: return ReservationProblem.OutsideNetwork
        val (base, size) = cidrRange(pool.subnet)!!
        if (address == base || address == base + size - 1) return ReservationProblem.BadIp
        val routerAddresses = network[pool.iface]?.list("ipaddr").orEmpty().map { it.substringBefore('/') }
        if (ip in routerAddresses) return ReservationProblem.RouterAddress
        if (list(dhcp).any { it.section != ignoring && it.ip == ip }) return ReservationProblem.IpTaken
        return null
    }
}
