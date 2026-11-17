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
                val clients = mergeClients(gateway.readings(), config).filter { it.ip != null && it.link != ClientLink.OFFLINE }
                val wanNetworks = view.zones.firstOrNull { it.name == view.wanZone }?.networks.orEmpty()
                val wanAddress = gateway.interfaces().firstOrNull { it.name in wanNetworks && it.ipv4 != null }?.ipv4?.substringBefore('/')
                _ui.value = FirewallUi.Ready(view, clients, privateWan = wanAddress != null && isPrivateIpv4(wanAddress))
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
        return outcome(PortForwards.edit(config, existing, input))
    }

    fun removeForward(existing: PortForward): ChangeSet = PortForwards.remove(existing)

    fun toggleForward(existing: PortForward): ChangeSet = PortForwards.setEnabled(existing, !existing.enabled)

    fun toggleRule(rule: TrafficRule): ChangeSet = TrafficRules.setEnabled(rule, !rule.enabled)

    fun removeRule(rule: TrafficRule): ChangeSet = TrafficRules.remove(listOf(rule))

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
