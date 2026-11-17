package dev.openwrtmanager.app.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.admin.AdminGatewayProvider
import dev.openwrtmanager.app.admin.LogKind
import dev.openwrtmanager.app.admin.Service
import dev.openwrtmanager.app.admin.ServiceAction
import dev.openwrtmanager.app.admin.SystemConfig
import dev.openwrtmanager.app.admin.SystemPlanner
import dev.openwrtmanager.app.admin.actionAllowed
import dev.openwrtmanager.app.admin.filterLines
import dev.openwrtmanager.app.admin.isValidHostname
import dev.openwrtmanager.app.admin.isValidPassword
import dev.openwrtmanager.app.admin.mergeFollow
import dev.openwrtmanager.app.admin.systemConfigOf
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.data.AuthType
import dev.openwrtmanager.app.data.ProfileRepository
import dev.openwrtmanager.app.data.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Swallows ordinary failures into null; lets cancellation through. */
private suspend fun <T> soft(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

sealed interface ServicesUi {
    data object Loading : ServicesUi
    data object Offline : ServicesUi
    data object Failed : ServicesUi
    data class Ready(val services: List<Service>, val message: String? = null) : ServicesUi
}

@HiltViewModel
class ServicesViewModel @Inject constructor(private val gateways: AdminGatewayProvider) : ViewModel() {
    private val _ui = MutableStateFlow<ServicesUi>(ServicesUi.Loading)
    val ui: StateFlow<ServicesUi> = _ui.asStateFlow()

    fun load() {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val gateway = gateways.current() ?: run { _ui.value = ServicesUi.Offline; return }
        val services = soft { gateway.services() }
        _ui.value = when {
            services != null -> ServicesUi.Ready(services, (_ui.value as? ServicesUi.Ready)?.message)
            _ui.value is ServicesUi.Ready -> _ui.value
            else -> ServicesUi.Failed
        }
    }

    fun run(service: Service, action: ServiceAction) {
        viewModelScope.launch {
            if (!actionAllowed(service.name, action)) {
                val doing = if (action == ServiceAction.STOP) "Stopping" else "Disabling"
                say("$doing ${service.name} would cut this app off from the device.")
                return@launch
            }
            val ok = soft { gateways.current()?.runService(service.name, action) } == true
            refresh()
            say(if (ok) "${service.name}: ${action.verb} done." else "The router could not ${action.verb} ${service.name}.")
        }
    }

    fun clearMessage() = _ui.update { if (it is ServicesUi.Ready) it.copy(message = null) else it }

    private fun say(message: String) = _ui.update { if (it is ServicesUi.Ready) it.copy(message = message) else it }
}

data class LogsUi(
    val kind: LogKind = LogKind.SYSTEM,
    val lines: List<String> = emptyList(),
    val query: String = "",
    val following: Boolean = true,
    val offline: Boolean = false,
    val failed: Boolean = false,
) {
    val visible: List<String> get() = filterLines(lines, query)
}

@HiltViewModel
class LogsViewModel @Inject constructor(private val gateways: AdminGatewayProvider) : ViewModel() {
    private val _ui = MutableStateFlow(LogsUi())
    val ui: StateFlow<LogsUi> = _ui.asStateFlow()

    /** Follows the log every 2 s until cancelled; the screen runs this only while it is visible. */
    suspend fun poll() {
        while (true) {
            if (_ui.value.following) fetch()
            delay(FOLLOW_MS)
        }
    }

    fun setKind(kind: LogKind) {
        if (kind == _ui.value.kind) return
        _ui.update { it.copy(kind = kind, lines = emptyList()) }
        viewModelScope.launch { fetch() }
    }

    fun setQuery(query: String) = _ui.update { it.copy(query = query) }

    fun setFollowing(on: Boolean) = _ui.update { it.copy(following = on) }

    private suspend fun fetch() {
        val gateway = gateways.current() ?: run { _ui.update { it.copy(offline = true) }; return }
        val kind = _ui.value.kind
        val fresh = soft { gateway.log(kind) }
        _ui.update { current ->
            when {
                current.kind != kind -> current
                fresh == null -> current.copy(failed = true, offline = false)
                else -> current.copy(lines = mergeFollow(current.lines, fresh), failed = false, offline = false)
            }
        }
    }

    private companion object {
        const val FOLLOW_MS = 2_000L
    }
}

fun interface ZoneSource {
    /** Zone name and POSIX TZ string. */
    fun zones(): List<Pair<String, String>>
}

sealed interface SystemUi {
    data object Loading : SystemUi
    data object Offline : SystemUi
    data object Failed : SystemUi

    data class Ready(val config: SystemConfig, val zones: List<Pair<String, String>>, val message: String? = null) : SystemUi {
        override fun toString(): String = "Ready($config, ${zones.size} zones, message=$message)"
    }
}

@HiltViewModel
class SystemViewModel @Inject constructor(
    private val gateways: AdminGatewayProvider,
    private val profiles: ProfileRepository,
    private val secrets: SecretStore,
    private val zoneSource: ZoneSource,
) : ViewModel() {
    private val _ui = MutableStateFlow<SystemUi>(SystemUi.Loading)
    val ui: StateFlow<SystemUi> = _ui.asStateFlow()

    private val zones by lazy { zoneSource.zones() }

    fun load() {
        viewModelScope.launch {
            val gateway = gateways.current() ?: run { _ui.value = SystemUi.Offline; return@launch }
            val config = soft { gateway.systemConfig() }?.let(::systemConfigOf)
            _ui.value = when {
                config != null -> SystemUi.Ready(config, zones, (_ui.value as? SystemUi.Ready)?.message)
                _ui.value is SystemUi.Ready -> _ui.value
                else -> SystemUi.Failed
            }
        }
    }

    fun clearMessage() = _ui.update { if (it is SystemUi.Ready) it.copy(message = null) else it }

    /** Planned from the device's config as it is now. Null when unchanged or invalid. */
    suspend fun planHostname(name: String): ChangeSet? {
        if (!isValidHostname(name)) {
            say("Use letters, digits and hyphens, up to 63, not starting or ending with a hyphen.")
            return null
        }
        val current = fresh() ?: return null
        return SystemPlanner.hostname(current, name)
    }

    suspend fun planTimezone(zone: String): ChangeSet? {
        val tz = zones.firstOrNull { it.first == zone }?.second ?: run { say("Unknown time zone."); return null }
        val current = fresh() ?: return null
        return SystemPlanner.timezone(current, zone, tz)
    }

    suspend fun reboot() {
        val ok = soft { gateways.current()?.reboot() } == true
        say(if (ok) "Rebooting. The app reconnects when the device is back." else "The device did not accept the reboot.")
    }

    /** Changes the root password; updates the app's saved login when it uses this password. */
    suspend fun changePassword(password: String, repeat: String): Boolean {
        if (password != repeat) return fail("The two passwords do not match.")
        if (!isValidPassword(password)) return fail("Use 8 to 128 characters: letters, digits, spaces and symbols from a standard keyboard.")
        val ok = soft { gateways.current()?.setRootPassword(password) } == true
        if (!ok) return fail("The router did not accept the new password. Nothing was changed.")
        val id = profiles.observeActiveId().first()
        val profile = id?.let { profiles.get(it) }
        if (profile != null && profile.authType == AuthType.PASSWORD) {
            secrets.put(profile.id, password)
            say("Password changed. The app's saved login was updated too.")
        } else {
            say("Password changed.")
        }
        return true
    }

    private suspend fun fresh(): SystemConfig? =
        soft { gateways.current()?.systemConfig() }?.let(::systemConfigOf) ?: run {
            say("Could not read the device's current settings. Nothing was changed.")
            null
        }

    private fun fail(message: String): Boolean {
        say(message)
        return false
    }

    private fun say(message: String) = _ui.update { if (it is SystemUi.Ready) it.copy(message = message) else it }
}
