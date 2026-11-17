package dev.openwrtmanager.app.vlan

import dev.openwrtmanager.app.uci.UciBatch
import dev.openwrtmanager.app.uci.UciConfig

sealed interface VlanProblem {
    data class BadId(val vid: Int) : VlanProblem
    data class IdTaken(val vid: Int) : VlanProblem
    data class TwoUntagged(val port: String) : VlanProblem

    /** The home network would have no untagged port and no Wi-Fi left, so nothing could reach the router on it. */
    data object LanUnreachable : VlanProblem
}

/**
 * Turns VLAN edits into uci lines. Every function writes into the given batch and returns the layout
 * as it will be once the batch is applied, so several edits can be chained in one change.
 * Port roles are always written with an explicit suffix: `:u*` untagged (and PVID), `:t` tagged.
 */
object VlanPlanner {
    const val LAN_VID = 1
    private const val MAX_VID = 4094

    /** The VLAN the home network is on: VLAN 1 unless the router was set up otherwise. */
    fun lanVid(layout: VlanLayout): Int = layout.interfaces.entries.firstOrNull { it.value == "lan" }?.key ?: LAN_VID

    /**
     * Turns on VLAN filtering with every port untagged in VLAN 1, and moves every interface on the
     * bridge to `<bridge>.1`. Nothing changes for wired or Wi-Fi devices. Does nothing when the
     * bridge already filters.
     */
    fun enableFiltering(layout: VlanLayout, network: UciConfig, batch: UciBatch): VlanLayout {
        if (layout.filtering) return layout
        val section = "vlan_lan"
        batch.section("network", section, "bridge-vlan")
        batch.set("network", section, "device", layout.bridge)
        batch.set("network", section, "vlan", LAN_VID.toString())
        for (port in layout.ports) batch.addList("network", section, "ports", token(port, PortRole.UNTAGGED))
        val moved = network.ofType("interface").filter { it.opt("device") == layout.bridge }
        for (iface in moved) batch.set("network", iface.name, "device", "${layout.bridge}.$LAN_VID")
        val lan = BridgeVlan(section, LAN_VID, layout.ports.associateWith { PortRole.UNTAGGED })
        val interfaces = moved.firstOrNull()?.let { layout.interfaces + (LAN_VID to it.name) } ?: layout.interfaces
        return layout.copy(vlans = listOf(lan), interfaces = interfaces)
    }

    /** A problem with using [vid] for a new VLAN, or null when it is free and valid. */
    fun checkNewId(layout: VlanLayout, vid: Int): VlanProblem? = when {
        vid !in 1..MAX_VID -> VlanProblem.BadId(vid)
        layout.vlan(vid) != null -> VlanProblem.IdTaken(vid)
        else -> null
    }

    /** Adds VLAN [vid] in section `vlan_<name>`. Ports not in [roles] are not members. */
    fun addVlan(layout: VlanLayout, batch: UciBatch, vid: Int, name: String, roles: Map<String, PortRole>): VlanLayout {
        require(layout.filtering) { "turn on VLAN filtering first" }
        require(checkNewId(layout, vid) == null) { "VLAN $vid cannot be added" }
        val section = "vlan_$name"
        require(layout.vlans.none { it.section == section }) { "$section exists" }
        batch.section("network", section, "bridge-vlan")
        batch.set("network", section, "device", layout.bridge)
        batch.set("network", section, "vlan", vid.toString())
        val empty = BridgeVlan(section, vid, layout.ports.associateWith { PortRole.NONE })
        val added = layout.copy(vlans = (layout.vlans + empty).sortedBy { it.vid })
        val after = withRoles(added, vid, roles)
        writePorts(batch, after.vlan(vid)!!, added = true)
        rewriteChanged(batch, added, after, except = vid)
        return after
    }

    /**
     * Sets the role of the given ports in VLAN [vid]; other ports keep theirs. A port made untagged
     * here stops being untagged in the VLAN it was untagged in, because a port has one PVID.
     */
    fun setRoles(layout: VlanLayout, batch: UciBatch, vid: Int, roles: Map<String, PortRole>): VlanLayout {
        requireNotNull(layout.vlan(vid)) { "no VLAN $vid" }
        val after = withRoles(layout, vid, roles)
        rewriteChanged(batch, layout, after, except = null)
        return after
    }

