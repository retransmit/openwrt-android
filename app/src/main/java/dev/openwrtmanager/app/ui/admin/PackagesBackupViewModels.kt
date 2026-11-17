package dev.openwrtmanager.app.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.admin.AdminGatewayProvider
import dev.openwrtmanager.app.admin.CommandOutput
import dev.openwrtmanager.app.admin.Package
import dev.openwrtmanager.app.admin.PackageAction
import dev.openwrtmanager.app.admin.PackageTool
import dev.openwrtmanager.app.admin.parseApkList
import dev.openwrtmanager.app.admin.parseOpkgList
import dev.openwrtmanager.app.admin.systemConfigOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    /** Fetches the package index once per screen, then searches it. Needs internet on the device. */
    suspend fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return say("Type part of a package name.")
        val tool = _ui.value.tool ?: return
        val gateway = gateways.current() ?: return
        _ui.update { it.copy(busy = true) }
        if (!indexUpdated) {
            val update = quietly { gateway.runPackage(tool, PackageAction.UPDATE, null) }
            if (update == null || update.exitCode != 0) {
                _ui.update { it.copy(busy = false, output = update?.text, message = "Could not fetch the package list. The device needs internet for this.") }
                return
            }
            indexUpdated = true
        }
        val found = quietly { gateway.runPackage(tool, PackageAction.SEARCH, q) }
        _ui.update {
            when (found) {
                null -> it.copy(busy = false, message = "Use letters, digits and - . _ + in package names.")
                else -> it.copy(busy = false, results = parse(tool, found.text, installed = false).filter { p -> p.name.contains(q, ignoreCase = true) })
            }
        }
    }

    suspend fun install(name: String) = run(PackageAction.INSTALL, name)

    suspend fun remove(name: String) = run(PackageAction.REMOVE, name)

    private suspend fun run(action: PackageAction, name: String) {
        val tool = _ui.value.tool ?: return
        val gateway = gateways.current() ?: return
        _ui.update { it.copy(busy = true) }
        val out: CommandOutput? = quietly { gateway.runPackage(tool, action, name) }
        refresh()
        _ui.update {
            it.copy(
                busy = false,
                output = out?.text,
                message = when {
                    out == null -> "The device did not answer. Check the package list before trying again."
                    out.exitCode != 0 -> "The package manager reported a problem. Its output is below."
                    else -> null
                },
            )
        }
    }

    private fun say(message: String) = _ui.update { it.copy(message = message) }

    private fun parse(tool: PackageTool, text: String, installed: Boolean): List<Package> = when (tool) {
        PackageTool.APK -> parseApkList(text).map { if (installed) it.copy(installed = true) else it }
        PackageTool.OPKG -> parseOpkgList(text, installed)
    }.sortedBy { it.name }
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

data class BackupUi(val hostname: String? = null, val busy: Boolean = false, val message: String? = null)

@HiltViewModel
class BackupViewModel @Inject constructor(private val gateways: AdminGatewayProvider) : ViewModel() {
    private val _ui = MutableStateFlow(BackupUi())
    val ui: StateFlow<BackupUi> = _ui.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val config = quietly { gateways.current()?.systemConfig() }?.let(::systemConfigOf)
            _ui.update { it.copy(hostname = config?.hostname) }
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    fun fileName(): String = backupFileName(_ui.value.hostname.orEmpty(), LocalDate.now())

    suspend fun download(): ByteArray? {
        _ui.update { it.copy(busy = true) }
        val bytes = quietly { gateways.current()?.downloadBackup() }
        _ui.update { it.copy(busy = false, message = if (bytes == null) "The device could not make a backup." else null) }
        return bytes
    }

    /** Only after the user typed the device's name; cannot be undone. */
    suspend fun restore(bytes: ByteArray, typedName: String): Boolean {
        val hostname = _ui.value.hostname
        if (hostname == null || typedName != hostname) return fail("Type the device name exactly to confirm.")
        val gateway = gateways.current() ?: return fail("Not connected to the device.")
        _ui.update { it.copy(busy = true) }
        val token = quietly { gateway.uploadRestore(bytes) }
            ?: return fail("This file is not a backup this device can restore. Nothing was changed.")
        val ok = quietly { gateway.applyRestore(token) } == true
        if (!ok) {
            quietly { gateway.discardRestore(token) }
            return fail("The device could not restore the backup.")
        }
        _ui.update { it.copy(busy = false, message = "Restored. The device is rebooting; the app reconnects when it is back.") }
        return true
    }

    private fun fail(message: String): Boolean {
        _ui.update { it.copy(busy = false, message = message) }
        return false
    }
}
