package dev.openwrtmanager.app.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.connection.SshExecutorFactory
import dev.openwrtmanager.app.data.AuthType
import dev.openwrtmanager.app.data.Profile
import dev.openwrtmanager.app.data.ProfileRepository
import dev.openwrtmanager.app.data.SecretStore
import dev.openwrtmanager.app.router.RouterClient
import dev.openwrtmanager.app.router.RouterError
import dev.openwrtmanager.app.ssh.GeneratedKey
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.ssh.SshTarget
import dev.openwrtmanager.app.ssh.shellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

fun interface FingerprintProbe {
    suspend fun probe(host: String, port: Int): String
}

fun interface KeySource {
    fun generate(): GeneratedKey
}

enum class FailReason { UNREACHABLE, AUTH, NOT_OPENWRT, TIMEOUT, HOST_KEY, OTHER }

sealed interface Step {
    data object Editing : Step
    data object Testing : Step
    data class ConfirmHostKey(val fingerprint: String, val changed: Boolean) : Step
    data class Failed(val reason: FailReason) : Step
    data object OfferKeySetup : Step
    data object SettingUpKey : Step
    data class Saved(val keyInstalled: Boolean, val keySetupFailed: Boolean) : Step
}

data class EditDeviceUi(
    val isNew: Boolean = true,
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "root",
    val secret: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val nameError: Boolean = false,
    val hostError: Boolean = false,
    val portError: Boolean = false,
    val secretError: Boolean = false,
    val step: Step = Step.Editing,
)

