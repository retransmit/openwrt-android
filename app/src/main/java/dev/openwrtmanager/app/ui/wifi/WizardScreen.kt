package dev.openwrtmanager.app.ui.wifi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openwrtmanager.app.ui.apply.ApplySheet
import dev.openwrtmanager.app.ui.apply.ApplyViewModel
import dev.openwrtmanager.app.wifi.BlockReason
import dev.openwrtmanager.app.wifi.WizardKind
import dev.openwrtmanager.app.wifi.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WizardScreen(
    kind: WizardKind,
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: WizardViewModel = hiltViewModel(),
    applyViewModel: ApplyViewModel = hiltViewModel(),
) {
    LaunchedEffect(kind) { viewModel.start(kind) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val title = if (kind == WizardKind.GUEST) "Guest network" else "IoT network"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val blocked = ui.blocked
            when {
                ui.failed -> Notice(Icons.Default.WifiOff, "Could not read the device", "Go back and try again.")
                !ui.loaded -> LinearProgressIndicator(Modifier.fillMaxWidth())
                blocked != null -> {
                    val (headline, detail) = blocked.explain()
                    Notice(Icons.Default.Block, headline, detail)
                }
                else -> {
                    Text(
                        if (kind == WizardKind.GUEST) {
                            "Visitors get internet access on their own network. They cannot reach your devices or the router's settings."
                        } else {
                            "Smart home devices get their own network. They can reach the internet but cannot start connections to your devices."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = ui.ssid,
                        onValueChange = viewModel::onSsid,
                        label = { Text("Network name") },
                        isError = ui.ssidError != null,
                        supportingText = ui.ssidError?.let { { Text(it.text()) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = ui.key,
                        onValueChange = viewModel::onKey,
                        label = { Text("Password") },
                        isError = ui.keyError != null,
                        supportingText = { Text(ui.keyError?.text() ?: "Shown here so you can read it out. A join code follows.") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        trailingIcon = {
                            IconButton(onClick = viewModel::regeneratePassword) {
                                Icon(Icons.Default.Refresh, contentDescription = "Make a new password")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Bands", style = MaterialTheme.typography.labelLarge)
                    ui.availableRadios.forEach { radio ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = radio.name in ui.radios, onCheckedChange = { viewModel.onRadio(radio.name, it) })
                            Text(radio.band.label(), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (kind == WizardKind.IOT) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Let my devices reach IoT devices", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Needed for apps that control lights, speakers or cameras from your phone at home.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = ui.lanCanReachIot, onCheckedChange = viewModel::onLanCanReachIot)
                        }
                    }
                    if (ui.ports.isNotEmpty()) {
                        Text("Also on these ports", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Optional. A device port puts everything plugged into it on this network. An AP link carries it " +
                                "tagged to an access point that understands VLANs. Leave ports on None to keep them on your home network.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        dev.openwrtmanager.app.ui.network.PortRolesEditor(ui.ports, ui.portRoles, viewModel::onPortRole)
                    }
                    Button(
                        onClick = { viewModel.review()?.let { change -> applyViewModel.review(change) { viewModel.onApplied() } } },
                        enabled = ui.radios.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Review") }
                }
            }
        }
    }

    ApplySheet(applyViewModel)
    ui.qrPayload?.let { payload -> QrDialog(ui.ssid, payload, onDismiss = onDone) }
}

private fun BlockReason.explain(): Pair<String, String> = when (this) {
    BlockReason.NO_UPLINK -> "Create this on your main router" to
        "This device passes traffic along but does not connect to the internet itself, so it cannot run a separate network. " +
        "Switch to your main router and create the network there, then use \"Add a network from my router\" here to carry it to this device."
    BlockReason.NO_WAN_ZONE -> "No internet zone found" to
        "The firewall on this device has no zone for the internet connection, so the new network would have nowhere to go."
    BlockReason.NO_FREE_SUBNET -> "No free address range" to
        "Every range the app could use overlaps one that is already in use on this device."
    BlockReason.NO_RADIO -> "No Wi-Fi radio selected" to "Pick at least one band."
    BlockReason.BAD_NAME -> "Pick another name" to
        "Use up to 11 lowercase letters, digits or underscores, starting with a letter, and a name no other network uses."
    BlockReason.VLAN_PROBLEM -> "These port settings would not work" to
        "The VLAN ID is taken or out of range, or no port or Wi-Fi would be left on your home network. Change the ports and try again."
}