    fun removeVlan(layout: VlanLayout, batch: UciBatch, vid: Int): VlanLayout {
        require(vid != lanVid(layout)) { "the home network's VLAN cannot be removed" }
        val vlan = requireNotNull(layout.vlan(vid)) { "no VLAN $vid" }
        batch.delete("network", vlan.section)
        return layout.copy(vlans = layout.vlans - vlan, interfaces = layout.interfaces - vid)
    }

    fun validate(layout: VlanLayout, lanHasWifi: Boolean): List<VlanProblem> {
        if (!layout.filtering) return emptyList()
        val problems = mutableListOf<VlanProblem>()
        for (port in layout.ports) {
            if (layout.vlans.count { it.roles[port] == PortRole.UNTAGGED } > 1) problems += VlanProblem.TwoUntagged(port)
        }
        val lan = layout.vlan(lanVid(layout))
        val lanHasPort = lan?.roles?.values?.any { it == PortRole.UNTAGGED } == true
        if (!lanHasPort && !lanHasWifi) problems += VlanProblem.LanUnreachable
        return problems
    }

    /** [preferred] when free, otherwise the next free multiple of 10 above it. */
    fun nextFreeVid(layout: VlanLayout, preferred: Int): Int {
        if (layout.vlan(preferred) == null) return preferred
        var vid = (preferred / 10 + 1) * 10
        while (layout.vlan(vid) != null) vid += 10
        return vid
    }

    /** Plain lines describing what changes between two layouts, port by port. */
    fun summary(before: VlanLayout, after: VlanLayout): List<String> {
        val lines = mutableListOf<String>()
        if (!before.filtering && after.filtering) {
            lines += "Turn on VLANs for your LAN ports. Wired and Wi-Fi devices stay on your home network."
        }
        val lanVid = lanVid(after)
        for (vlan in before.vlans.filter { after.vlan(it.vid) == null }) lines += "Remove VLAN ${vlan.vid} (${label(before, vlan)})"
        for (port in after.ports) {
            val ordered = after.vlans.sortedBy { if (it.vid == lanVid) -1 else it.vid }
            for (vlan in ordered) {
                val was = before.vlan(vlan.vid)?.roles?.get(port)
                    ?: if (!before.filtering && vlan.vid == lanVid) PortRole.UNTAGGED else PortRole.NONE
                val now = vlan.roles[port] ?: PortRole.NONE
                if (was == now) continue
                lines += if (vlan.vid == lanVid) {
                    when (now) {
                        PortRole.NONE -> "$port leaves your home network"
                        PortRole.UNTAGGED -> "$port is back on your home network"
                        PortRole.TAGGED -> "$port carries your home network tagged"
                    }
                } else {
                    val name = label(after, vlan)
                    when (now) {
                        PortRole.NONE -> "$port leaves $name"
                        PortRole.UNTAGGED -> "$port joins $name as a device port"
                        PortRole.TAGGED -> "$port carries $name to an access point"
                    }
                }
            }
        }
        return lines
    }

    private fun label(layout: VlanLayout, vlan: BridgeVlan): String =
        layout.interfaces[vlan.vid] ?: vlan.section.takeIf { it.startsWith("vlan_") }?.removePrefix("vlan_") ?: "VLAN ${vlan.vid}"

    private fun withRoles(layout: VlanLayout, vid: Int, roles: Map<String, PortRole>): VlanLayout {
        val untaggedHere = roles.filterValues { it == PortRole.UNTAGGED }.keys
        val vlans = layout.vlans.map { vlan ->
            if (vlan.vid == vid) {
                vlan.copy(roles = vlan.roles + roles.filterKeys { it in layout.ports })
            } else {
                vlan.copy(roles = vlan.roles.mapValues { (port, role) -> if (port in untaggedHere && role == PortRole.UNTAGGED) PortRole.NONE else role })
            }
        }
        return layout.copy(vlans = vlans)
    }

    private fun rewriteChanged(batch: UciBatch, before: VlanLayout, after: VlanLayout, except: Int?) {
        for (vlan in after.vlans) {
            if (vlan.vid == except || before.vlan(vlan.vid) == vlan) continue
            writePorts(batch, vlan, added = false)
        }
    }

    private fun writePorts(batch: UciBatch, vlan: BridgeVlan, added: Boolean) {
        if (!added) batch.delete("network", vlan.section, "ports")
        for ((port, role) in vlan.roles) if (role != PortRole.NONE) batch.addList("network", vlan.section, "ports", token(port, role))
    }

    private fun token(port: String, role: PortRole): String = when (role) {
        PortRole.UNTAGGED -> "$port:u*"
        PortRole.TAGGED -> "$port:t"
        PortRole.NONE -> error("not a member")
    }
}
