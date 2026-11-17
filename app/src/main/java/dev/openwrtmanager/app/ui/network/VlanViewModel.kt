package dev.openwrtmanager.app.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.uci.UciBatch
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.vlan.PortRole
import dev.openwrtmanager.app.vlan.VlanLayout
import dev.openwrtmanager.app.vlan.VlanPlanner
import dev.openwrtmanager.app.vlan.VlanProblem
import dev.openwrtmanager.app.vlan.homeWifiOn
import dev.openwrtmanager.app.vlan.vlanLayoutOf
import dev.openwrtmanager.app.wifi.BlockReason
import dev.openwrtmanager.app.wifi.DeviceSnapshot
import dev.openwrtmanager.app.wifi.NetworkWizard
import dev.openwrtmanager.app.wifi.Security
import dev.openwrtmanager.app.wifi.WifiGatewayProvider
import dev.openwrtmanager.app.wifi.WizardInput
import dev.openwrtmanager.app.wifi.WizardKind
import dev.openwrtmanager.app.wifi.WizardPlan
import dev.openwrtmanager.app.wifi.configuredSubnets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One VLAN as shown on the Network tab. [name] is its network, or the name it was created with. */
data class VlanRow(
    val vid: Int,
    val name: String?,
    val subnet: String?,
    val roles: Map<String, PortRole>,
    val removable: Boolean,
    val home: Boolean,
)

sealed interface VlanUi {
    data object Loading : VlanUi
    data object Offline : VlanUi
    data object Failed : VlanUi

    /** The device has no DSA bridge on its home network, so it cannot do VLANs this way. */
    data object Unsupported : VlanUi

    data class Ready(
        val ports: List<String>,
        val rows: List<VlanRow>,
        val filtering: Boolean,
        val suggestedVid: Int,
        val message: String? = null,
    ) : VlanUi
}

