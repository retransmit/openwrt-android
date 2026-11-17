package dev.openwrtmanager.app.ui.admin

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openwrtmanager.app.admin.MAX_BACKUP_BYTES
import dev.openwrtmanager.app.admin.Package
import dev.openwrtmanager.app.admin.removalAllowed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding -> content(Modifier.fillMaxSize().padding(padding)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagesScreen(onBack: () -> Unit, viewModel: PackagesViewModel = hiltViewModel()) {
    LaunchedEffect(viewModel) { viewModel.load() }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<Package, Boolean>?>(null) }

    Screen("Packages", onBack) { modifier ->
        Column(modifier) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Installed (${ui.installed.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Find") })
            }
            if (ui.loading || ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (ui.offline) Text("Connect to a device on the Home tab first.", Modifier.padding(16.dp))
            ui.output?.let { output -> OutputCard(output, onClose = viewModel::clearOutput) }
            if (tab == 0) {
                OutlinedTextField(ui.filter, viewModel::setFilter, label = { Text("Filter") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp))
                PackageList(ui.visible, action = "Remove") { confirm = it to false }
            } else {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, label = { Text("Package name") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(enabled = !ui.busy, onClick = { viewModel.search(query) }) { Text("Search") }
                }
                Text(
                    "Searching downloads the package list, so the device needs internet.",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PackageList(ui.results, action = "Install") { confirm = it to true }
            }
        }
    }
    ui.message?.let { message ->
        AlertDialog(onDismissRequest = viewModel::clearMessage, confirmButton = { TextButton(onClick = viewModel::clearMessage) { Text("OK") } }, text = { Text(message) })
    }
    confirm?.let { (pkg, installing) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (installing) "Install ${pkg.name}?" else "Remove ${pkg.name}?") },
            text = {
                Text(
                    if (installing) "Downloads and installs it on the device. The undo timer does not cover packages."
                    else "Removes it from the device. Anything that depends on it may stop working, and the undo timer does not cover packages.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (installing) viewModel.install(pkg.name) else viewModel.remove(pkg.name)
                }) { Text(if (installing) "Install" else "Remove") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** Scrollable and capped, opening at the end, where package managers print their summary and errors. */
@Composable
private fun OutputCard(output: String, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(output) { scroll.scrollTo(scroll.maxValue) }
    ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                output.ifBlank { "(no output)" },
                Modifier.heightIn(max = 180.dp).verticalScroll(scroll),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}

@Composable
private fun PackageList(packages: List<Package>, action: String, onAction: (Package) -> Unit) {
    LazyColumn {
        items(packages, key = { it.name + " " + it.version }) { pkg ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(pkg.name, style = MaterialTheme.typography.bodyLarge)
                    Text(pkg.version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    action == "Install" && pkg.installed -> Text("Installed", style = MaterialTheme.typography.labelMedium)
                    action == "Remove" && !removalAllowed(pkg.name) -> Text("Needed", style = MaterialTheme.typography.labelMedium)
                    else -> TextButton(onClick = { onAction(pkg) }) { Text(action) }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun BackupScreen(onBack: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    LaunchedEffect(viewModel) { viewModel.load() }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = viewModel.takePendingBackup()
        scope.launch {
            note = withContext(Dispatchers.IO) {
                if (bytes == null) {
                    // The picker already created the file; never leave an empty "backup" behind.
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    "The backup was lost before it could be saved. Download it again."
                } else {
                    val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null }.getOrDefault(false)
                    if (ok) "Backup saved (${bytes.size / 1024} KB)." else "Could not save the file."
                }
            }
        }
    }
    LaunchedEffect(ui.saveRequested) {
        if (ui.saveRequested) {
            viewModel.saveRequestHandled()
            saver.launch(viewModel.fileName())
        }
    }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_BACKUP_BYTES) } }.getOrNull()
                }
                if (bytes == null) note = "That file could not be read, or it is too big to be a backup." else {
                    typed = ""
                    viewModel.prepareRestore(bytes)
                }
            }
        }
    }

    Screen("Backup", onBack) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Save the device's settings to this phone, or put back settings saved earlier.", style = MaterialTheme.typography.bodyMedium)
            Button(enabled = !ui.busy && ui.hostname != null, onClick = viewModel::download, modifier = Modifier.fillMaxWidth()) { Text("Download backup") }
            OutlinedButton(enabled = !ui.busy && ui.hostname != null, onClick = { opener.launch(arrayOf("application/gzip", "application/x-gzip", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Restore from a backup")
            }
            Text(
                "Restoring replaces every setting on the device with the ones in the file, including its root password and SSH keys, and reboots it. It cannot be undone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    ui.pendingRestore?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text("Restore ${ui.hostname}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This backup was taken from: ${pending.backupHostname ?: "an unknown device"}.")
                    if (pending.otherDevice) {
                        Text(
                            "That is not this device. Its network settings, addresses, root password and SSH keys would replace this device's, and it may not be reachable after the reboot.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text("Every setting is replaced, including the root password and SSH keys, then the device reboots. This cannot be undone. Type ${ui.hostname} to confirm.")
                    OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(enabled = typed == ui.hostname && !ui.busy, onClick = { viewModel.confirmRestore(typed) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = viewModel::cancelRestore) { Text("Cancel") } },
        )
    }
    (note ?: ui.message)?.let { message ->
        AlertDialog(
            onDismissRequest = { note = null; viewModel.clearMessage() },
            confirmButton = { TextButton(onClick = { note = null; viewModel.clearMessage() }) { Text("OK") } },
            text = { Text(message) },
        )
    }
}
