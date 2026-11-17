package dev.openwrtmanager.app.clients

import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.uci.UciBatch
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.wifi.configuredSubnets

/** A DHCP pool that is switched on, with OpenWrt's defaults filled in. */
data class Pool(val section: String, val iface: String, val start: Int, val limit: Int, val leasetime: String, val subnet: String)

enum class PoolProblem { START_OUT_OF_RANGE, POOL_OUTSIDE_SUBNET, BAD_LEASE_TIME }

object DhcpPools {
    private val LEASE = Regex("^(\\d+)([smhdw]?)$")
    private const val MIN_LEASE_SECONDS = 120L

    fun pools(dhcp: UciConfig, network: UciConfig): List<Pool> = dhcp.ofType("dhcp").mapNotNull { section ->
        if (section.flag("ignore")) return@mapNotNull null
        val iface = section.opt("interface") ?: return@mapNotNull null
        val ifaceSection = network[iface]?.takeIf { it.type == "interface" } ?: return@mapNotNull null
        val subnet = configuredSubnets(UciConfig("network", listOf(ifaceSection))).firstOrNull() ?: return@mapNotNull null
        Pool(
            section = section.name,
            iface = iface,
            start = section.opt("start")?.toIntOrNull() ?: 100,
            limit = section.opt("limit")?.toIntOrNull() ?: 150,
            leasetime = section.opt("leasetime") ?: "12h",
            subnet = subnet,
        )
    }

    fun edit(pool: Pool, start: Int, limit: Int, leasetime: String): Planned<PoolProblem> {
        val (base, size) = cidrRange(pool.subnet) ?: return Planned.Problem(PoolProblem.POOL_OUTSIDE_SUBNET)
        val lastHost = size - 2
        if (start < 1 || start > minOf(254L, lastHost)) return Planned.Problem(PoolProblem.START_OUT_OF_RANGE)
        if (limit < 1 || start + limit - 1L > lastHost) return Planned.Problem(PoolProblem.POOL_OUTSIDE_SUBNET)
        if (!validLeaseTime(leasetime)) return Planned.Problem(PoolProblem.BAD_LEASE_TIME)

        val batch = UciBatch()
        if (start != pool.start) batch.set("dhcp", pool.section, "start", start.toString())
        if (limit != pool.limit) batch.set("dhcp", pool.section, "limit", limit.toString())
        if (leasetime != pool.leasetime) batch.set("dhcp", pool.section, "leasetime", leasetime)
        val summary = "Hand out addresses ${longToIpv4(base + start)} to ${longToIpv4(base + start + limit - 1)} on ${pool.iface}, for $leasetime"
        return Planned.Ready(ChangeSet.of("Change address pool", listOf(summary), batch))
    }

    /** dnsmasq lease times: seconds, or a number with s, m, h, d or w, or `infinite`; at least 2 minutes. */
    fun validLeaseTime(value: String): Boolean {
        if (value == "infinite") return true
        val match = LEASE.matchEntire(value) ?: return false
        val amount = match.groupValues[1].toLongOrNull() ?: return false
        val unit = when (match.groupValues[2]) { "", "s" -> 1L; "m" -> 60L; "h" -> 3600L; "d" -> 86_400L; else -> 604_800L }
        return amount * unit >= MIN_LEASE_SECONDS
    }
}