@HiltViewModel
class EditDeviceViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val secrets: SecretStore,
    private val executorFactory: SshExecutorFactory,
    private val fingerprintProbe: FingerprintProbe,
    private val keySource: KeySource,
) : ViewModel() {
    private val _ui = MutableStateFlow(EditDeviceUi())
    val ui: StateFlow<EditDeviceUi> = _ui.asStateFlow()

    /** The stored profile being edited, or the one just saved. Null while adding a new device. */
    private var stored: Profile? = null
    private var loaded = false

    fun load(profileId: Long?) {
        if (loaded) return
        loaded = true
        if (profileId == null) return
        viewModelScope.launch {
            val profile = profiles.get(profileId) ?: return@launch
            stored = profile
            _ui.value = EditDeviceUi(
                isNew = false,
                name = profile.name,
                host = profile.host,
                port = profile.port.toString(),
                username = profile.username,
                authType = profile.authType,
            )
        }
    }

    fun onName(value: String) = edit { copy(name = value, nameError = false) }
    fun onHost(value: String) = edit { copy(host = value, hostError = false) }
    fun onPort(value: String) = edit { copy(port = value, portError = false) }
    fun onUsername(value: String) = edit { copy(username = value) }
    fun onSecret(value: String) = edit { copy(secret = value, secretError = false) }

    private fun edit(change: EditDeviceUi.() -> EditDeviceUi) = _ui.update {
        val next = it.change()
        if (it.step is Step.Failed) next.copy(step = Step.Editing) else next
    }

    fun testAndSave() {
        val form = _ui.value
        if (form.step !is Step.Editing && form.step !is Step.Failed) return
        val port = form.port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
        val checked = form.copy(
            nameError = form.name.isBlank(),
            hostError = form.host.isBlank(),
            portError = port == null,
            secretError = form.isNew && form.secret.isEmpty(),
        )
        if (checked.nameError || checked.hostError || checked.portError || checked.secretError) {
            _ui.value = checked.copy(step = Step.Editing)
            return
        }
        _ui.value = checked.copy(step = Step.Testing)
        val host = form.host.trim()
        viewModelScope.launch {
            val fingerprint = try {
                fingerprintProbe.probe(host, port!!)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStep(Step.Failed(reasonOf(e)))
                return@launch
            }
            val trusted = stored?.takeIf { it.host == host && it.port == port }?.hostKeyFingerprint
            if (fingerprint == trusted) {
                verifyAndSave(fingerprint)
            } else {
                setStep(Step.ConfirmHostKey(fingerprint, changed = trusted != null))
            }
        }
    }

    fun acceptHostKey() {
        val confirm = _ui.value.step as? Step.ConfirmHostKey ?: return
        setStep(Step.Testing)
        viewModelScope.launch { verifyAndSave(confirm.fingerprint) }
    }

    fun rejectHostKey() {
        if (_ui.value.step is Step.ConfirmHostKey) setStep(Step.Editing)
    }

    fun skipKeySetup() {
        if (_ui.value.step is Step.OfferKeySetup) setStep(Step.Saved(keyInstalled = false, keySetupFailed = false))
    }

    fun setUpKey() {
        if (_ui.value.step !is Step.OfferKeySetup) return
        val profile = stored ?: return
        setStep(Step.SettingUpKey)
        viewModelScope.launch {
            val installed = installKey(profile)
            setStep(Step.Saved(keyInstalled = installed, keySetupFailed = !installed))
        }
    }

    private suspend fun verifyAndSave(fingerprint: String) {
        val form = _ui.value
        val previous = stored
        // A typed secret is always a password; a blank field keeps whatever is stored.
        val authType = if (form.secret.isNotEmpty()) AuthType.PASSWORD else previous?.authType ?: AuthType.PASSWORD
        val secret = form.secret.ifEmpty { previous?.let { secrets.get(it.id) } }
        if (secret == null) {
            setStep(Step.Failed(FailReason.AUTH))
            return
        }
        val candidate = (previous ?: Profile(name = "", host = "", authType = authType)).copy(
            name = form.name.trim(),
            host = form.host.trim(),
            port = form.port.trim().toInt(),
            username = form.username.trim().ifEmpty { "root" },
            authType = authType,
            hostKeyFingerprint = fingerprint,
            lastUsedAt = System.currentTimeMillis(),
        )
        val error = check(candidate.toTarget(secret))
        if (error != null) {
            setStep(Step.Failed(reasonOf(error)))
            return
        }
        // For an existing profile the secret goes in first, so a reconnect triggered by the save sees it.
        if (candidate.id != 0L) secrets.put(candidate.id, secret)
        val id = profiles.save(candidate)
        secrets.put(id, secret)
        profiles.setActive(id)
        stored = candidate.copy(id = id)
        val offerKey = previous == null && authType == AuthType.PASSWORD
        setStep(if (offerKey) Step.OfferKeySetup else Step.Saved(keyInstalled = false, keySetupFailed = false))
    }

    /** Appends a fresh public key on the router, then switches the profile to it only if key login works. */
    private suspend fun installKey(profile: Profile): Boolean {
        val password = secrets.get(profile.id) ?: return false
        val key = keySource.generate()
        val command = "mkdir -p /etc/dropbear && echo ${shellQuote(key.publicOpenSsh)} >> /etc/dropbear/authorized_keys" +
            " && chmod 600 /etc/dropbear/authorized_keys"
        val executor = executorFactory.create(profile.toTarget(password))
        val appended = try {
            executor.exec(command).exitCode == 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            executor.close()
        }
        if (!appended) return false
        val keyProfile = profile.copy(authType = AuthType.KEY)
        if (check(keyProfile.toTarget(key.privatePem)) != null) return false
        secrets.put(profile.id, key.privatePem)
        profiles.save(keyProfile)
        stored = keyProfile
        return true
    }

    /** Connects and confirms the device answers like OpenWrt. Returns what went wrong, or null. */
    private suspend fun check(target: SshTarget): Throwable? {
        val executor = executorFactory.create(target)
        return try {
            RouterClient(executor).systemInfo()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        } finally {
            executor.close()
        }
    }

    private fun Profile.toTarget(secret: String) =
        SshTarget(host, port, username, authType, secret, hostKeyFingerprint)

    private fun setStep(step: Step) = _ui.update { it.copy(step = step) }

    private fun reasonOf(error: Throwable): FailReason = when (error) {
        is SshError.Unreachable, is SshError.Disconnected -> FailReason.UNREACHABLE
        is SshError.AuthFailed -> FailReason.AUTH
        is SshError.Timeout -> FailReason.TIMEOUT
        is SshError.HostKeyChanged, is SshError.HostKeyUnknown -> FailReason.HOST_KEY
        is RouterError.NotOpenWrt -> FailReason.NOT_OPENWRT
        else -> FailReason.OTHER
    }
}
