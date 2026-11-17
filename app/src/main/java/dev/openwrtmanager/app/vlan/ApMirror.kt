package dev.openwrtmanager.app.vlan

import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.uci.UciBatch
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.wifi.DeviceSnapshot
import dev.openwrtmanager.app.wifi.NetworkEdit
import dev.openwrtmanager.app.wifi.Security
import dev.openwrtmanager.app.wifi.WifiEdits

/** A VLAN network on the main router, as an access point needs to know it. */
data class SourceNetwork(val name: String, val vid: Int, val ssid: String, val security: Security, val key: String?) {
    override fun toString(): String = "SourceNetwork($name, vlan $vid, $ssid, $security)"
}

/** The router's VLAN networks that have Wi-Fi turned on, with the first enabled SSID of each. */
fun sourceNetworksOf(network: UciConfig, wireless: UciConfig): List<SourceNetwork> {
    val layout = vlanLayoutOf(network) ?: return emptyList()
    val lanVid = VlanPlanner.lanVid(layout)
    return layout.interfaces.toSortedMap().mapNotNull { (vid, name) ->
        if (vid == lanVid || layout.vlan(vid) == null) return@mapNotNull null
        val wifi = wireless.ofType("wifi-iface").firstOrNull { name in it.list("network") && !it.flag("disabled") } ?: return@mapNotNull null
        val security = Security.fromUci(wifi.opt("encryption")) ?: return@mapNotNull null
        SourceNetwork(name, vid, wifi.opt("ssid") ?: return@mapNotNull null, security, wifi.opt("key"))
    }
}

enum class MirrorBlock { NOT_DSA, NOT_AN_ACCESS_POINT, UPLINK_NOT_A_PORT, VID_TAKEN, NAME_TAKEN, NO_RADIO, NOTHING_CHOSEN }

sealed interface MirrorPlan {
    data class Ready(val change: ChangeSet) : MirrorPlan
    data class Blocked(val reason: MirrorBlock) : MirrorPlan
}

/**
 * Puts the router's VLAN networks on an access point: the same VLAN IDs tagged on the uplink port,
 * an interface with no address on each, and the same SSIDs with client isolation. The router keeps
 * doing DHCP and firewalling; the access point only bridges.
 */
object ApMirror {
    fun plan(ap: DeviceSnapshot, sources: List<SourceNetwork>, uplink: String): MirrorPlan {
        if (ap.hasUplink) return MirrorPlan.Blocked(MirrorBlock.NOT_AN_ACCESS_POINT)
        val before = vlanLayoutOf(ap.network) ?: return MirrorPlan.Blocked(MirrorBlock.NOT_DSA)
        if (uplink !in before.ports) return MirrorPlan.Blocked(MirrorBlock.UPLINK_NOT_A_PORT)
        if (sources.isEmpty()) return MirrorPlan.Blocked(MirrorBlock.NOTHING_CHOSEN)
        val radios = ap.wireless.ofType("wifi-device").map { it.name }
        if (radios.isEmpty()) return MirrorPlan.Blocked(MirrorBlock.NO_RADIO)
        for (source in sources) {
            if (before.vlan(source.vid) != null) return MirrorPlan.Blocked(MirrorBlock.VID_TAKEN)
            val name = source.name
            if (ap.network[name] != null || ap.network["vlan_$name"] != null || ap.wireless.sections.any { it.name.startsWith("${name}_") }) {
                return MirrorPlan.Blocked(MirrorBlock.NAME_TAKEN)
            }
        }

        val batch = UciBatch()
        var layout = VlanPlanner.enableFiltering(before, ap.network, batch)
        val summary = mutableListOf<String>()
        if (!before.filtering) summary += "Turn on VLANs for this device's ports. Its own address stays on your home network."
        for (source in sources) {
            layout = VlanPlanner.addVlan(layout, batch, source.vid, source.name, mapOf(uplink to PortRole.TAGGED))
            batch.section("network", source.name, "interface")
            batch.set("network", source.name, "proto", "none")
            batch.set("network", source.name, "device", "${layout.bridge}.${source.vid}")
            val edit = NetworkEdit(source.ssid, source.security, source.key, hidden = false, isolate = true, enabled = true, radios = radios.toSet())
            for (radio in radios) WifiEdits.newIface(batch, "${source.name}_$radio", radio, edit, source.name)
            summary += "Add Wi-Fi network \"${source.ssid}\" on VLAN ${source.vid}"
        }
        summary += "Carry ${if (sources.size == 1) "it" else "them"} to and from your router tagged on $uplink"
        summary += "Your router hands out addresses and keeps these networks away from your home network"
        return MirrorPlan.Ready(ChangeSet.of("Add networks from your router", summary, batch))
    }
}
