package dev.openwrtmanager.app.ui.firewall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.clients.Client
import dev.openwrtmanager.app.clients.ClientLink
import dev.openwrtmanager.app.clients.ClientsGatewayProvider
import dev.openwrtmanager.app.clients.DeviceConfig
import dev.openwrtmanager.app.clients.Planned
import dev.openwrtmanager.app.clients.ipv4ToLong
import dev.openwrtmanager.app.clients.mergeClients
import dev.openwrtmanager.app.firewall.FirewallView
import dev.openwrtmanager.app.firewall.ForwardInput
import dev.openwrtmanager.app.firewall.ForwardProblem
import dev.openwrtmanager.app.firewall.PortForward
import dev.openwrtmanager.app.firewall.PortForwards
import dev.openwrtmanager.app.firewall.TrafficRule
import dev.openwrtmanager.app.firewall.TrafficRules
import dev.openwrtmanager.app.firewall.firewallViewOf
import dev.openwrtmanager.app.firewall.isPrivateIpv4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface FirewallUi {
    data object Loading : FirewallUi
    data object Offline : FirewallUi
    data object Failed : FirewallUi

    data class Ready(
        val view: FirewallView,
        /** Devices that can be a port forward's target: seen now, with an address. */
        val clients: List<Client>,
        /** The WAN address is private, so the router in front must forward the port too. */
        val privateWan: Boolean,
        val message: String? = null,
    ) : FirewallUi
}

@HiltViewModel
class FirewallViewModel @Inject constructor(
    private val gateways: ClientsGatewayProvider,
) : ViewModel() {
    private val _ui = MutableStateFlow<FirewallUi>(FirewallUi.Loading)
    val ui: StateFlow<FirewallUi> = _ui.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val gateway = gateways.current()
            if (gateway == null) {
                _ui.value = FirewallUi.Offline
                return@launch
            }
            try {
                val config = gateway.config()
                val view = firewallViewOf(config.firewall)
                val clients = targets(mergeClients(gateway.readings(), config), config)
                val wanNetworks = view.zones.firstOrNull { it.name == view.wanZone }?.networks.orEmpty()
                val wanAddress = gateway.interfaces().firstOrNull { it.name in wanNetworks && it.ipv4 != null }?.ipv4?.substringBefore('/')
                val message = (_ui.value as? FirewallUi.Ready)?.message
                _ui.value = FirewallUi.Ready(view, clients, privateWan = wanAddress != null && isPrivateIpv4(wanAddress), message = message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_ui.value !is FirewallUi.Ready) _ui.value = FirewallUi.Failed
            }
        }
    }

    fun clearMessage() = _ui.update { if (it is FirewallUi.Ready) it.copy(message = null) else it }

    suspend fun addForward(input: ForwardInput): ChangeSet? {
        val config = freshConfig() ?: return null
        return outcome(PortForwards.add(config, input))
    }

    suspend fun editForward(existing: PortForward, input: ForwardInput): ChangeSet? {
        val config = freshConfig() ?: return null
        val current = currentForward(config, existing) ?: return null
        return outcome(PortForwards.edit(config, current, input))
    }

    suspend fun removeForward(existing: PortForward): ChangeSet? {
        val config = freshConfig() ?: return null
        return currentForward(config, existing)?.let(PortForwards::remove)
    }

    suspend fun setForwardEnabled(existing: PortForward, enabled: Boolean): ChangeSet? {
        val config = freshConfig() ?: return null
        val current = currentForward(config, existing) ?: return null
        return PortForwards.setEnabled(current, enabled).takeIf { !it.isEmpty() } ?: alreadyThere(current.name, enabled)
    }

    suspend fun setRuleEnabled(rule: TrafficRule, enabled: Boolean): ChangeSet? {
        val config = freshConfig() ?: return null
        val current = currentRule(config, rule) ?: return null
        return TrafficRules.setEnabled(current, enabled).takeIf { !it.isEmpty() } ?: alreadyThere(current.name, enabled)
    }

    suspend fun removeRule(rule: TrafficRule): ChangeSet? {
        val config = freshConfig() ?: return null
        return currentRule(config, rule)?.let { TrafficRules.remove(listOf(it)) }
    }

    /** The forward as it is on the router now; null, with a message and a refresh, when it is gone. */
    private fun currentForward(config: DeviceConfig, shown: PortForward): PortForward? =
        firewallViewOf(config.firewall).forwards.firstOrNull { it.section == shown.section } ?: gone(shown.name)

    private fun currentRule(config: DeviceConfig, shown: TrafficRule): TrafficRule? =
        firewallViewOf(config.firewall).rules.firstOrNull { it.section == shown.section } ?: gone(shown.name)

    private fun <T> gone(name: String): T? {
        say("$name no longer exists on the router. The list has been refreshed.")
        load()
        return null
    }

    private fun alreadyThere(name: String, enabled: Boolean): ChangeSet? {
        say("$name is already ${if (enabled) "on" else "off"}.")
        load()
        return null
    }

    /**
     * Devices a forward can point at: connected ones, and reserved ones even while they are off,
     * each by its reserved address when it has one, since that is where the forward will point.
     */
    private fun targets(clients: List<dev.openwrtmanager.app.clients.Client>, config: DeviceConfig): List<dev.openwrtmanager.app.clients.Client> {
        val reserved = dev.openwrtmanager.app.clients.reservationsOf(config.dhcp)
            .filter { it.ip?.let(::ipv4ToLong) != null }
            .associateBy { it.mac }
        val online = clients.filter { it.link != ClientLink.OFFLINE }.map { c -> reserved[c.mac]?.let { c.copy(ip = it.ip) } ?: c }.filter { it.ip != null }
        val offline = reserved.values.filter { r -> online.none { it.mac == r.mac } }.map { r ->
            dev.openwrtmanager.app.clients.Client(r.mac, r.name, r.ip, ClientLink.OFFLINE, null, null, null, null, null, reserved = true, blocked = false)
        }
        return online + offline
    }

    /** Changes are planned from the router's config as it is now, never from what the screen shows. */
    private suspend fun freshConfig(): DeviceConfig? = try {
        gateways.current()?.config()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } ?: run {
        say("Could not read the router's current settings. Nothing was changed.")
        null
    }

    private fun outcome(planned: Planned<ForwardProblem>): ChangeSet? = when (planned) {
        is Planned.Ready -> planned.change
        is Planned.Problem -> {
            say(problemText(planned.problem))
            null
        }
    }

    private fun say(message: String) = _ui.update { if (it is FirewallUi.Ready) it.copy(message = message) else it }

    companion object {
        fun problemText(problem: ForwardProblem): String = when (problem) {
            ForwardProblem.BAD_NAME -> "Give the forward a name of up to 64 characters."
            ForwardProblem.BAD_PORT -> "Ports are numbers from 1 to 65535, or a range like 6000-6010."
            ForwardProblem.RANGE_MISMATCH -> "The inside range must have as many ports as the outside range."
            ForwardProblem.CLASHES_WITH_FORWARD -> "Another port forward already uses that outside port."
            ForwardProblem.CLASHES_WITH_ROUTER -> "The router itself uses that port from the internet."
            ForwardProblem.NO_TARGET_ADDRESS -> "That device has no address right now."
            ForwardProblem.TARGET_OUTSIDE_LAN -> "That device is not on one of this router's networks."
            ForwardProblem.NO_PROTOCOL -> "Pick TCP, UDP or both."
            ForwardProblem.CANNOT_RESERVE -> "That device's address could not be reserved for it. Check the reservations on the Network tab."
        }
    }
}
