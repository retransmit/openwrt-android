package dev.openwrtmanager.app.ui.wifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.wifi.DeviceSnapshot
import dev.openwrtmanager.app.wifi.KeyError
import dev.openwrtmanager.app.wifi.NetworkEdit
import dev.openwrtmanager.app.wifi.NetworkWizard
import dev.openwrtmanager.app.wifi.Radio
import dev.openwrtmanager.app.wifi.Security
import dev.openwrtmanager.app.wifi.SsidError
import dev.openwrtmanager.app.wifi.Steering
import dev.openwrtmanager.app.wifi.SteeringRequest
import dev.openwrtmanager.app.wifi.UsteerStatus
import dev.openwrtmanager.app.wifi.WifiConfig
import dev.openwrtmanager.app.wifi.WifiEdits
import dev.openwrtmanager.app.wifi.WifiGatewayProvider
import dev.openwrtmanager.app.wifi.WifiGroup
import dev.openwrtmanager.app.wifi.validateKey
import dev.openwrtmanager.app.wifi.validateSsid
import dev.openwrtmanager.app.wifi.wifiConfigOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NetworkForm(
    val loaded: Boolean = false,
    val failed: Boolean = false,
    val isNew: Boolean = true,
    val ssid: String = "",
    val security: Security = Security.WPA2_WPA3,
    val key: String = "",
    val radios: Set<String> = emptySet(),
    val hidden: Boolean = false,
    val isolate: Boolean = false,
    val enabled: Boolean = true,
    val availableRadios: List<Radio> = emptyList(),
    val ssidError: SsidError? = null,
    val keyError: KeyError? = null,
    val radioError: Boolean = false,
    val canSave: Boolean = false,
    val steeringAssist: Boolean = false,
    val fastRoaming: Boolean = false,
    val usteer: UsteerStatus? = null,
    val usteerBusy: Boolean = false,
) {
    /** The password is part of the form but must never be printed. */
    override fun toString(): String = "NetworkForm(ssid=$ssid, security=$security, radios=$radios, loaded=$loaded)"
}

