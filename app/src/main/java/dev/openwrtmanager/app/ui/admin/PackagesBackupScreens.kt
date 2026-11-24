package dev.openwrtmanager.app.ui.admin

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openwrtmanager.app.admin.MAX_BACKUP_BYTES
import dev.openwrtmanager.app.admin.Package
import dev.openwrtmanager.app.admin.removalAllowed
import dev.openwrtmanager.app.ui.kit.BannerCard
import dev.openwrtmanager.app.ui.kit.BannerTone
import dev.openwrtmanager.app.ui.kit.ChipTone
import dev.openwrtmanager.app.ui.kit.MonoText
import dev.openwrtmanager.app.ui.kit.SectionCard
import dev.openwrtmanager.app.ui.kit.TagChip
import dev.openwrtmanager.app.ui.theme.MonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                scrollBehavior = scrollBehavior,
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
            if (ui.offline) {
                BannerCard(
                    text = "Connect to a device on the Home tab first.",
                    tone = BannerTone.INFO,
                    modifier = Modifier.padding(16.dp),
                )
            }
            ui.output?.let { output -> OutputCard(output, onClose = viewModel::clearOutput) }
            if (tab == 0) {
                OutlinedTextField(
                    value = ui.filter,
                    onValueChange = viewModel::setFilter,
                    placeholder = { Text("Filter installed packages") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (ui.filter.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setFilter("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear filter")
                            }
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                PackageList(ui.visible, action = "Remove") { confirm = it to false }
            } else {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Package name") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = CircleShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Button(enabled = !ui.busy, onClick = { viewModel.search(query) }) { Text("Search") }
                }
                Text(
                    "Searching refreshes the package index, so the device needs internet access.",
                    Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
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
                }) {
                    Text(
                        if (installing) "Install" else "Remove",
                        color = if (installing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OutputCard(output: String, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(output) { scroll.scrollTo(scroll.maxValue) }
    SectionCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), label = "Output") {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                output.ifBlank { "(no output)" },
                Modifier.heightIn(max = 180.dp).verticalScroll(scroll).padding(12.dp),
                fontFamily = MonoFamily,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Close") }
    }
}

@Composable
private fun PackageList(packages: List<Package>, action: String, onAction: (Package) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(packages, key = { it.name + " " + it.version }) { pkg ->
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    MonoText(pkg.name, size = 14.sp)
                    MonoText(pkg.version, color = MaterialTheme.colorScheme.onSurfaceVariant, size = 11.sp)
                }
                when {
                    action == "Install" && pkg.installed -> TagChip("Installed", tone = ChipTone.OK)
                    action == "Remove" && !removalAllowed(pkg.name) -> TagChip("Needed")
                    action == "Install" -> FilledTonalButton(onClick = { onAction(pkg) }) { Text(action) }
                    else -> TextButton(onClick = { onAction(pkg) }) { Text(action, color = MaterialTheme.colorScheme.error) }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
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
        Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            SectionCard(label = "Save", glow = true) {
                Text("Download a sysupgrade archive of this device's configuration to your phone.", style = MaterialTheme.typography.bodyMedium)
                Button(enabled = !ui.busy && ui.hostname != null, onClick = viewModel::download, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Download backup")
                }
            }
            SectionCard(label = "Restore") {
                BannerCard(
                    text = "Restoring replaces every setting on the device, including root password and SSH keys, then reboots. It cannot be undone.",
                    icon = Icons.Default.Warning,
                    tone = BannerTone.WARNING,
                )
                FilledTonalButton(
                    enabled = !ui.busy && ui.hostname != null,
                    onClick = { opener.launch(arrayOf("application/gzip", "application/x-gzip", "application/octet-stream")) },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Restore from backup")
                }
            }
        }
    }
    ui.pendingRestore?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text("Restore ${ui.hostname}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This backup was taken from: ${pending.backupHostname ?: "an unknown device"}.")
                    if (pending.otherDevice) {
                        BannerCard(
                            text = "That is not this device. Its network settings, root password and SSH keys would overwrite this device and may lock you out.",
                            icon = Icons.Default.Warning,
                            tone = BannerTone.ERROR,
                        )
                    }
                    Text("Type ${ui.hostname} to confirm.")
                    OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = typed == ui.hostname && !ui.busy, onClick = { viewModel.confirmRestore(typed) }) {
                    Text("Restore", color = MaterialTheme.colorScheme.error)
                }
            },
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
