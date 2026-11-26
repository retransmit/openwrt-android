package dev.openwrtmanager.app.ui.wifi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.domain.Band
import dev.openwrtmanager.app.ui.apply.ApplySheet
import dev.openwrtmanager.app.ui.apply.ApplyViewModel
import dev.openwrtmanager.app.ui.kit.ListCard
import dev.openwrtmanager.app.ui.kit.ListRow
import dev.openwrtmanager.app.ui.kit.RowDivider
import dev.openwrtmanager.app.ui.kit.SectionCard
import dev.openwrtmanager.app.ui.kit.StateNotice
import dev.openwrtmanager.app.ui.kit.SwitchListRow
import dev.openwrtmanager.app.ui.kit.TagChip
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
    onMirror: () -> Unit,
    viewModel: WifiViewModel = hiltViewModel(),
    applyViewModel: ApplyViewModel = hiltViewModel(),
) {
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { }
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var qrFor by remember { mutableStateOf<WifiGroup?>(null) }
    var editingRadio by remember { mutableStateOf<Radio?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text("Wi-Fi") }, scrollBehavior = scrollBehavior) },
        floatingActionButton = {
            if (ui is WifiUi.Loaded) {
                ExtendedFloatingActionButton(
                    onClick = { onEditNetwork(null) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add network") },
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = viewModel::load,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (val state = ui) {
                    WifiUi.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                    WifiUi.Offline -> StateNotice(
                        icon = Icons.Default.WifiOff,
                        title = "Not connected",
                        detail = "Connect to a device on the Home tab to manage its Wi-Fi.",
                        fillScreen = false,
                    )
                    WifiUi.Failed -> StateNotice(
                        icon = Icons.Default.WifiOff,
                        title = "Could not read Wi-Fi settings",
                        detail = "The device did not answer as expected.",
                        actionLabel = "Try again",
                        onAction = viewModel::load,
                    )
                    is WifiUi.Loaded -> {
                        NetworksCard(state.config, onOpen = { onEditNetwork(it.first.section) }, onQr = { qrFor = it })
                        if (!state.canCreateIsolatedNetworks) {
                            ListCard(label = "Access point") {
                                ListRow(
                                    "Add a network from my router",
                                    icon = Icons.Default.Groups,
                                    detail = "Mirror your router's guest or IoT network onto this access point",
                                    onClick = onMirror,
                                )
                            }
                        }
                        ListCard(label = "Guided setup") {
                            ListRow(
                                "Create guest network",
                                icon = Icons.Default.Groups,
                                detail = "Internet only, isolated from your home devices",
                                onClick = { onWizard(WizardKind.GUEST) },
                            )
                            RowDivider()
                            ListRow(
                                "Create IoT network",
                                icon = Icons.Default.Sensors,
                                detail = "Separate VLAN and firewall rules for smart devices",
                                onClick = { onWizard(WizardKind.IOT) },
                            )
                        }
                        RadiosCard(state.config, onOpen = { editingRadio = it })
                        Spacer(Modifier.height(76.dp))
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
    ListCard(label = "Networks · ${config.groups.size}") {
        if (config.groups.isEmpty()) {
            Text(
                "This device is not broadcasting any networks.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        config.groups.forEachIndexed { index, group ->
            if (index > 0) RowDivider()
            val bands = group.bands.joinToString(" + ") { it.label() }
            val security = group.first.security?.label() ?: group.first.rawEncryption
            ListRow(
                group.ssid.ifEmpty { "(no name)" },
                modifier = Modifier.alpha(if (group.enabled) 1f else 0.55f),
                icon = if (group.enabled) Icons.Default.Wifi else Icons.Default.WifiOff,
                detail = "$bands · $security" + if (group.enabled) "" else " · off",
                monoDetail = true,
                onClick = { onOpen(group) },
                trailing = if (group.first.security != null) {
                    {
                        FilledTonalIconButton(
                            onClick = { onQr(group) },
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(Icons.Default.QrCode2, contentDescription = "Show join code for ${group.ssid}", modifier = Modifier.size(20.dp))
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun RadiosCard(config: WifiConfig, onOpen: (Radio) -> Unit) {
    ListCard(label = "Radios · ${config.radios.size}") {
        config.radios.forEachIndexed { index, radio ->
            if (index > 0) RowDivider()
            val channel = if (radio.channel == "auto") "auto channel" else "ch ${radio.channel}"
            val power = radio.txpower?.let { " · $it dBm" }.orEmpty()
            ListRow(
                radio.band.label(),
                icon = Icons.Default.SettingsInputAntenna,
                detail = if (radio.disabled) "Turned off" else "$channel · ${radio.htmode}$power",
                monoDetail = !radio.disabled,
                onClick = { onOpen(radio) },
                trailing = if (radio.disabled) ({ TagChip("Off") }) else null,
            )
        }
    }
}

private fun channelsFor(band: Band): List<String> = listOf("auto") + when (band) {
    Band.GHZ_2_4 -> (1..13).map { it.toString() }
    Band.GHZ_5 -> listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165)
        .map { it.toString() }
    Band.GHZ_6 -> (1..93 step 4).map { it.toString() }
    Band.UNKNOWN -> emptyList()
}

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
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("${radio.band.label()} radio", style = MaterialTheme.typography.titleLarge)
            SectionCard(contentPadding = false) {
                SwitchListRow(title = "Turned on", checked = enabled, onCheckedChange = { enabled = it })
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