@HiltViewModel
class NetworkEditViewModel @Inject constructor(
    private val gateways: WifiGatewayProvider,
) : ViewModel() {
    private val _form = MutableStateFlow(NetworkForm())
    val form: StateFlow<NetworkForm> = _form.asStateFlow()

    private var snapshot: DeviceSnapshot? = null
    private var config: WifiConfig? = null
    private var group: WifiGroup? = null
    private var initial: NetworkEdit? = null
    private var started = false

    /** [section] is any interface section of the network to edit, or null to add a new network. */
    fun start(section: String?) {
        if (started) return
        started = true
        viewModelScope.launch {
            val gateway = gateways.current()
            try {
                val snap = gateway?.snapshot() ?: throw IllegalStateException("not connected")
                val wifi = wifiConfigOf(snap.wireless)
                val found = section?.let { s -> wifi.groups.firstOrNull { g -> g.ifaces.any { it.section == s } } }
                snapshot = snap
                config = wifi
                group = found
                val allRadios = wifi.radios.map { it.name }.toSet()
                val start = if (found == null) {
                    NetworkEdit("", Security.WPA2_WPA3, "", hidden = false, isolate = false, enabled = true, radios = allRadios)
                } else {
                    val first = found.first
                    NetworkEdit(
                        ssid = found.ssid,
                        security = first.security ?: Security.WPA2,
                        key = first.key.orEmpty(),
                        hidden = first.hidden,
                        isolate = first.isolate,
                        enabled = found.enabled,
                        radios = found.ifaces.map { it.radio }.toSet(),
                    )
                }
                initial = start
                val usteer = runCatching { gateway.usteerStatus() }.getOrNull()
                _form.value = NetworkForm(
                    loaded = true,
                    isNew = found == null,
                    ssid = start.ssid,
                    security = start.security,
                    key = start.key.orEmpty(),
                    radios = start.radios,
                    hidden = start.hidden,
                    isolate = start.isolate,
                    enabled = start.enabled,
                    availableRadios = wifi.radios,
                    steeringAssist = found?.steeringAssist ?: false,
                    fastRoaming = found?.fastRoaming ?: false,
                    usteer = usteer,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _form.value = NetworkForm(failed = true)
            }
        }
    }

    fun onSsid(value: String) = edit { copy(ssid = value, ssidError = null) }
    fun onSecurity(value: Security) = edit { copy(security = value, keyError = null) }
    fun onKey(value: String) = edit { copy(key = value, keyError = null) }
    fun onHidden(value: Boolean) = edit { copy(hidden = value) }
    fun onIsolate(value: Boolean) = edit { copy(isolate = value) }
    fun onEnabled(value: Boolean) = edit { copy(enabled = value) }
    fun onRadio(radio: String, on: Boolean) = edit { copy(radios = if (on) radios + radio else radios - radio, radioError = false) }

    private fun edit(change: NetworkForm.() -> NetworkForm) = _form.update {
        val next = it.change()
        next.copy(canSave = next.loaded && next.toEdit() != initial)
    }

    private fun NetworkForm.toEdit() = NetworkEdit(ssid, security, key, hidden, isolate, enabled, radios)

    /** Validates and returns the change to review, or null when there is nothing valid to save. */
    fun save(): ChangeSet? {
        val form = _form.value
        val wifi = config ?: return null
        if (!form.canSave) return null
        val ssidError = validateSsid(form.ssid)
        val keyError = validateKey(form.key, form.security)
        val radioError = form.radios.isEmpty()
        if (ssidError != null || keyError != null || radioError) {
            _form.update { it.copy(ssidError = ssidError, keyError = keyError, radioError = radioError) }
            return null
        }
        val edit = form.toEdit()
        val existing = group
        val change = if (existing == null) WifiEdits.addNetwork(wifi, edit, HOME_NETWORK) else WifiEdits.editGroup(wifi, existing, edit)
        return change.takeUnless { it.isEmpty() }
    }

    /** Band steering is its own change, separate from Save. Null for a network that does not exist yet. */
    fun steeringChange(enable: Boolean, fastRoaming: Boolean): ChangeSet? {
        val wifi = config ?: return null
        val existing = group ?: return null
        return try {
            if (enable) {
                Steering.enable(wifi, existing, SteeringRequest(wifi.radios.map { it.name }.toSet(), fastRoaming))
            } else {
                Steering.disable(wifi, existing)
            }
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Removing a network made by the guest or IoT wizard takes its address range and firewall rules
     * with it. Removing an ordinary network only removes its Wi-Fi.
     */
    fun removeChange(): ChangeSet? {
        val wifi = config ?: return null
        val snap = snapshot ?: return null
        val existing = group ?: return null
        val network = existing.first.network
        val ownsNetwork = network != null && network !in BUILT_IN && snap.network[network] != null &&
            wifi.ifaces.filter { it.network == network }.all { it in existing.ifaces }
        return if (ownsNetwork) NetworkWizard.remove(snap, network!!) else WifiEdits.deleteGroup(wifi, existing)
    }

    fun startUsteer() = usteerAction { it.startUsteer() }

    fun installUsteer() = usteerAction { it.installUsteer() }

    private fun usteerAction(action: suspend (dev.openwrtmanager.app.wifi.WifiGateway) -> Boolean) {
        val gateway = gateways.current() ?: return
        _form.update { it.copy(usteerBusy = true) }
        viewModelScope.launch {
            try {
                action(gateway)
                val status = gateway.usteerStatus()
                _form.update { it.copy(usteer = status) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave the last known status in place.
            } finally {
                _form.update { it.copy(usteerBusy = false) }
            }
        }
    }

    private companion object {
        const val HOME_NETWORK = "lan"
        val BUILT_IN = setOf("lan", "wan", "wan6", "loopback")
    }
}
