package dev.openwrtmanager.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import dev.openwrtmanager.app.ui.kit.BannerCard
import dev.openwrtmanager.app.ui.kit.BannerTone
import dev.openwrtmanager.app.ui.kit.ChipTone
import dev.openwrtmanager.app.ui.kit.Label
import dev.openwrtmanager.app.ui.kit.ListCard
import dev.openwrtmanager.app.ui.kit.ListRow
import dev.openwrtmanager.app.ui.kit.LiveMarker
import dev.openwrtmanager.app.ui.kit.MonoText
import dev.openwrtmanager.app.ui.kit.RowDivider
import dev.openwrtmanager.app.ui.kit.SectionCard
import dev.openwrtmanager.app.ui.kit.StateNotice
import dev.openwrtmanager.app.ui.kit.TagChip
import dev.openwrtmanager.app.ui.theme.MonoFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminScaffold(title: String, onBack: () -> Unit, content: @Composable (Modifier) -> Unit) {
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

@Composable
private fun LoadingBar(modifier: Modifier) {
    Box(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
    }
}

@Composable
private fun MessageDialog(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }, text = { Text(message) })
}

private enum class ServiceFilter(val label: String) {
    ALL("All"),
    RUNNING("Running"),
    STOPPED("Stopped"),
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
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ServiceFilter.ALL) }

    AdminScaffold("Services", onBack) { modifier ->
        when (val state = ui) {
            ServicesUi.Loading -> LoadingBar(modifier)
            ServicesUi.Offline -> StateNotice(
                icon = Icons.Default.LinkOff,
                title = "Not connected",
                detail = "Connect to a device on the Home tab first.",
                modifier = modifier,
                fillScreen = true,
            )
            ServicesUi.Failed -> StateNotice(
                icon = Icons.Default.LinkOff,
                title = "Could not read services",
                detail = "The device did not answer as expected.",
                actionLabel = "Try again",
                onAction = viewModel::load,
                modifier = modifier,
                fillScreen = true,
            )
            is ServicesUi.Ready -> {
                val trimmed = query.trim()
                val shown = state.services.filter { svc ->
                    (trimmed.isEmpty() || svc.name.contains(trimmed, ignoreCase = true)) && when (filter) {
                        ServiceFilter.ALL -> true
                        ServiceFilter.RUNNING -> svc.running == true
                        ServiceFilter.STOPPED -> svc.running == false
                    }
                }
                LazyColumn(
                    modifier,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Search services") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { query = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
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
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ServiceFilter.entries.forEach { option ->
                                    FilterChip(
                                        selected = filter == option,
                                        onClick = { filter = option },
                                        label = { Text(option.label) },
                                    )
                                }
                            }
                            Label("Services · ${shown.size}", Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                    items(shown, key = { it.name }) { service ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ListRow(
                                service.name,
                                detail = if (service.enabled) "Starts at boot" else "Disabled at boot",
                                onClick = { open = service },
                                trailing = {
                                    when (service.running) {
                                        true -> TagChip("Running", tone = ChipTone.OK)
                                        false -> TagChip("Stopped")
                                        null -> TagChip("No process")
                                    }
                                },
                            )
                        }
                    }
                }
                MessageDialog(state.message, viewModel::clearMessage)
            }
        }
    }
    open?.let { service ->
        ModalBottomSheet(onDismissRequest = { open = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(service.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 4.dp))
                SectionCard(contentPadding = false) {
                    ServiceAction.entries.forEachIndexed { index, action ->
                        if (index > 0) RowDivider()
                        val allowed = actionAllowed(service.name, action)
                        val icon = when (action) {
                            ServiceAction.START -> Icons.Default.PlayArrow
                            ServiceAction.RESTART -> Icons.Default.Refresh
                            ServiceAction.STOP -> Icons.Default.Stop
                            ServiceAction.ENABLE -> Icons.Default.ToggleOn
                            ServiceAction.DISABLE -> Icons.Default.ToggleOff
                        }
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = if (allowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                )
                            },
                            headlineContent = {
                                Text(
                                    action.verb.replaceFirstChar { it.uppercase() },
                                    color = if (allowed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                )
                            },
                            modifier = Modifier.clickable(enabled = allowed) {
                                confirm = service to action
                                open = null
                            },
                        )
                    }
                }
                if (!actionAllowed(service.name, ServiceAction.STOP)) {
                    BannerCard(
                        text = "Stop and disable are blocked because they would cut off SSH access to this device.",
                        tone = BannerTone.INFO,
                    )
                }
            }
        }
    }
    confirm?.let { (service, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${action.verb.replaceFirstChar { it.uppercase() }} ${service.name}?") },
            text = { actionWarning(service.name, action)?.let { Text(it) } },
            confirmButton = {
                TextButton(onClick = { confirm = null; viewModel.run(service, action) }) {
                    Text(action.verb.replaceFirstChar { it.uppercase() })
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (ui.following) {
                        Box(Modifier.padding(end = 16.dp)) { LiveMarker() }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!ui.following && visible.isNotEmpty()) {
                FloatingActionButton(onClick = { viewModel.setFollowing(true) }) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Jump to latest logs")
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    val kinds = listOf(LogKind.SYSTEM to "System", LogKind.KERNEL to "Kernel")
                    kinds.forEachIndexed { index, (kind, label) ->
                        SegmentedButton(
                            selected = ui.kind == kind,
                            onClick = { viewModel.setKind(kind) },
                            shape = SegmentedButtonDefaults.itemShape(index, kinds.size),
                        ) { Text(label) }
                    }
                }
                FilterChip(
                    selected = ui.following,
                    onClick = { viewModel.setFollowing(!ui.following) },
                    label = { Text("Follow") },
                )
            }
            OutlinedTextField(
                value = ui.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("Filter log lines") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (ui.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
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
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                ui.offline -> BannerCard(text = "Connect to a device on the Home tab first.", tone = BannerTone.INFO)
                ui.failed && ui.lines.isEmpty() -> BannerCard(text = "Could not read the log.", tone = BannerTone.ERROR)
            }
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 12.dp),
            ) {
                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
                ) {
                    items(visible) { line ->
                        Text(line, fontFamily = MonoFamily, fontSize = 11.sp, lineHeight = 16.sp, softWrap = false)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
            SystemUi.Loading -> LoadingBar(modifier)
            SystemUi.Offline -> StateNotice(
                icon = Icons.Default.LinkOff,
                title = "Not connected",
                detail = "Connect to a device on the Home tab first.",
                modifier = modifier,
                fillScreen = true,
            )
            SystemUi.Failed -> StateNotice(
                icon = Icons.Default.LinkOff,
                title = "Could not read settings",
                detail = "The device did not answer as expected.",
                actionLabel = "Try again",
                onAction = viewModel::load,
                modifier = modifier,
                fillScreen = true,
            )
            is SystemUi.Ready -> {
                Column(
                    modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    ListCard(label = "Device") {
                        ListRow(
                            "Device name",
                            icon = Icons.Default.Dns,
                            detail = state.config.hostname,
                            monoDetail = true,
                            onClick = { dialog = "hostname" },
                        )
                        RowDivider()
                        ListRow(
                            "Time zone",
                            icon = Icons.Default.Schedule,
                            detail = state.config.zonename ?: state.config.timezone ?: "Not set",
                            monoDetail = true,
                            onClick = { dialog = "zone" },
                        )
                    }
                    SectionCard(label = "Access and power") {
                        FilledTonalButton(onClick = { dialog = "password" }, modifier = Modifier.fillMaxWidth()) {
                            Text("Change root password")
                        }
                        FilledTonalButton(
                            onClick = { dialog = "reboot" },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text("Reboot device")
                        }
                    }
                }
                MessageDialog(state.message, viewModel::clearMessage)
                when (dialog) {
                    "hostname" -> TextDialog("Device name", state.config.hostname, onDismiss = { dialog = null }) { name ->
                        scope.launch { viewModel.planHostname(name)?.let { dialog = null; review(it) } }
                    }
                    "zone" -> ZoneSheet(state.zones, onDismiss = { dialog = null }) { zone ->
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
                        confirmButton = {
                            TextButton(onClick = { dialog = null; scope.launch { viewModel.reboot() } }) {
                                Text("Reboot", color = MaterialTheme.colorScheme.error)
                            }
                        },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZoneSheet(zones: List<Pair<String, String>>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = zones.map { it.first }.filter { it.contains(query.trim(), ignoreCase = true) }.take(200)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Time zone", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search, e.g. Kolkata") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedBorderColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(shown) { zone ->
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { MonoText(zone, size = 14.sp) },
                        modifier = Modifier.clickable { onPick(zone) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change root password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This takes effect at once and the undo timer cannot bring the old password back.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    password, { password = it }, label = { Text("New password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    repeat, { repeat = it }, label = { Text("Repeat password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(password, repeat) }) { Text("Change") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
