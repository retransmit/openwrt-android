package dev.openwrtmanager.app.ui.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.connection.ConnectionManager
import dev.openwrtmanager.app.connection.ConnectionState
import dev.openwrtmanager.app.data.Profile
import dev.openwrtmanager.app.domain.Band
import dev.openwrtmanager.app.domain.Rate
import dev.openwrtmanager.app.domain.RateSampler
import dev.openwrtmanager.app.domain.SystemInfo
import dev.openwrtmanager.app.domain.WifiNetwork
import dev.openwrtmanager.app.router.RouterClient
import dev.openwrtmanager.app.router.RouterError
import dev.openwrtmanager.app.ssh.SshError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

fun interface TimeSource {
    /** Monotonic milliseconds. */
    fun nowMillis(): Long
}

sealed interface HomeStatus {
    data object NoDevice : HomeStatus
    data object Connecting : HomeStatus
    data object Online : HomeStatus
    data class Offline(val error: Throwable) : HomeStatus
}

/** One SSID as the user thinks of it, even when it is broadcast on several bands. */
data class WifiRow(
    val ssid: String,
    val bands: List<Band>,
    val encrypted: Boolean,
    val up: Boolean,
    val clients: Int,
)

data class HomeUi(
    val deviceName: String = "",
    val status: HomeStatus = HomeStatus.NoDevice,
    /** True once the first full load for this device has finished. */
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val uplinkPresent: Boolean = false,
    val rate: Rate? = null,
    val history: List<Rate> = emptyList(),
    val wifi: List<WifiRow> = emptyList(),
    val clientTotal: Int? = null,
    val system: SystemInfo? = null,
)

fun mergeWifi(networks: List<WifiNetwork>): List<WifiRow> =
    networks.groupBy { it.ssid to it.encryption }.map { (key, group) ->
        WifiRow(
            ssid = key.first,
            bands = group.map { it.band }.distinct().sortedBy { it.ordinal },
            encrypted = key.second != "none",
            up = group.any { it.up },
            clients = group.sumOf { it.clientCount },
        )
    }

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val connection: ConnectionManager,
    private val time: TimeSource,
) : ViewModel() {
    private val _ui = MutableStateFlow(HomeUi())
    val ui: StateFlow<HomeUi> = _ui.asStateFlow()

    private val sampler = RateSampler()
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private var profileId: Long? = null
    private var session: RouterClient? = null
    private var uplink: String? = null

    /**
     * Follows the connection and polls the router while it is online. Runs until cancelled:
     * the screen calls this only while it is visible, so nothing is polled in the background.
     */
    suspend fun poll() {
        connection.state.collectLatest { state ->
            when (state) {
                ConnectionState.NoProfile -> {
                    profileId = null
                    session = null
                    _ui.value = HomeUi()
                }
                is ConnectionState.Connecting -> {
                    show(state.profile)
                    _ui.update { it.copy(status = HomeStatus.Connecting) }
                }
                is ConnectionState.Offline -> {
                    show(state.profile)
                    _ui.update { it.copy(status = HomeStatus.Offline(state.error), refreshing = false) }
                }
                is ConnectionState.Online -> {
                    show(state.profile)
                    sampler.reset()
                    if (session !== state.router) {
                        // A new connection: counters may have restarted, so old samples no longer line up.
                        session = state.router
                        uplink = null
                        _ui.update { it.copy(rate = null, history = emptyList()) }
                    }
                    _ui.update { it.copy(status = HomeStatus.Online) }
                    runSession(state.router)
                }
            }
        }
    }

    /** Pull to refresh. Reloads now when online, otherwise asks for a reconnect. */
    fun refresh() {
        if (_ui.value.status is HomeStatus.Online) {
            _ui.update { it.copy(refreshing = true) }
            refreshRequests.trySend(Unit)
        } else {
            connection.retryNow()
        }
    }

    private fun show(profile: Profile) {
        if (profileId != profile.id) {
            profileId = profile.id
            session = null
            _ui.value = HomeUi()
        }
        _ui.update { it.copy(deviceName = profile.name) }
    }

    private suspend fun runSession(router: RouterClient) {
        try {
            coroutineScope {
                refreshSlow(router)
                launch {
                    while (true) {
                        sampleRate(router)
                        delay(FAST_MS)
                    }
                }
                launch {
                    while (true) {
                        withTimeoutOrNull(SLOW_MS) { refreshRequests.receive() }
                        refreshSlow(router)
                    }
                }
            }
        } catch (e: SshError) {
            // The transport failed. Stop polling; the manager reconnects and a new session starts.
            connection.reportFailure(e)
        }
    }

    private suspend fun sampleRate(router: RouterClient) {
        val device = uplink ?: return
        val counters = soft { router.counters(device) } ?: return
        val rate = sampler.sample(counters, time.nowMillis()) ?: return
        _ui.update { it.copy(rate = rate, history = (it.history + rate).takeLast(HISTORY_SIZE)) }
    }

    private suspend fun refreshSlow(router: RouterClient) {
        val system = soft { router.systemInfo() }
        val wifi = soft { router.wifiNetworks() }
        val leases = soft { router.leaseCount() }
        val device = soft { router.uplinkDevice() }
        if (device != uplink) {
            uplink = device
            sampler.reset()
            _ui.update { it.copy(rate = null, history = emptyList()) }
        }
        _ui.update { old ->
            val rows = wifi?.let(::mergeWifi) ?: old.wifi
            old.copy(
                loaded = true,
                refreshing = false,
                uplinkPresent = device != null,
                system = system ?: old.system,
                wifi = rows,
                // Leases miss clients served by another DHCP server; Wi-Fi counts miss wired ones. Show the larger.
                clientTotal = maxOf(leases ?: 0, rows.sumOf { it.clients }),
            )
        }
    }

    /** One odd answer from the router should not blank the screen: keep going with what we have. */
    private suspend fun <T> soft(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: RouterError) {
            null
        }

    private companion object {
        const val FAST_MS = 2_000L
        const val SLOW_MS = 10_000L
        const val HISTORY_SIZE = 60
    }
}
