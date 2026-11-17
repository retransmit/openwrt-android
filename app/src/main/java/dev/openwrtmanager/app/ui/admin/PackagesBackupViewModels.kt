package dev.openwrtmanager.app.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.admin.AdminGateway
import dev.openwrtmanager.app.admin.AdminGatewayProvider
import dev.openwrtmanager.app.admin.CommandOutput
import dev.openwrtmanager.app.admin.Package
import dev.openwrtmanager.app.admin.PackageAction
import dev.openwrtmanager.app.admin.PackageTool
import dev.openwrtmanager.app.admin.parseApkList
import dev.openwrtmanager.app.admin.parseOpkgList
import dev.openwrtmanager.app.admin.removalAllowed
import dev.openwrtmanager.app.admin.systemConfigOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

private suspend fun <T> quietly(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

data class PackagesUi(
    val loading: Boolean = true,
    val offline: Boolean = false,
    val tool: PackageTool? = null,
    val installed: List<Package> = emptyList(),
    val filter: String = "",
    val results: List<Package> = emptyList(),
    val busy: Boolean = false,
    /** What the last install, remove or search printed. */
    val output: String? = null,
    val message: String? = null,
) {
    val visible: List<Package> get() = filter.trim().let { q -> if (q.isEmpty()) installed else installed.filter { it.name.contains(q, ignoreCase = true) } }
}

/**
 * Package work runs in the view model's scope, so turning the phone or leaving the screen does not
 * cut an install off halfway. Busy is always cleared when the work ends, however it ends.
 */
@HiltViewModel
class PackagesViewModel @Inject constructor(private val gateways: AdminGatewayProvider) : ViewModel() {
    private val _ui = MutableStateFlow(PackagesUi())
    val ui: StateFlow<PackagesUi> = _ui.asStateFlow()

    private var indexUpdated = false

    fun load() {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val gateway = gateways.current() ?: run { _ui.update { it.copy(loading = false, offline = true) }; return }
        val tool = _ui.value.tool ?: quietly { gateway.packageTool() }
        if (tool == null) {
            _ui.update { it.copy(loading = false, message = "This device has neither apk nor opkg.") }
            return
        }
        val listed = quietly { gateway.runPackage(tool, PackageAction.LIST_INSTALLED, null) }
        _ui.update { it.copy(loading = false, offline = false, tool = tool, installed = listed?.let { out -> parse(tool, out.text, installed = true) } ?: it.installed) }
    }

    fun setFilter(filter: String) = _ui.update { it.copy(filter = filter) }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    fun clearOutput() = _ui.update { it.copy(output = null) }

    /** Fetches the package index once per screen, then searches it. Needs internet on the device. */
    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return say("Type part of a package name.")
        val tool = _ui.value.tool ?: return
        val gateway = gateways.current() ?: return
        work {
            if (!indexUpdated) {
                val update = quietly { gateway.runPackage(tool, PackageAction.UPDATE, null) }
                if (update == null || update.exitCode != 0) {
                    _ui.update { it.copy(output = update?.text, message = "Could not fetch the package list. The device needs internet for this.") }
                    return@work
                }
                indexUpdated = true
            }
            val found = quietly { gateway.runPackage(tool, PackageAction.SEARCH, q) }
            val installed = _ui.value.installed.map { it.name }.toSet()
            _ui.update {
                when (found) {
                    null -> it.copy(message = "Use letters, digits and - . _ + in package names.")
                    else -> it.copy(
                        results = parse(tool, found.text, installed = false)
                            .filter { p -> p.name.contains(q, ignoreCase = true) }
                            .map { p -> if (p.name in installed) p.copy(installed = true) else p },
                        message = if (found.exitCode == 0 && found.text.isBlank()) "Nothing found for \"$q\"." else null,
                    )
                }
            }
        }
    }

    fun install(name: String) = run(PackageAction.INSTALL, name)

    fun remove(name: String) {
        if (!removalAllowed(name)) return say("Removing $name would cut this app off from the device.")
        run(PackageAction.REMOVE, name)
    }

    private fun run(action: PackageAction, name: String) {
        val tool = _ui.value.tool ?: return
        val gateway = gateways.current() ?: return
        work {
            val out: CommandOutput? = quietly { gateway.runPackage(tool, action, name) }
            refresh()
            _ui.update {
                it.copy(
                    output = out?.text,
                    message = when {
                        out == null -> "The device did not answer. Check the package list before trying again."
                        out.exitCode != 0 -> "The package manager reported a problem. Its output is below."
                        else -> null
                    },
                )
            }
        }
    }

    private fun work(block: suspend () -> Unit) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    private fun say(message: String) = _ui.update { it.copy(message = message) }

    private fun parse(tool: PackageTool, text: String, installed: Boolean): List<Package> = when (tool) {
        PackageTool.APK -> parseApkList(text).map { if (installed) it.copy(installed = true) else it }
        PackageTool.OPKG -> parseOpkgList(text, installed)
    }.distinctBy { it.name to it.version }.sortedBy { it.name }
}

