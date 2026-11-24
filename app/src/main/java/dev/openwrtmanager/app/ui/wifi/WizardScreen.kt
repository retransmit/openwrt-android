package dev.openwrtmanager.app.ui.wifi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openwrtmanager.app.ui.apply.ApplySheet
import dev.openwrtmanager.app.ui.apply.ApplyViewModel
import dev.openwrtmanager.app.ui.kit.BannerCard
import dev.openwrtmanager.app.ui.kit.BannerTone
import dev.openwrtmanager.app.ui.kit.CheckboxListRow
import dev.openwrtmanager.app.ui.kit.RowDivider
import dev.openwrtmanager.app.ui.kit.SectionCard
import dev.openwrtmanager.app.ui.kit.StateNotice
import dev.openwrtmanager.app.ui.kit.SwitchListRow
import dev.openwrtmanager.app.ui.network.PortRolesEditor
import dev.openwrtmanager.app.ui.theme.MonoFamily
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val blocked = ui.blocked
            when {
                ui.failed -> StateNotice(
                    icon = Icons.Default.WifiOff,
                    title = "Could not read the device",
                    detail = "Go back and try again.",
                    fillScreen = true,
                )
                !ui.loaded -> LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                blocked != null -> {
                    val (headline, detail) = blocked.explain()
                    StateNotice(icon = Icons.Default.Block, title = headline, detail = detail, fillScreen = true)
                }
                else -> {
                    BannerCard(
                        text = if (kind == WizardKind.GUEST) {
                            "Visitors get internet access on their own isolated network. They cannot reach your devices or router settings."
                        } else {
                            "Smart home devices get their own isolated network with internet access, unable to initiate connections to your LAN."
                        },
                        tone = BannerTone.INFO,
                    )
                    SectionCard(label = "Wi-Fi credentials") {
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
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
                            trailingIcon = {
                                IconButton(onClick = viewModel::regeneratePassword) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Make a new password")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SectionCard(label = "Bands", contentPadding = false) {
                        ui.availableRadios.forEachIndexed { index, radio ->
                            if (index > 0) RowDivider()
                            CheckboxListRow(
                                title = radio.band.label(),
                                checked = radio.name in ui.radios,
                                onCheckedChange = { viewModel.onRadio(radio.name, it) },
                            )
                        }
                    }
                    if (kind == WizardKind.IOT) {
                        SectionCard(label = "Access", contentPadding = false) {
                            SwitchListRow(
                                title = "Let my devices reach IoT devices",
                                detail = "Needed for apps that control lights, speakers or cameras from your phone at home.",
                                checked = ui.lanCanReachIot,
                                onCheckedChange = viewModel::onLanCanReachIot,
                            )
                        }
                    }
                    if (ui.ports.isNotEmpty()) {
                        SectionCard(label = "Switch ports (optional)") {
                            Text(
                                "A device port puts everything plugged into it on this network. An AP link carries it tagged to an access point. Leave on None to keep ports on your home network.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PortRolesEditor(ui.ports, ui.portRoles, viewModel::onPortRole)
                        }
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
