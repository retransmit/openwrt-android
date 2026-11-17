package dev.openwrtmanager.app.wifi

import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.router.RouterClient
import dev.openwrtmanager.app.uci.UciBatch
import dev.openwrtmanager.app.uci.UciClient
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import dev.openwrtmanager.app.vlan.PortRole
import dev.openwrtmanager.app.vlan.VlanPlanner
import dev.openwrtmanager.app.vlan.homeWifiOn
import dev.openwrtmanager.app.vlan.vlanLayoutOf

enum class WizardKind { GUEST, IOT, CUSTOM }

data class WizardInput(
    val kind: WizardKind,
    val ssid: String,
    val security: Security,
    val key: String?,
    /** Radios to put the Wi-Fi on. CUSTOM networks may have none, then no Wi-Fi is created. */
    val radios: Set<String>,
    /** IoT only: whether devices on the home network may open connections to IoT devices. */
    val lanCanReachIot: Boolean = true,
    /** Bridge ports that carry the network, on a router with a DSA bridge. */
    val ports: Map<String, PortRole> = emptyMap(),
    /** CUSTOM only: the network's name, also used for its firewall zone. */
    val customName: String? = null,
    /** VLAN ID; null picks 10 for guest, 20 for IoT, 30 for others, or the next free one. */
    val vid: Int? = null,
) {
    override fun toString(): String = "WizardInput($kind, $ssid, $security, radios=$radios)"
}

/** Everything the wizard needs to know about a device, read in one go. */
data class DeviceSnapshot(
    val wireless: UciConfig,
    val network: UciConfig,
    val firewall: UciConfig,
    val dhcp: UciConfig,
    val usedSubnets: List<String>,
    val hasUplink: Boolean,
)

suspend fun loadSnapshot(router: RouterClient, uci: UciClient): DeviceSnapshot {
    val network = uci.load("network")
    return DeviceSnapshot(
    wireless = uci.load("wireless"),
    network = network,
    firewall = uci.load("firewall"),
    dhcp = uci.load("dhcp"),
    usedSubnets = (router.usedSubnets() + configuredSubnets(network)).distinct(),
    hasUplink = router.uplinkDevice() != null,
    )
}

enum class BlockReason { NO_UPLINK, NO_WAN_ZONE, NO_FREE_SUBNET, NO_RADIO, BAD_NAME, VLAN_PROBLEM }

sealed interface WizardPlan {
    /** [vid] is null on a device without a DSA bridge, where the network gets its own bridge. */
    data class Ready(val change: ChangeSet, val networkName: String, val subnet: String, val vid: Int? = null) : WizardPlan
    data class Blocked(val reason: BlockReason) : WizardPlan
}

/**
 * Builds a separate, isolated Wi-Fi network in one change: its own bridge and address range, a DHCP
 * pool, a firewall zone that may reach the internet but never the home network, and the SSIDs.
 */
object NetworkWizard {
    private val PROTECTED = setOf("lan", "wan", "wan6", "loopback")

