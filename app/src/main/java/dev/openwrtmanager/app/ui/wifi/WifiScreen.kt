package dev.openwrtmanager.app.ui.wifi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.domain.Band
import dev.openwrtmanager.app.ui.apply.ApplySheet
import dev.openwrtmanager.app.ui.apply.ApplyViewModel
import dev.openwrtmanager.app.wifi.Radio
import dev.openwrtmanager.app.wifi.WifiConfig
import dev.openwrtmanager.app.wifi.WifiEdits
import dev.openwrtmanager.app.wifi.WifiGroup
import dev.openwrtmanager.app.wifi.WizardKind
import dev.openwrtmanager.app.wifi.label
import dev.openwrtmanager.app.wifi.wifiQrPayload

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiScreen(
    onEditNetwork: (String?) -> Unit,
    onWizard: (WizardKind) -> Unit,
    viewModel: WifiViewModel = hiltViewModel(),
    applyViewModel: ApplyViewModel = hiltViewModel(),
) {
    // Reload every time the screen comes back, so edits made on other screens show up.
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { }
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var qrFor by remember { mutableStateOf<WifiGroup?>(null) }
    var editingRadio by remember { mutableStateOf<Radio?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Wi-Fi") }) }) { padding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = viewModel::load,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val state = ui) {
                    WifiUi.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    WifiUi.Offline -> Notice(Icons.Default.WifiOff, "Not connected", "Connect to a device on the Home tab to manage its Wi-Fi.")
                    WifiUi.Failed -> {
                        Notice(Icons.Default.WifiOff, "Could not read the Wi-Fi settings", "The device did not answer as expected.")
                        Button(onClick = viewModel::load, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Try again") }
                    }
                    is WifiUi.Loaded -> {
                        NetworksCard(state.config, onOpen = { onEditNetwork(it.first.section) }, onQr = { qrFor = it })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onEditNetwork(null) }) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Text(" Add network")
                            }
                        }
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
                            ListItem(
                                colors = clear,
                                modifier = Modifier.clickable { onWizard(WizardKind.GUEST) },
                                leadingContent = { Icon(Icons.Default.Groups, contentDescription = null) },
                                headlineContent = { Text("Create guest network") },
                                supportingContent = { Text("Internet only, kept away from your own devices") },
                                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                            )
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            ListItem(
                                colors = clear,
                                modifier = Modifier.clickable { onWizard(WizardKind.IOT) },
                                leadingContent = { Icon(Icons.Default.Sensors, contentDescription = null) },
                                headlineContent = { Text("Create IoT network") },
                                supportingContent = { Text("A separate network for smart home devices") },
                                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                            )
                        }
                        RadiosCard(state.config, onOpen = { editingRadio = it })
                    }
                }
            }
        }
    }

    qrFor?.let { group ->
        val first = group.first
        val security = first.security
        if (security != null) {
            QrDialog(group.ssid, wifiQrPayload(group.ssid, security, first.key, first.hidden)) { qrFor = null }
        }
    }
    editingRadio?.let { radio ->
        RadioEditSheet(
            radio = radio,
            onDismiss = { editingRadio = null },
            onSave = { change ->
                editingRadio = null
                applyViewModel.review(change) { viewModel.load() }
            },
        )
    }
    ApplySheet(applyViewModel)
}

@Composable
private fun NetworksCard(config: WifiConfig, onOpen: (WifiGroup) -> Unit, onQr: (WifiGroup) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text("Networks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 8.dp))
            if (config.groups.isEmpty()) {
                Text(
                    "This device is not broadcasting any networks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp, 8.dp),
                )
            }
            config.groups.forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onOpen(group) },
                    leadingContent = {
                        Icon(if (group.enabled) Icons.Default.Wifi else Icons.Default.WifiOff, contentDescription = null)
                    },
                    headlineContent = { Text(group.ssid.ifEmpty { "(no name)" }) },
                    supportingContent = {
                        val bands = group.bands.joinToString(" + ") { it.label() }
                        val security = group.first.security?.label() ?: group.first.rawEncryption
                        val state = if (group.enabled) "" else " · off"
                        Text("$bands · $security$state")
                    },
                    trailingContent = {
                        if (group.first.security != null) {
                            IconButton(onClick = { onQr(group) }) {
                                Icon(Icons.Default.QrCode2, contentDescription = "Show join code for ${group.ssid}")
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun RadiosCard(config: WifiConfig, onOpen: (Radio) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text("Radios", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 8.dp))
            config.radios.forEachIndexed { index, radio ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onOpen(radio) },
                    leadingContent = { Icon(Icons.Default.SettingsInputAntenna, contentDescription = null) },
                    headlineContent = { Text(radio.band.label()) },
                    supportingContent = {
                        val channel = if (radio.channel == "auto") "automatic channel" else "channel ${radio.channel}"
                        val power = radio.txpower?.let { " · $it dBm" }.orEmpty()
                        Text(if (radio.disabled) "Turned off" else "$channel · ${radio.htmode}$power")
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
internal fun Notice(icon: ImageVector, title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun channelsFor(band: Band): List<String> = listOf("auto") + when (band) {
    Band.GHZ_2_4 -> (1..13).map { it.toString() }
    Band.GHZ_5 -> listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165)
        .map { it.toString() }
    Band.GHZ_6 -> (1..93 step 4).map { it.toString() }
    Band.UNKNOWN -> emptyList()
}

/** Width options in the same family as the current mode, so a Wi-Fi 6 radio is never offered a Wi-Fi 4 mode. */
private fun widthsFor(radio: Radio): List<String> {
    val family = radio.htmode.takeWhile { !it.isDigit() }
    if (family.isEmpty()) return emptyList()
    val widths = when {
        radio.band == Band.GHZ_2_4 -> listOf(20, 40)
        family == "HT" -> listOf(20, 40)
        else -> listOf(20, 40, 80, 160)
    }
    return widths.map { "$family$it" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadioEditSheet(radio: Radio, onDismiss: () -> Unit, onSave: (ChangeSet) -> Unit) {
    var channel by remember { mutableStateOf(radio.channel) }
    var htmode by remember { mutableStateOf(radio.htmode) }
    var power by remember { mutableStateOf(radio.txpower) }
    var enabled by remember { mutableStateOf(!radio.disabled) }
    val change = WifiEdits.editRadio(radio, channel, htmode, power, radio.country, enabled)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("${radio.band.label()} radio", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Turned on", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Picker("Channel", channel, (channelsFor(radio.band) + radio.channel).distinct(), { if (it == "auto") "Automatic" else it }) { channel = it }
            val widths = (widthsFor(radio) + radio.htmode).filter { it.isNotEmpty() }.distinct()
            if (widths.isNotEmpty()) Picker("Channel width", htmode, widths, { it }) { htmode = it }
            Picker(
                label = "Transmit power",
                value = power?.toString() ?: "",
                options = listOf("", "5", "10", "15", "20", "23"),
                display = { if (it.isEmpty()) "Default" else "$it dBm" },
            ) { power = it.toIntOrNull() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(change) }, enabled = !change.isEmpty()) { Text("Review") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(label: String, value: String, options: List<String>, display: (String) -> String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = display(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(display(option)) }, onClick = { onPick(option); open = false })
            }
        }
    }
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
