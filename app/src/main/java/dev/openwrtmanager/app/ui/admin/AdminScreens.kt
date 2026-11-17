package dev.openwrtmanager.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.openwrtmanager.app.admin.LogKind
import dev.openwrtmanager.app.admin.Service
import dev.openwrtmanager.app.admin.ServiceAction
import dev.openwrtmanager.app.admin.actionAllowed
import dev.openwrtmanager.app.admin.actionWarning
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.ui.apply.ApplySheet
import dev.openwrtmanager.app.ui.apply.ApplyViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminScaffold(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding -> content(Modifier.fillMaxSize().padding(padding)) }
}

@Composable
private fun MessageDialog(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }, text = { Text(message) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(onBack: () -> Unit, viewModel: ServicesViewModel = hiltViewModel()) {
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { }
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Service?>(null) }
    var confirm by remember { mutableStateOf<Pair<Service, ServiceAction>?>(null) }

    AdminScaffold("Services", onBack) { modifier ->
        when (val state = ui) {
            ServicesUi.Loading -> LinearProgressIndicator(modifier.fillMaxWidth())
            ServicesUi.Offline -> Text("Connect to a device on the Home tab first.", modifier.padding(16.dp))
            ServicesUi.Failed -> Text("Could not read the services.", modifier.padding(16.dp))
            is ServicesUi.Ready -> {
                LazyColumn(modifier) {
                    items(state.services, key = { it.name }) { service ->
                        Column(Modifier.fillMaxWidth().clickable { open = service }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(service.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOf(
                                    when (service.running) { true -> "Running"; false -> "Stopped"; null -> "No background process" },
                                    if (service.enabled) "starts at boot" else "does not start at boot",
                                ).joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
                MessageDialog(state.message, viewModel::clearMessage)
            }
        }
    }
    open?.let { service ->
        ModalBottomSheet(onDismissRequest = { open = null }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(service.name, style = MaterialTheme.typography.titleLarge)
                ServiceAction.entries.forEach { action ->
                    OutlinedButton(
                        onClick = { confirm = service to action; open = null },
                        enabled = actionAllowed(service.name, action),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(action.verb.replaceFirstChar { it.uppercase() }) }
                }
                if (!actionAllowed(service.name, ServiceAction.STOP)) {
                    Text("Stop and disable are not offered: they would cut this app off from the device.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    confirm?.let { (service, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${action.verb.replaceFirstChar { it.uppercase() }} ${service.name}?") },
            text = { actionWarning(service.name, action)?.let { Text(it) } },
            confirmButton = { TextButton(onClick = { confirm = null; viewModel.run(service, action) }) { Text(action.verb.replaceFirstChar { it.uppercase() }) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun LogsScreen(onBack: () -> Unit, viewModel: LogsViewModel = hiltViewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.poll() }
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val visible = ui.visible
    LaunchedEffect(visible.size, ui.following) { if (ui.following && visible.isNotEmpty()) list.scrollToItem(visible.lastIndex) }

    AdminScaffold("Logs", onBack) { modifier ->
        Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = ui.kind == LogKind.SYSTEM, onClick = { viewModel.setKind(LogKind.SYSTEM) }, label = { Text("System") })
                FilterChip(selected = ui.kind == LogKind.KERNEL, onClick = { viewModel.setKind(LogKind.KERNEL) }, label = { Text("Kernel") })
                Text("Follow", Modifier.padding(start = 8.dp))
                Switch(checked = ui.following, onCheckedChange = viewModel::setFollowing)
            }
            OutlinedTextField(ui.query, viewModel::setQuery, label = { Text("Filter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            when {
                ui.offline -> Text("Connect to a device on the Home tab first.")
                ui.failed && ui.lines.isEmpty() -> Text("Could not read the log.")
            }
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f).horizontalScroll(rememberScrollState())) {
                items(visible) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, softWrap = false)
                }
            }
        }
    }
}

@Composable
fun SystemScreen(onBack: () -> Unit, viewModel: SystemViewModel = hiltViewModel(), applyViewModel: ApplyViewModel = hiltViewModel()) {
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { }
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<String?>(null) }
    val review: (ChangeSet?) -> Unit = { change -> if (change != null) applyViewModel.review(change) { viewModel.load() } }

    AdminScaffold("System", onBack) { modifier ->
        when (val state = ui) {
            SystemUi.Loading -> LinearProgressIndicator(modifier.fillMaxWidth())
            SystemUi.Offline -> Text("Connect to a device on the Home tab first.", modifier.padding(16.dp))
            SystemUi.Failed -> Text("Could not read the device's settings.", modifier.padding(16.dp))
            is SystemUi.Ready -> {
                Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().clickable { dialog = "hostname" }.padding(16.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Device name", style = MaterialTheme.typography.labelMedium)
                                Text(state.config.hostname, style = MaterialTheme.typography.bodyLarge)
                            }
                            Text("Change", color = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth().clickable { dialog = "zone" }.padding(16.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Time zone", style = MaterialTheme.typography.labelMedium)
                                Text(state.config.zonename ?: state.config.timezone ?: "Not set", style = MaterialTheme.typography.bodyLarge)
                            }
                            Text("Change", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    OutlinedButton(onClick = { dialog = "password" }, modifier = Modifier.fillMaxWidth()) { Text("Change root password") }
                    Button(onClick = { dialog = "reboot" }, modifier = Modifier.fillMaxWidth()) { Text("Reboot") }
                }
                MessageDialog(state.message, viewModel::clearMessage)
                when (dialog) {
                    "hostname" -> TextDialog("Device name", state.config.hostname, onDismiss = { dialog = null }) { name ->
                        scope.launch { viewModel.planHostname(name)?.let { dialog = null; review(it) } }
                    }
                    "zone" -> ZoneDialog(state.zones, onDismiss = { dialog = null }) { zone ->
                        dialog = null
                        scope.launch { review(viewModel.planTimezone(zone)) }
                    }
                    "password" -> PasswordDialog(onDismiss = { dialog = null }) { password, repeat ->
                        scope.launch { if (viewModel.changePassword(password, repeat)) dialog = null }
                    }
                    "reboot" -> AlertDialog(
                        onDismissRequest = { dialog = null },
                        title = { Text("Reboot ${state.config.hostname}?") },
                        text = { Text("Everything connected loses its connection for a minute or two.") },
                        confirmButton = { TextButton(onClick = { dialog = null; scope.launch { viewModel.reboot() } }) { Text("Reboot") } },
                        dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
                    )
                }
            }
        }
    }
    ApplySheet(applyViewModel)
}

@Composable
private fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Review") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ZoneDialog(zones: List<Pair<String, String>>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = zones.map { it.first }.filter { it.contains(query.trim(), ignoreCase = true) }.take(200)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Time zone") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("Search, for example Kolkata") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.padding(top = 8.dp).fillMaxWidth().weight(1f, fill = false)) {
                    items(shown) { zone ->
                        Text(zone, Modifier.fillMaxWidth().clickable { onPick(zone) }.padding(vertical = 10.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change root password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This takes effect at once and the undo timer cannot bring the old password back.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    password, { password = it }, label = { Text("New password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    repeat, { repeat = it }, label = { Text("Again") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(password, repeat) }) { Text("Change") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