/** Reads the whole stream, or returns null once it passes [limit] bytes. Works on every Android version. */
fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return out.toByteArray()
        out.write(buffer, 0, n)
        if (out.size() > limit) return null
    }
}

/** `backup-<hostname>-<date>.tar.gz`. */
fun backupFileName(hostname: String, date: LocalDate): String = "backup-${hostname.ifBlank { "device" }}-$date.tar.gz"

/** An uploaded, checked backup waiting for the user's confirmation. */
data class PendingRestore(val token: String, val backupHostname: String?, val deviceHostname: String?) {
    /** The backup names a different device (or none): restoring brings over its addresses, password and SSH keys. */
    val otherDevice: Boolean get() = backupHostname != deviceHostname

    override fun toString(): String = "PendingRestore(backup=$backupHostname, device=$deviceHostname)"
}

data class BackupUi(
    val hostname: String? = null,
    val busy: Boolean = false,
    /** A backup is ready to be written; the screen opens the save dialog once. */
    val saveRequested: Boolean = false,
    val pendingRestore: PendingRestore? = null,
    val message: String? = null,
)

/**
 * Downloaded bytes and uploaded restores live here, not in the screen, so a rotation while the save
 * dialog is open does not lose them, and a restore is never cut off halfway.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(private val gateways: AdminGatewayProvider) : ViewModel() {
    private val _ui = MutableStateFlow(BackupUi())
    val ui: StateFlow<BackupUi> = _ui.asStateFlow()

    private var pendingBackup: ByteArray? = null

    /** The device the pending restore was uploaded to: it is applied there or discarded there. */
    private var restoreGateway: AdminGateway? = null

    fun load() {
        viewModelScope.launch {
            val config = quietly { gateways.current()?.systemConfig() }?.let(::systemConfigOf)
            _ui.update { it.copy(hostname = config?.hostname) }
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    fun fileName(): String = backupFileName(_ui.value.hostname.orEmpty(), LocalDate.now())

    fun download() {
        val gateway = gateways.current() ?: return say("Not connected to the device.")
        work {
            val bytes = quietly { gateway.downloadBackup() }
            pendingBackup = bytes
            _ui.update { it.copy(saveRequested = bytes != null, message = if (bytes == null) "The device could not make a backup." else null) }
        }
    }

    fun saveRequestHandled() = _ui.update { it.copy(saveRequested = false) }

    /** The downloaded backup, once. Null when there is none, for example after it was already saved. */
    fun takePendingBackup(): ByteArray? = pendingBackup.also { pendingBackup = null }

    /** Uploads and checks the file, then waits for the user to confirm with the device's name. */
    fun prepareRestore(bytes: ByteArray) {
        val gateway = gateways.current() ?: return say("Not connected to the device.")
        work {
            val token = quietly { gateway.uploadRestore(bytes) }
            if (token == null) {
                say("This file is not a backup this device can restore. Nothing was changed.")
                return@work
            }
            restoreGateway = gateway
            val backupHostname = quietly { gateway.restoreHostname(token) }
            _ui.update { it.copy(pendingRestore = PendingRestore(token, backupHostname, it.hostname)) }
        }
    }

    /** Only after the user typed the device's name. Cannot be undone. */
    fun confirmRestore(typedName: String) {
        val pending = _ui.value.pendingRestore ?: return
        val hostname = _ui.value.hostname
        if (hostname == null || typedName != hostname) return say("Type the device name exactly to confirm.")
        val gateway = restoreGateway ?: return say("Not connected to the device.")
        work {
            val ok = quietly { gateway.applyRestore(pending.token) } == true
            _ui.update {
                it.copy(
                    pendingRestore = null,
                    message = if (ok) {
                        "Restored. The device is rebooting; the app reconnects when it is back."
                    } else {
                        "The device reported a problem while restoring. Some settings may already be replaced and take effect at the next reboot. Check the device before rebooting it."
                    },
                )
            }
        }
    }

    fun cancelRestore() {
        val pending = _ui.value.pendingRestore ?: return
        val gateway = restoreGateway
        _ui.update { it.copy(pendingRestore = null) }
        viewModelScope.launch {
            withContext(NonCancellable) { quietly { gateway?.discardRestore(pending.token) } }
        }
    }

    private fun work(block: suspend () -> Unit) {
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    private fun say(message: String) = _ui.update { it.copy(message = message) }
}
