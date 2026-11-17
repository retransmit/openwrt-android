package dev.openwrtmanager.app.ui.wifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.connection.ProfileSessions
import dev.openwrtmanager.app.data.Profile
import dev.openwrtmanager.app.data.ProfileRepository
import dev.openwrtmanager.app.uci.UciClient
import dev.openwrtmanager.app.vlan.ApMirror
import dev.openwrtmanager.app.vlan.MirrorBlock
import dev.openwrtmanager.app.vlan.MirrorPlan
import dev.openwrtmanager.app.vlan.PortRole
import dev.openwrtmanager.app.vlan.SourceNetwork
import dev.openwrtmanager.app.vlan.sourceNetworksOf
import dev.openwrtmanager.app.vlan.vlanLayoutOf
import dev.openwrtmanager.app.wifi.DeviceSnapshot
import dev.openwrtmanager.app.wifi.WifiGatewayProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class MirrorUi(
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** Saved devices other than this one, to read the networks from. */
    val routers: List<Profile> = emptyList(),
    val router: Profile? = null,
    val readingRouter: Boolean = false,
    val sources: List<SourceNetwork> = emptyList(),
    val chosen: Set<String> = emptySet(),
    /** This device's bridge ports, to pick the one cabled to the router. */
    val ports: List<String> = emptyList(),
    val uplink: String? = null,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    val blocked: MirrorBlock? = null,
)

/**
 * "Add a network from my router": reads the router's VLAN networks over a connection of its own,
 * closes it, and plans the same networks on this access point. Keys stay in memory only.
 */
@HiltViewModel
class MirrorViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val sessions: ProfileSessions,
    private val gateways: WifiGatewayProvider,
) : ViewModel() {
    private val _ui = MutableStateFlow(MirrorUi())
    val ui: StateFlow<MirrorUi> = _ui.asStateFlow()

    private var snapshot: DeviceSnapshot? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            try {
                val snap = gateways.current()?.snapshot() ?: throw IllegalStateException("not connected")
                snapshot = snap
                val activeId = profiles.observeActiveId().first()
                val ports = vlanLayoutOf(snap.network)?.ports.orEmpty()
                _ui.value = MirrorUi(
                    loading = false,
                    routers = profiles.observeAll().first().filter { it.id != activeId },
                    ports = ports,
                    uplink = if ("wan" in ports) "wan" else ports.firstOrNull(),
                    blocked = when {
                        snap.hasUplink -> MirrorBlock.NOT_AN_ACCESS_POINT
                        ports.isEmpty() -> MirrorBlock.NOT_DSA
                        else -> null
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = MirrorUi(loading = false, failed = true)
            }
        }
    }

    fun pickRouter(id: Long) {
        val profile = _ui.value.routers.firstOrNull { it.id == id } ?: return
        _ui.update { it.copy(router = profile, readingRouter = true, sources = emptyList(), chosen = emptySet(), warnings = emptyList(), error = null) }
        viewModelScope.launch {
            try {
                val ssh = sessions.open(id) ?: throw IllegalStateException("no saved login")
                try {
                    val uci = UciClient(ssh)
                    val network = uci.load("network")
                    val sources = sourceNetworksOf(network, uci.load("wireless"))
                    val layout = vlanLayoutOf(network)
                    val warnings = sources.filter { source -> layout?.vlan(source.vid)?.roles?.values?.none { it == PortRole.TAGGED } != false }
                        .map {
                            "No port on ${profile.name} carries ${it.name} tagged yet. On ${profile.name}, open the Network tab and set the port " +
                                "this device is plugged into as an AP link for ${it.name}."
                        }
                    _ui.update { it.copy(readingRouter = false, sources = sources, chosen = sources.map { s -> s.name }.toSet(), warnings = warnings) }
                } finally {
                    withContext(NonCancellable) { runCatching { ssh.close() } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(readingRouter = false, error = "Could not read the networks on ${profile.name}.") }
            }
        }
    }

    fun toggle(name: String) = _ui.update { it.copy(chosen = if (name in it.chosen) it.chosen - name else it.chosen + name, blocked = null) }

    fun onUplink(port: String) = _ui.update { it.copy(uplink = port, blocked = null) }

    fun review(): ChangeSet? {
        val ui = _ui.value
        val snap = snapshot ?: return null
        if (ui.router == null || ui.error != null) return null
        val uplink = ui.uplink ?: return null
        return when (val plan = ApMirror.plan(snap, ui.sources.filter { it.name in ui.chosen }, uplink)) {
            is MirrorPlan.Ready -> plan.change
            is MirrorPlan.Blocked -> {
                _ui.update { it.copy(blocked = plan.reason) }
                null
            }
        }
    }
}
