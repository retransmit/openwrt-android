package dev.openwrtmanager.app.clients

import dev.openwrtmanager.app.domain.Band
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.wifi.configuredSubnets
import dev.openwrtmanager.app.wifi.networkOf

enum class ClientLink { WIFI, WIRED, OFFLINE }

/** One device as the Clients tab shows it. OFFLINE only ever appears for blocked devices. */
data class Client(
    val mac: String,
    val name: String?,
    val ip: String?,
    val link: ClientLink,
    val ssid: String?,
    val band: Band?,
    val ifname: String?,
    val signal: Int?,
    val connectedSeconds: Long?,
    val reserved: Boolean,
    val blocked: Boolean,
)

/** The config the client actions depend on. */
data class DeviceConfig(
    val network: UciConfig,
    val dhcp: UciConfig,
    val firewall: UciConfig,
    /** Interface name to its l3 device, from `network.interface dump`; empty entries while an interface is down. */
    val l3Devices: Map<String, String>,
    val firewallEnabled: Boolean,
    /** OpenWrt 25.x and snapshots serve a DHCP pool only with `dhcpv4 'server'`. */
    val dhcpv4Required: Boolean,
)

/** A static DHCP lease (`config host`). */
data class Reservation(val section: String, val name: String?, val mac: String, val ip: String?)

fun reservationsOf(dhcp: UciConfig): List<Reservation> = dhcp.ofType("host").flatMap { host ->
    // `mac` may be a list or several MACs separated by spaces.
    host.list("mac").flatMap { it.split(' ') }.filter(::isMac).map { Reservation(host.name, host.opt("name"), normalMac(it), host.opt("ip")) }
}

/**
 * Networks of the zone that faces the internet: the one holding `wan`. Masquerading alone does not
 * count, because VPN zones such as Tailscale masquerade too.
 */
private fun wanZoneNetworks(firewall: UciConfig): Set<String> =
    firewall.ofType("zone").map(::zoneNetworks).filter { "wan" in it }.flatten().toSet()

/** A zone's networks; old configs write them as one space-separated string. */
private fun zoneNetworks(zone: dev.openwrtmanager.app.uci.UciSection): List<String> =
    zone.list("network").flatMap { it.split(' ', '\t') }.filter { it.isNotEmpty() }

/** Devices on the internet side, by config and by what netifd reports, so they count even while WAN is down. */
fun wanDevices(config: DeviceConfig): Set<String> = wanZoneNetworks(config.firewall).flatMap { iface ->
    listOfNotNull(config.network[iface]?.opt("device"), config.l3Devices[iface])
}.toSet()

/**
 * Joins leases, Wi-Fi associations and neighbours by MAC. Wi-Fi clients first by signal, then wired
 * devices by name, then blocked devices that are not seen right now.
 */
fun mergeClients(readings: ClientReadings, config: DeviceConfig): List<Client> {
    val wan = wanDevices(config)
    val reservations = reservationsOf(config.dhcp).associateBy { it.mac }
    val blocked = BlockPlanner.blockedMacs(config.firewall).toSet()
    val leases = readings.leases.associateBy { normalMac(it.mac) }
    val networks = readings.wifi.filter { it.ifname != null }.associateBy { it.ifname }
    val associations = readings.associations
        .groupBy { normalMac(it.mac) }
        .mapValues { (_, list) -> list.maxByOrNull { it.signal ?: Int.MIN_VALUE }!! }
    val neighbours = readings.neighbours.filter { it.device !in wan }.groupBy { normalMac(it.mac) }.mapValues { it.value.first() }

    fun client(mac: String, link: ClientLink): Client {
        val association = associations[mac]
        val network = association?.let { networks[it.ifname] }
        return Client(
            mac = mac,
            name = reservations[mac]?.name ?: leases[mac]?.name ?: readings.hintNames[mac],
            ip = leases[mac]?.ip ?: neighbours[mac]?.ip ?: reservations[mac]?.ip?.takeIf { link == ClientLink.OFFLINE },
            link = link,
            ssid = network?.ssid,
            band = network?.band,
            ifname = association?.ifname,
            signal = association?.signal,
            connectedSeconds = association?.connectedSeconds,
            reserved = mac in reservations,
            blocked = mac in blocked,
        )
    }

    val byName = compareBy<Client, String?>(nullsLast(String.CASE_INSENSITIVE_ORDER)) { it.name }.thenBy { it.mac }
    val wifi = associations.keys.map { client(it, ClientLink.WIFI) }
        .sortedWith(compareByDescending<Client> { it.signal ?: Int.MIN_VALUE }.thenBy { it.mac })
    val wired = (neighbours.keys - associations.keys).map { client(it, ClientLink.WIRED) }.sortedWith(byName)
    val offline = (blocked - associations.keys - neighbours.keys).map { client(it, ClientLink.OFFLINE) }.sortedWith(byName)
    return wifi + wired + offline
}

data class ClientActions(val reconnect: Boolean, val reserve: Boolean, val block: Boolean)

/**
 * What can be done to [client] on this device, decided from config rather than from the internet
 * link being up: a main router with WAN down keeps Reserve and Block, an access point never offers
 * an action that would do nothing.
 */
fun actionsFor(client: Client, config: DeviceConfig): ClientActions {
    val reserve = client.ip != null && servingPool(client.ip, config) != null
    val wanZone = wanZoneNetworks(config.firewall).any { config.network[it]?.type == "interface" }
    // A client on a network that has its own gateway is bridged to another router: its traffic never
    // passes this device's forward chain, so a block here would do nothing.
    val routedHere = client.ip?.let { ip -> interfaceHolding(ip, config)?.opt("gateway") == null } ?: true
    return ClientActions(
        reconnect = client.link == ClientLink.WIFI && client.ifname != null,
        reserve = reserve,
        block = config.firewallEnabled && wanZone && routedHere,
    )
}

/** The interface whose configured subnet holds [ip]. */
private fun interfaceHolding(ip: String, config: DeviceConfig): dev.openwrtmanager.app.uci.UciSection? =
    config.network.ofType("interface").firstOrNull { section ->
        configuredSubnets(UciConfig("network", listOf(section))).any { cidr ->
            val bits = cidr.substringAfter('/').toIntOrNull() ?: return@any false
            bits < 32 && networkOf(ip, bits) == cidr
        }
    }

/** The interface whose active DHCP pool hands out [ip], if any. */
fun servingPool(ip: String, config: DeviceConfig): String? = config.dhcp.ofType("dhcp").firstNotNullOfOrNull { pool ->
    val iface = pool.opt("interface") ?: return@firstNotNullOfOrNull null
    if (pool.flag("ignore")) return@firstNotNullOfOrNull null
    val mode = pool.opt("dhcpv4")
    if (mode == "disabled" || (config.dhcpv4Required && mode != "server")) return@firstNotNullOfOrNull null
    val section = config.network[iface]?.takeIf { it.type == "interface" } ?: return@firstNotNullOfOrNull null
    val inside = configuredSubnets(UciConfig("network", listOf(section))).any { cidr ->
        val bits = cidr.substringAfter('/').toIntOrNull() ?: return@any false
        networkOf(ip, bits) == cidr
    }
    iface.takeIf { inside }
}
