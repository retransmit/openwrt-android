package dev.openwrtmanager.app.vlan

import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection

/** How one bridge port takes part in one VLAN. UNTAGGED is also the port's PVID. */
enum class PortRole { NONE, UNTAGGED, TAGGED }

/** One `bridge-vlan` section. [roles] has an entry for every bridge port. */
data class BridgeVlan(val section: String, val vid: Int, val roles: Map<String, PortRole>)

/**
 * The LAN bridge and its VLANs, as configured. With no `bridge-vlan` sections the bridge does not
 * filter VLANs at all and every port is simply on the home network.
 */
data class VlanLayout(
    val bridgeSection: String,
    val bridge: String,
    val ports: List<String>,
    /** Sorted by VLAN ID; empty when the bridge does not filter. */
    val vlans: List<BridgeVlan>,
    /** VLAN ID to the interface whose device is `<bridge>.<vid>`. */
    val interfaces: Map<Int, String>,
) {
    val filtering: Boolean get() = vlans.isNotEmpty()

    fun vlan(vid: Int): BridgeVlan? = vlans.firstOrNull { it.vid == vid }

    /** The VLAN this port is untagged in, if any. */
    fun untaggedIn(port: String): Int? = vlans.firstOrNull { it.roles[port] == PortRole.UNTAGGED }?.vid
}

/** True when some enabled Wi-Fi network puts devices on the home network. */
fun homeWifiOn(wireless: UciConfig): Boolean =
    wireless.ofType("wifi-iface").any { "lan" in it.list("network") && !it.flag("disabled") }

/** Reads one entry of a `bridge-vlan` ports list. A port with no suffix counts as tagged. */
fun parsePortRole(token: String): Pair<String, PortRole> {
    val port = token.substringBefore(':')
    val flags = if (':' in token) token.substringAfter(':') else ""
    return port to if ('u' in flags) PortRole.UNTAGGED else PortRole.TAGGED
}

/**
 * Finds the bridge behind [lanInterface] and reads its VLANs. Null when that interface is not on a
 * bridge with ports, which is the case on devices without a DSA switch.
 */
fun vlanLayoutOf(network: UciConfig, lanInterface: String = "lan"): VlanLayout? {
    val device = network[lanInterface]?.takeIf { it.type == "interface" }?.opt("device") ?: return null
    fun bridgeNamed(name: String): UciSection? =
        network.ofType("device").firstOrNull { it.opt("name") == name && it.opt("type") == "bridge" && it.list("ports").isNotEmpty() }
    val bridgeSection = bridgeNamed(device) ?: device.takeIf { '.' in it }?.let { bridgeNamed(it.substringBeforeLast('.')) } ?: return null
    val bridge = bridgeSection.opt("name")!!
    val ports = bridgeSection.list("ports")

    val vlans = network.ofType("bridge-vlan").filter { it.opt("device") == bridge }.mapNotNull { section ->
        val vid = section.opt("vlan")?.toIntOrNull() ?: return@mapNotNull null
        val listed = section.list("ports").associate(::parsePortRole)
        BridgeVlan(section.name, vid, ports.associateWith { listed[it] ?: PortRole.NONE })
    }.sortedBy { it.vid }

    val interfaces = network.ofType("interface").mapNotNull { iface ->
        val vid = iface.opt("device")?.takeIf { it.startsWith("$bridge.") }?.substringAfter("$bridge.")?.toIntOrNull()
        vid?.let { it to iface.name }
    }.toMap()

    return VlanLayout(bridgeSection.name, bridge, ports, vlans, interfaces)
}