    fun plan(snapshot: DeviceSnapshot, input: WizardInput): WizardPlan {
        if (!snapshot.hasUplink) return WizardPlan.Blocked(BlockReason.NO_UPLINK)
        val radios = snapshot.wireless.ofType("wifi-device").map { it.name }.filter { it in input.radios }
        if (radios.isEmpty() && (input.kind != WizardKind.CUSTOM || input.radios.isNotEmpty())) {
            return WizardPlan.Blocked(BlockReason.NO_RADIO)
        }
        val wanZone = zoneHolding(snapshot.firewall, "wan") ?: return WizardPlan.Blocked(BlockReason.NO_WAN_ZONE)
        val name = when (input.kind) {
            WizardKind.GUEST -> freeNetworkName(snapshot, "guest")
            WizardKind.IOT -> freeNetworkName(snapshot, "iot")
            WizardKind.CUSTOM -> input.customName?.takeIf { CUSTOM_NAME.matches(it) && it !in PROTECTED && !taken(snapshot, it) }
                ?: return WizardPlan.Blocked(BlockReason.BAD_NAME)
        }
        val batch = UciBatch()

        // On a DSA router the network is a VLAN on the LAN bridge; elsewhere it gets a bridge of its own.
        val before = vlanLayoutOf(snapshot.network)
        var vlanSummary = emptyList<String>()
        var vid: Int? = null
        val device = if (before != null) {
            var layout = VlanPlanner.enableFiltering(before, snapshot.network, batch)
            val preferred = when (input.kind) { WizardKind.GUEST -> 10; WizardKind.IOT -> 20; WizardKind.CUSTOM -> 30 }
            val chosen = input.vid ?: VlanPlanner.nextFreeVid(layout, preferred)
            if (VlanPlanner.checkNewId(layout, chosen) != null) return WizardPlan.Blocked(BlockReason.VLAN_PROBLEM)
            layout = VlanPlanner.addVlan(layout, batch, chosen, name, input.ports)
            if (VlanPlanner.validate(layout, homeWifiOn(snapshot.wireless)).isNotEmpty()) return WizardPlan.Blocked(BlockReason.VLAN_PROBLEM)
            vlanSummary = VlanPlanner.summary(before, layout)
            vid = chosen
            "${layout.bridge}.$chosen"
        } else {
            batch.section("network", "br_$name", "device")
            batch.set("network", "br_$name", "type", "bridge")
            batch.set("network", "br_$name", "name", "br-$name")
            batch.set("network", "br_$name", "bridge_empty", "1")
            "br-$name"
        }
        val preferredSubnet = vid?.takeIf { it in 2..254 }?.let { "192.168.$it.0/24" }
        val subnet = pickSubnet(snapshot.usedSubnets, preferredSubnet) ?: return WizardPlan.Blocked(BlockReason.NO_FREE_SUBNET)
        val router = subnet.substringBefore('/').substringBeforeLast('.') + ".1"

        batch.section("network", name, "interface")
        batch.set("network", name, "proto", "static")
        batch.set("network", name, "device", device)
        batch.set("network", name, "ipaddr", router)
        batch.set("network", name, "netmask", "255.255.255.0")

        batch.section("dhcp", name, "dhcp")
        batch.set("dhcp", name, "interface", name)
        batch.set("dhcp", name, "start", "100")
        batch.set("dhcp", name, "limit", "150")
        batch.set("dhcp", name, "leasetime", if (input.kind == WizardKind.GUEST) "1h" else "12h")
        // Newer OpenWrt (25.x and snapshots) treats a pool as off unless this is set; older releases ignore it.
        batch.set("dhcp", name, "dhcpv4", "server")

        batch.section("firewall", name, "zone")
        batch.set("firewall", name, "name", name)
        batch.addList("firewall", name, "network", name)
        // REJECT on input keeps the new network away from the router's own services (SSH, web, ubus).
        batch.set("firewall", name, "input", "REJECT")
        batch.set("firewall", name, "output", "ACCEPT")
        batch.set("firewall", name, "forward", "REJECT")
        batch.section("firewall", "${name}_wan", "forwarding")
        batch.set("firewall", "${name}_wan", "src", name)
        batch.set("firewall", "${name}_wan", "dest", wanZone)
        // Without these two, clients could not get an address or resolve names.
        allow(batch, "${name}_dns", "Allow-DNS-$name", name, "53", "tcp udp")
        allow(batch, "${name}_dhcp", "Allow-DHCP-$name", name, "67", "udp")
        val lanZone = zoneHolding(snapshot.firewall, "lan")
        val homeCanReach = input.kind == WizardKind.IOT && input.lanCanReachIot && lanZone != null
        if (homeCanReach) {
            batch.section("firewall", "lan_$name", "forwarding")
            batch.set("firewall", "lan_$name", "src", lanZone)
            batch.set("firewall", "lan_$name", "dest", name)
        }

        val edit = NetworkEdit(input.ssid, input.security, input.key, hidden = false, isolate = true, enabled = true, radios = radios.toSet())
        for (radio in radios) WifiEdits.newIface(batch, "${name}_$radio", radio, edit, name)

        val wifi = wifiConfigOf(snapshot.wireless)
        val bands = radios.map { wifi.radio(it)?.band?.label() ?: it }.distinct().joinToString(" and ")
        val summary = vlanSummary.toMutableList()
        summary += if (radios.isEmpty()) "Create network \"$name\"" else "Create Wi-Fi network \"${input.ssid}\" on $bands"
        summary += "Give it its own address range, $subnet"
        vid?.let { summary += "Put it on VLAN $it" }
        if (input.kind == WizardKind.GUEST) {
            summary += "Devices on it can reach the internet, but not your home network or this router's settings"
        } else {
            summary += "Devices on it can reach the internet, but cannot start connections to your home network"
            summary += if (homeCanReach) "Your home devices can reach devices on it" else "Your home devices cannot reach devices on it"
        }
        // Isolation is per radio: devices on different bands of the same network still share its bridge.
        if (radios.isNotEmpty()) {
            summary += if (radios.size == 1) "Devices on it cannot see each other" else "Devices on the same band cannot see each other"
        }
        val title = when (input.kind) {
            WizardKind.GUEST -> "Create guest network"
            WizardKind.IOT -> "Create IoT network"
            WizardKind.CUSTOM -> "Create network"
        }
        return WizardPlan.Ready(ChangeSet.of(title, summary, batch, checkInterfaces = listOf(name)), name, subnet, vid)
    }

