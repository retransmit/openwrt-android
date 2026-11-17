package dev.openwrtmanager.app.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.clients.ClientsGatewayProvider
import dev.openwrtmanager.app.clients.DeviceConfig
import dev.openwrtmanager.app.clients.DhcpPools
import dev.openwrtmanager.app.clients.Planned
import dev.openwrtmanager.app.clients.Pool
import dev.openwrtmanager.app.clients.PoolProblem
import dev.openwrtmanager.app.clients.Reservation
import dev.openwrtmanager.app.clients.Reservations
import dev.openwrtmanager.app.router.InterfaceStatus
import dev.openwrtmanager.app.ui.clients.ClientsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InterfaceRow(val status: InterfaceStatus, val pool: Pool?)

sealed interface ExtrasUi {
    data object Loading : ExtrasUi
    data object Offline : ExtrasUi
    data object Failed : ExtrasUi

    data class Ready(
        val interfaces: List<InterfaceRow>,
        val reservations: List<Reservation>,
        val message: String? = null,
    ) : ExtrasUi
}

/** Interfaces, DHCP pools and address reservations for the Network tab. */
@HiltViewModel
class NetworkExtrasViewModel @Inject constructor(
    private val gateways: ClientsGatewayProvider,
) : ViewModel() {
    private val _ui = MutableStateFlow<ExtrasUi>(ExtrasUi.Loading)
    val ui: StateFlow<ExtrasUi> = _ui.asStateFlow()

    private var config: DeviceConfig? = null

    fun load() {
        viewModelScope.launch {
            val gateway = gateways.current()
            if (gateway == null) {
                _ui.value = ExtrasUi.Offline
                return@launch
            }
            try {
                val deviceConfig = gateway.config()
                val interfaces = gateway.interfaces()
                config = deviceConfig
                val pools = DhcpPools.pools(deviceConfig.dhcp, deviceConfig.network).associateBy { it.iface }
                _ui.value = ExtrasUi.Ready(
                    interfaces = interfaces.map { InterfaceRow(it, pools[it.name]) },
                    reservations = Reservations.list(deviceConfig.dhcp),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_ui.value !is ExtrasUi.Ready) _ui.value = ExtrasUi.Failed
            }
        }
    }

    fun clearMessage() = _ui.update { if (it is ExtrasUi.Ready) it.copy(message = null) else it }

    /** The change for these pool settings; null when nothing changes or the settings would not work. */
    fun editPool(pool: Pool, start: Int, limit: Int, leasetime: String): ChangeSet? =
        when (val planned = DhcpPools.edit(pool, start, limit, leasetime)) {
            is Planned.Ready -> planned.change.takeIf { !it.isEmpty() }
            is Planned.Problem -> {
                say(
                    when (planned.problem) {
                        PoolProblem.START_OUT_OF_RANGE -> "The first address must be between 1 and 254."
                        PoolProblem.POOL_OUTSIDE_SUBNET -> "The pool must fit inside ${pool.subnet}."
                        PoolProblem.BAD_LEASE_TIME -> "Use a lease time like 30m, 12h, 1d or infinite, at least 2 minutes."
                    },
                )
                null
            }
        }

    fun editReservation(reservation: Reservation, ip: String, name: String): ChangeSet? {
        val deviceConfig = config ?: return null
        return when (val planned = Reservations.edit(deviceConfig.dhcp, deviceConfig.network, reservation, ip, name)) {
            is Planned.Ready -> planned.change.takeIf { !it.isEmpty() }
            is Planned.Problem -> {
                say(ClientsViewModel.reservationText(planned.problem))
                null
            }
        }
    }

    fun removeReservation(reservation: Reservation): ChangeSet = Reservations.remove(reservation)

    private fun say(message: String) = _ui.update { if (it is ExtrasUi.Ready) it.copy(message = message) else it }
}
