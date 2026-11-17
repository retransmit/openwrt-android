package dev.openwrtmanager.app.ui.wifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.domain.Band
import dev.openwrtmanager.app.vlan.PortRole
import dev.openwrtmanager.app.vlan.vlanLayoutOf
import dev.openwrtmanager.app.wifi.BlockReason
import dev.openwrtmanager.app.wifi.DeviceSnapshot
import dev.openwrtmanager.app.wifi.KeyError
import dev.openwrtmanager.app.wifi.NetworkWizard
import dev.openwrtmanager.app.wifi.Radio
import dev.openwrtmanager.app.wifi.Security
import dev.openwrtmanager.app.wifi.SsidError
import dev.openwrtmanager.app.wifi.WifiGatewayProvider
import dev.openwrtmanager.app.wifi.WizardInput
import dev.openwrtmanager.app.wifi.WizardKind
import dev.openwrtmanager.app.wifi.WizardPlan
import dev.openwrtmanager.app.wifi.validateKey
import dev.openwrtmanager.app.wifi.validateSsid
import dev.openwrtmanager.app.wifi.wifiConfigOf
import dev.openwrtmanager.app.wifi.wifiQrPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Random
import javax.inject.Inject

fun interface PasswordSource {
    fun next(): String
}

private const val PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

/** Twelve characters that are easy to read aloud and type: no 0, O, 1, l or I. */
fun generateWifiPassword(random: Random): String =
    (1..12).map { PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)] }.joinToString("")

data class WizardUi(
    val kind: WizardKind = WizardKind.GUEST,
    val loaded: Boolean = false,
    val failed: Boolean = false,
    val blocked: BlockReason? = null,
    val ssid: String = "",
    val key: String = "",
    val security: Security = Security.WPA2_WPA3,
    val radios: Set<String> = emptySet(),
    val availableRadios: List<Radio> = emptyList(),
    val lanCanReachIot: Boolean = true,
    val ssidError: SsidError? = null,
    val keyError: KeyError? = null,
    val subnet: String? = null,
    val qrPayload: String? = null,
    /** Bridge ports the network can also be put on. Empty on a device without a DSA bridge. */
    val ports: List<String> = emptyList(),
    val portRoles: Map<String, PortRole> = emptyMap(),
) {
    override fun toString(): String = "WizardUi($kind, ssid=$ssid, radios=$radios, blocked=$blocked, loaded=$loaded)"
}

@HiltViewModel
class WizardViewModel @Inject constructor(
    private val gateways: WifiGatewayProvider,
    private val passwords: PasswordSource,
) : ViewModel() {
    private val _ui = MutableStateFlow(WizardUi())
    val ui: StateFlow<WizardUi> = _ui.asStateFlow()

    private var snapshot: DeviceSnapshot? = null
    private var started = false

    fun start(kind: WizardKind) {
        if (started) return
        started = true
        viewModelScope.launch {
            try {
                val snap = gateways.current()?.snapshot() ?: throw IllegalStateException("not connected")
                snapshot = snap
                val wifi = wifiConfigOf(snap.wireless)
                val home = wifi.groups.firstOrNull { it.first.network == "lan" } ?: wifi.groups.firstOrNull()
                val suffix = if (kind == WizardKind.GUEST) "-Guest" else "-IoT"
                val lowBand = wifi.radios.filter { it.band == Band.GHZ_2_4 }.map { it.name }.toSet()
                val all = wifi.radios.map { it.name }.toSet()
                _ui.value = WizardUi(
                    kind = kind,
                    loaded = true,
                    blocked = if (snap.hasUplink) null else BlockReason.NO_UPLINK,
                    ssid = (home?.ssid ?: "Home") + suffix,
                    key = passwords.next(),
                    // Most smart home devices only speak 2.4 GHz, so that is where an IoT network starts.
                    radios = if (kind == WizardKind.IOT && lowBand.isNotEmpty()) lowBand else all,
                    availableRadios = wifi.radios,
                    ports = vlanLayoutOf(snap.network)?.ports.orEmpty(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.value = WizardUi(kind = kind, failed = true)
            }
        }
    }

    fun onSsid(value: String) = _ui.update { it.copy(ssid = value, ssidError = null) }
    fun onKey(value: String) = _ui.update { it.copy(key = value, keyError = null) }
    fun regeneratePassword() = _ui.update { it.copy(key = passwords.next(), keyError = null) }
    fun onRadio(radio: String, on: Boolean) = _ui.update { it.copy(radios = if (on) it.radios + radio else it.radios - radio) }
    fun onLanCanReachIot(value: Boolean) = _ui.update { it.copy(lanCanReachIot = value) }
    fun onPortRole(port: String, role: PortRole) = _ui.update {
        it.copy(portRoles = if (role == PortRole.NONE) it.portRoles - port else it.portRoles + (port to role))
    }

    /** Validates the form and returns the change to review, or null with errors or a block reason set. */
    fun review(): ChangeSet? {
        val ui = _ui.value
        val snap = snapshot ?: return null
        val ssidError = validateSsid(ui.ssid)
        val keyError = validateKey(ui.key, ui.security)
        if (ssidError != null || keyError != null) {
            _ui.update { it.copy(ssidError = ssidError, keyError = keyError) }
            return null
        }
        val input = WizardInput(ui.kind, ui.ssid, ui.security, ui.key, ui.radios, ui.lanCanReachIot, ports = ui.portRoles)
        return when (val plan = NetworkWizard.plan(snap, input)) {
            is WizardPlan.Ready -> {
                _ui.update { it.copy(subnet = plan.subnet) }
                plan.change
            }
            is WizardPlan.Blocked -> {
                _ui.update { it.copy(blocked = plan.reason) }
                null
            }
        }
    }

    /** Called once the change is confirmed on the router: now there is a network to join. */
    fun onApplied() = _ui.update { it.copy(qrPayload = wifiQrPayload(it.ssid, it.security, it.key, hidden = false)) }
}