    /**
     * Removes a network and everything that hangs off it: Wi-Fi interfaces, DHCP pool, firewall zone
     * with its forwardings and rules, the interface, and its bridge if nothing else uses it.
     */
    fun remove(snapshot: DeviceSnapshot, networkName: String): ChangeSet {
        require(networkName !in PROTECTED) { "$networkName is a built-in network" }
        val batch = UciBatch()
        // Later sections first: deleting an anonymous section can renumber the ones after it.
        fun deleteAll(config: String, sections: List<UciSection>) =
            sections.sortedByDescending { it.index }.forEach { batch.delete(config, it.name) }

        deleteAll("wireless", snapshot.wireless.ofType("wifi-iface").filter { networkName in it.list("network") })
        deleteAll("dhcp", snapshot.dhcp.ofType("dhcp").filter { it.opt("interface") == networkName })

        // A zone is deleted only when this network is all it holds. A shared zone (for example the home
        // zone with a second network added to it) just loses this one entry and keeps its rules.
        val holding = snapshot.firewall.ofType("zone").filter { it.opt("name") == networkName || networkName in it.list("network") }
        val (own, shared) = holding.partition { zone -> zone.list("network").all { it == networkName } }
        val ownNames = own.mapNotNull { it.opt("name") }.toSet()
        val attached = snapshot.firewall.sections.filter {
            it.type in setOf("forwarding", "rule", "redirect") && (it.opt("src") in ownNames || it.opt("dest") in ownNames)
        }
        deleteAll("firewall", own + attached)
        shared.filter { networkName in it.list("network") }.forEach { batch.delList("firewall", it.name, "network", networkName) }

        val iface = snapshot.network["$networkName"]?.takeIf { it.type == "interface" }
        val device = iface?.opt("device")
        val sharedDevice = snapshot.network.ofType("interface").any { it.name != networkName && it.opt("device") == device }
        val layout = vlanLayoutOf(snapshot.network)
        val vid = device?.takeIf { layout != null && it.startsWith("${layout.bridge}.") }?.substringAfterLast('.')?.toIntOrNull()
        val bridges = if (device == null || sharedDevice || vid != null) {
            emptyList()
        } else {
            snapshot.network.ofType("device").filter { it.opt("name") == device && it.opt("type") == "bridge" }
        }
        deleteAll("network", listOfNotNull(iface) + bridges)
        // The VLAN goes with its network. VLAN filtering and the home VLAN stay as they are.
        if (layout != null && vid != null && !sharedDevice && vid != VlanPlanner.lanVid(layout) && layout.vlan(vid) != null) {
            VlanPlanner.removeVlan(layout, batch, vid)
        }

        return ChangeSet.of("Remove network", listOf("Remove the \"$networkName\" network, its Wi-Fi, its address range and its firewall rules"), batch)
    }

    private fun allow(batch: UciBatch, section: String, ruleName: String, zone: String, port: String, proto: String) {
        batch.section("firewall", section, "rule")
        batch.set("firewall", section, "name", ruleName)
        batch.set("firewall", section, "src", zone)
        batch.set("firewall", section, "dest_port", port)
        batch.set("firewall", section, "proto", proto)
        batch.set("firewall", section, "target", "ACCEPT")
    }

    /** Name of the firewall zone whose network list contains [network]. */
    private fun zoneHolding(firewall: UciConfig, network: String): String? =
        firewall.ofType("zone").firstOrNull { network in it.list("network") }?.opt("name")

    private val CUSTOM_NAME = Regex("[a-z][a-z0-9_]{0,10}")

    private fun taken(snapshot: DeviceSnapshot, name: String) =
        snapshot.network[name] != null || snapshot.network["br_$name"] != null || snapshot.network["vlan_$name"] != null ||
            snapshot.dhcp[name] != null || snapshot.firewall[name] != null ||
            snapshot.firewall.ofType("zone").any { it.opt("name") == name } ||
            snapshot.wireless.sections.any { it.name.startsWith("${name}_") }

    private fun freeNetworkName(snapshot: DeviceSnapshot, base: String): String {
        var name = base
        var n = 2
        while (taken(snapshot, name)) name = "$base${n++}"
        return name
    }
}