@HiltViewModel
class VlanViewModel @Inject constructor(
    private val gateways: WifiGatewayProvider,
) : ViewModel() {
    private val _ui = MutableStateFlow<VlanUi>(VlanUi.Loading)
    val ui: StateFlow<VlanUi> = _ui.asStateFlow()

    private var snapshot: DeviceSnapshot? = null
    private var layout: VlanLayout? = null

    fun load() {
        viewModelScope.launch {
            val gateway = gateways.current()
            if (gateway == null) {
                _ui.value = VlanUi.Offline
                return@launch
            }
            if (_ui.value !is VlanUi.Ready) _ui.value = VlanUi.Loading
            _ui.value = try {
                val snap = gateway.snapshot()
                snapshot = snap
                val found = vlanLayoutOf(snap.network)
                layout = found
                if (found == null) VlanUi.Unsupported else ready(snap, found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VlanUi.Failed
            }
        }
    }

    fun clearMessage() = _ui.update { if (it is VlanUi.Ready) it.copy(message = null) else it }

    /** The change that sets these port roles in VLAN [vid], or null when nothing changes or it would not work. */
    fun editRoles(vid: Int, roles: Map<String, PortRole>): ChangeSet? {
        val snap = snapshot ?: return null
        val before = layout ?: return null
        val batch = UciBatch()
        var after = VlanPlanner.enableFiltering(before, snap.network, batch)
        after = VlanPlanner.setRoles(after, batch, vid, roles)
        if (!check(after, snap)) return null
        if (batch.isEmpty()) return null
        return ChangeSet.of("Change VLAN ports", VlanPlanner.summary(before, after), batch)
    }

    /** Removes VLAN [vid]; a VLAN that carries a network takes the network with it. */
    fun remove(vid: Int): ChangeSet? {
        val snap = snapshot ?: return null
        val current = layout ?: return null
        if (!current.filtering || vid == VlanPlanner.lanVid(current) || current.vlan(vid) == null) return null
        current.interfaces[vid]?.let { return NetworkWizard.remove(snap, it) }
        val batch = UciBatch()
        val after = VlanPlanner.removeVlan(current, batch, vid)
        return ChangeSet.of("Remove VLAN", VlanPlanner.summary(current, after), batch)
    }

    /**
     * A new VLAN. [routed] gives it an address range on this router with internet access and no way
     * into the home network; otherwise it only passes through, to be carried to another device.
     */
    fun create(vid: Int, name: String, ports: Map<String, PortRole>, routed: Boolean): ChangeSet? {
        val snap = snapshot ?: return null
        val before = layout ?: return null
        val batch = UciBatch()
        val filtering = VlanPlanner.enableFiltering(before, snap.network, batch)
        VlanPlanner.checkNewId(filtering, vid)?.let { return fail(problemText(it)) }
        if (!NAME.matches(name)) return fail(BAD_NAME)
        if (routed) {
            val input = WizardInput(WizardKind.CUSTOM, "", Security.OPEN, null, emptySet(), ports = ports, customName = name, vid = vid)
            return when (val plan = NetworkWizard.plan(snap, input)) {
                is WizardPlan.Ready -> plan.change
                is WizardPlan.Blocked -> fail(blockText(plan.reason))
            }
        }
        if (snap.network["vlan_$name"] != null || snap.network[name] != null) return fail(BAD_NAME)
        val after = VlanPlanner.addVlan(filtering, batch, vid, name, ports)
        if (!check(after, snap)) return null
        val summary = VlanPlanner.summary(before, after).toMutableList()
        summary += "Create VLAN $vid ($name) with no address on this router"
        return ChangeSet.of("Create VLAN", summary, batch)
    }

    private fun check(after: VlanLayout, snap: DeviceSnapshot): Boolean {
        val problem = VlanPlanner.validate(after, homeWifiOn(snap.wireless)).firstOrNull() ?: return true
        fail(problemText(problem))
        return false
    }

    private fun fail(message: String): ChangeSet? {
        _ui.update { if (it is VlanUi.Ready) it.copy(message = message) else it }
        return null
    }

    private fun ready(snap: DeviceSnapshot, layout: VlanLayout): VlanUi.Ready {
        val lanVid = VlanPlanner.lanVid(layout)
        val rows = if (!layout.filtering) {
            listOf(VlanRow(lanVid, "lan", subnetOf(snap, "lan"), layout.ports.associateWith { PortRole.UNTAGGED }, removable = false, home = true))
        } else {
            layout.vlans.map { vlan ->
                val iface = layout.interfaces[vlan.vid]
                VlanRow(
                    vid = vlan.vid,
                    name = iface ?: vlan.section.takeIf { it.startsWith("vlan_") }?.removePrefix("vlan_"),
                    subnet = iface?.let { subnetOf(snap, it) },
                    roles = vlan.roles,
                    removable = vlan.vid != lanVid,
                    home = vlan.vid == lanVid,
                )
            }
        }
        return VlanUi.Ready(layout.ports, rows, layout.filtering, VlanPlanner.nextFreeVid(layout, 10))
    }

    private fun subnetOf(snap: DeviceSnapshot, iface: String): String? =
        snap.network[iface]?.let { configuredSubnets(UciConfig("network", listOf(it))).firstOrNull() }

    private companion object {
        val NAME = Regex("[a-z][a-z0-9_]{0,10}")
        const val BAD_NAME = "Use up to 11 lowercase letters, digits or underscores, starting with a letter, and a name no other network uses."

        fun problemText(problem: VlanProblem): String = when (problem) {
            is VlanProblem.BadId -> "A VLAN ID is a number from 1 to 4094."
            is VlanProblem.IdTaken -> "VLAN ${problem.vid} is already in use."
            is VlanProblem.TwoUntagged -> "${problem.port} would be a device port in two VLANs. A port can be untagged in one only."
            VlanProblem.LanUnreachable -> "That would leave your home network with no port and no Wi-Fi, so nothing could reach the router on it."
        }

        fun blockText(reason: BlockReason): String = when (reason) {
            BlockReason.NO_UPLINK -> "This device has no internet connection of its own, so it cannot route a network. Use pass-through instead."
            BlockReason.NO_WAN_ZONE -> "The firewall has no zone for the internet connection."
            BlockReason.NO_FREE_SUBNET -> "Every address range the app could use is already taken."
            BlockReason.NO_RADIO -> "Pick at least one band."
            BlockReason.BAD_NAME -> BAD_NAME
            BlockReason.VLAN_PROBLEM -> "The VLAN ID is taken, or no port or Wi-Fi would be left on your home network."
        }
    }
}
