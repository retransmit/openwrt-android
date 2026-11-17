package dev.openwrtmanager.app.ui.clients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.clients.BlockPlanner
import dev.openwrtmanager.app.clients.Client
import dev.openwrtmanager.app.clients.ClientActions
import dev.openwrtmanager.app.clients.ClientLink
import dev.openwrtmanager.app.clients.ClientsGatewayProvider
import dev.openwrtmanager.app.clients.DeviceConfig
import dev.openwrtmanager.app.clients.Planned
import dev.openwrtmanager.app.clients.ReservationProblem
import dev.openwrtmanager.app.clients.Reservations
import dev.openwrtmanager.app.clients.actionsFor
import dev.openwrtmanager.app.clients.mergeClients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ClientsUi {
    data object Loading : ClientsUi
    data object Offline : ClientsUi
    data object Failed : ClientsUi

    data class Ready(
        /** Everything seen right now, blocked ones included. */
        val online: List<Client>,
        /** Every blocked device, seen or not. */
        val blocked: List<Client>,
        val missing: List<String>,
        /** True when open connections may outlive a block (flow offloading on). */
        val offloading: Boolean,
        val message: String? = null,
    ) : ClientsUi
}

@HiltViewModel
class ClientsViewModel @Inject constructor(
    private val gateways: ClientsGatewayProvider,
) : ViewModel() {
    private val _ui = MutableStateFlow<ClientsUi>(ClientsUi.Loading)
    val ui: StateFlow<ClientsUi> = _ui.asStateFlow()

    private var config: DeviceConfig? = null
    private var myAddress: String? = null

    /** Refreshes every 5 s until cancelled; the screen runs this only while it is visible. */
    suspend fun poll() {
        while (true) {
            load()
            delay(REFRESH_MS)
        }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val gateway = gateways.current()
        if (gateway == null) {
            _ui.value = ClientsUi.Offline
            return
        }
        try {
            val deviceConfig = gateway.config()
            val readings = gateway.readings()
            if (myAddress == null) myAddress = gateway.myAddress()
            config = deviceConfig
            val clients = mergeClients(readings, deviceConfig)
            val message = (_ui.value as? ClientsUi.Ready)?.message
            _ui.value = ClientsUi.Ready(
                online = clients.filter { it.link != ClientLink.OFFLINE },
                blocked = clients.filter { it.blocked },
                missing = readings.missing,
                offloading = BlockPlanner.offloadingOn(deviceConfig.firewall),
                message = message,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_ui.value !is ClientsUi.Ready) _ui.value = ClientsUi.Failed
        }
    }

    fun actions(client: Client): ClientActions =
        config?.let { actionsFor(client, it) } ?: ClientActions(reconnect = false, reserve = false, block = false)

    fun isThisPhone(client: Client): Boolean = client.ip != null && client.ip == myAddress

    fun clearMessage() = _ui.update { if (it is ClientsUi.Ready) it.copy(message = null) else it }

    fun reconnect(client: Client) {
        val ifname = client.ifname ?: return
        viewModelScope.launch {
            val ok = try {
                gateways.current()?.reconnect(ifname, client.mac) == true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            val name = label(client)
            say(if (ok) "Asked $name to reconnect." else "Could not reconnect $name. It may have left already.")
        }
    }

    fun reserve(client: Client, ip: String, name: String): ChangeSet? {
        val deviceConfig = config ?: return null
        return when (val planned = Reservations.add(deviceConfig.dhcp, deviceConfig.network, client.mac, ip, name)) {
            is Planned.Ready -> planned.change
            is Planned.Problem -> {
                say(reservationText(planned.problem))
                null
            }
        }
    }

    /** Puts the fixed include file in place first; without it the firewall must not reference it. */
    suspend fun block(client: Client): ChangeSet? {
        val deviceConfig = config ?: return null
        val written = try {
            gateways.current()?.ensureBlockFile() == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!written) {
            say("Could not prepare the block on the router. Nothing was changed.")
            return null
        }
        return BlockPlanner.block(deviceConfig.firewall, client.mac, label(client))
    }

    fun unblock(client: Client): ChangeSet {
        val firewall = config?.firewall ?: return BlockPlanner.unblock(dev.openwrtmanager.app.uci.UciConfig("firewall", emptyList()), client.mac, label(client))
        return BlockPlanner.unblock(firewall, client.mac, label(client))
    }

    private fun say(message: String) = _ui.update { if (it is ClientsUi.Ready) it.copy(message = message) else it }

    companion object {
        private const val REFRESH_MS = 5_000L

        fun label(client: Client): String = client.name ?: client.mac

        fun reservationText(problem: ReservationProblem): String = when (problem) {
            ReservationProblem.BadName -> "Use letters, digits and hyphens for the name, up to 63, not starting or ending with a hyphen."
            ReservationProblem.BadIp -> "That is not an address a device can use."
            ReservationProblem.OutsideNetwork -> "That address is outside every network this router hands out addresses on."
            ReservationProblem.RouterAddress -> "That is the router's own address."
            ReservationProblem.IpTaken -> "Another device already has that address reserved."
            ReservationProblem.MacTaken -> "This device already has a reservation. Change it on the Network tab."
        }
    }
}
