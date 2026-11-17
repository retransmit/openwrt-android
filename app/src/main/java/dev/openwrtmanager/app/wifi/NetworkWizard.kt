package dev.openwrtmanager.app.wifi

import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.router.RouterClient
import dev.openwrtmanager.app.uci.UciBatch
import dev.openwrtmanager.app.uci.UciClient
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection

enum class WizardKind { GUEST, IOT }

data class WizardInput(
    val kind: WizardKind,
    val ssid: String,
    val security: Security,
    val key: String?,
    val radios: Set<String>,
    /** IoT only: whether devices on the home network may open connections to IoT devices. */
    val lanCanReachIot: Boolean = true,
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

enum class BlockReason { NO_UPLINK, NO_WAN_ZONE, NO_FREE_SUBNET, NO_RADIO }

sealed interface WizardPlan {
    data class Ready(val change: ChangeSet, val networkName: String, val subnet: String) : WizardPlan
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
        if (radios.isEmpty()) return WizardPlan.Blocked(BlockReason.NO_RADIO)
        val wanZone = zoneHolding(snapshot.firewall, "wan") ?: return WizardPlan.Blocked(BlockReason.NO_WAN_ZONE)
        val subnet = pickSubnet(snapshot.usedSubnets) ?: return WizardPlan.Blocked(BlockReason.NO_FREE_SUBNET)

        val name = freeNetworkName(snapshot, if (input.kind == WizardKind.GUEST) "guest" else "iot")
        val bridge = "br-$name"
        val router = subnet.substringBefore('/').substringBeforeLast('.') + ".1"
        val batch = UciBatch()

        batch.section("network", "br_$name", "device")
        batch.set("network", "br_$name", "type", "bridge")
        batch.set("network", "br_$name", "name", bridge)
        batch.set("network", "br_$name", "bridge_empty", "1")
        batch.section("network", name, "interface")
        batch.set("network", name, "proto", "static")
        batch.set("network", name, "device", bridge)
        batch.set("network", name, "ipaddr", router)
        batch.set("network", name, "netmask", "255.255.255.0")

        batch.section("dhcp", name, "dhcp")
        batch.set("dhcp", name, "interface", name)
        batch.set("dhcp", name, "start", "100")
        batch.set("dhcp", name, "limit", "150")
        batch.set("dhcp", name, "leasetime", if (input.kind == WizardKind.GUEST) "1h" else "12h")

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
        val summary = mutableListOf(
            "Create Wi-Fi network \"${input.ssid}\" on $bands",
            "Give it its own address range, $subnet",
        )
        if (input.kind == WizardKind.GUEST) {
            summary += "Devices on it can reach the internet, but not your home network or this router's settings"
        } else {
            summary += "Devices on it can reach the internet, but cannot start connections to your home network"
            summary += if (homeCanReach) "Your home devices can reach devices on it" else "Your home devices cannot reach devices on it"
        }
        // Isolation is per radio: devices on different bands of the same network still share its bridge.
        summary += if (radios.size == 1) "Devices on it cannot see each other" else "Devices on the same band cannot see each other"
        val title = if (input.kind == WizardKind.GUEST) "Create guest network" else "Create IoT network"
        return WizardPlan.Ready(ChangeSet.of(title, summary, batch), name, subnet)
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
        val bridges = if (device == null || sharedDevice) {
            emptyList()
        } else {
            snapshot.network.ofType("device").filter { it.opt("name") == device && it.opt("type") == "bridge" }
        }
        deleteAll("network", listOfNotNull(iface) + bridges)

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

    private fun freeNetworkName(snapshot: DeviceSnapshot, base: String): String {
        fun taken(name: String) =
            snapshot.network[name] != null || snapshot.network["br_$name"] != null || snapshot.dhcp[name] != null ||
                snapshot.firewall[name] != null || snapshot.firewall.ofType("zone").any { it.opt("name") == name } ||
                snapshot.wireless.sections.any { it.name.startsWith("${name}_") }
        var name = base
        var n = 2
        while (taken(name)) name = "$base${n++}"
        return name
    }
}
