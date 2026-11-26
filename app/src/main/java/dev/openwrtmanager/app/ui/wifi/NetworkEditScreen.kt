package dev.openwrtmanager.app.ui.wifi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.ui.apply.ApplySheet
import dev.openwrtmanager.app.ui.apply.ApplyViewModel
import dev.openwrtmanager.app.ui.kit.BannerCard
import dev.openwrtmanager.app.ui.kit.BannerTone
import dev.openwrtmanager.app.ui.kit.CheckboxListRow
import dev.openwrtmanager.app.ui.kit.Label
import dev.openwrtmanager.app.ui.kit.RowDivider
import dev.openwrtmanager.app.ui.kit.SectionCard
import dev.openwrtmanager.app.ui.kit.StateNotice
import dev.openwrtmanager.app.ui.kit.SwitchListRow
import dev.openwrtmanager.app.wifi.KeyError
import dev.openwrtmanager.app.wifi.Security
import dev.openwrtmanager.app.wifi.SsidError
import dev.openwrtmanager.app.wifi.label

internal fun SsidError.text(): String = when (this) {
    SsidError.EMPTY -> "Enter a network name"
    SsidError.TOO_LONG -> "Too long: a name can use at most 32 bytes"
    SsidError.INVALID_CHARACTER -> "Remove line breaks and other invisible characters"
}

internal fun KeyError.text(): String = when (this) {
    KeyError.TOO_SHORT -> "Use at least 8 characters"
    KeyError.TOO_LONG -> "Use at most 63 characters"
    KeyError.NOT_ASCII -> "Use only plain letters, digits and symbols"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkEditScreen(
    section: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: NetworkEditViewModel = hiltViewModel(),
    applyViewModel: ApplyViewModel = hiltViewModel(),
) {
    LaunchedEffect(section) { viewModel.start(section) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmInstall by remember { mutableStateOf(false) }
    val review: (ChangeSet?) -> Unit = { change -> if (change != null && !change.isEmpty()) applyViewModel.review(change, onDone) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (form.isNew) "Add network" else form.ssid.ifEmpty { "Network" }) },
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
            when {
                form.failed -> StateNotice(
                    icon = Icons.Default.WifiOff,
                    title = "Could not load this network",
                    detail = "Go back and try again.",
                    fillScreen = true,
                )
                !form.loaded -> LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                else -> {
                    if (form.readOnly) {
                        BannerCard(
                            text = "This network uses a security mode the app cannot edit. You can inspect it and remove it, but not change it here.",
                            icon = Icons.Default.Warning,
                            tone = BannerTone.ERROR,
                        )
                    }
                    SectionCard(label = "Network & Security") {
                        OutlinedTextField(
                            value = form.ssid,
                            onValueChange = viewModel::onSsid,
                            label = { Text("Network name") },
                            isError = form.ssidError != null,
                            supportingText = form.ssidError?.let { { Text(it.text()) } },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Label("Security")
                        val options = listOf(Security.WPA2_WPA3 to "WPA2/3", Security.WPA2 to "WPA2", Security.WPA3 to "WPA3", Security.OPEN to "Open")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            options.forEachIndexed { index, (security, short) ->
                                SegmentedButton(
                                    selected = form.security == security,
                                    onClick = { viewModel.onSecurity(security) },
                                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                ) { Text(short, maxLines = 1) }
                            }
                        }
                        if (form.security != Security.OPEN) {
                            var visible by rememberSaveable { mutableStateOf(false) }
                            OutlinedTextField(
                                value = form.key,
                                onValueChange = viewModel::onKey,
                                label = { Text("Password") },
                                isError = form.keyError != null,
                                supportingText = { Text(form.keyError?.text() ?: "8 to 63 characters") },
                                singleLine = true,
                                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                                trailingIcon = {
                                    IconButton(onClick = { visible = !visible }) {
                                        Icon(
                                            if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (visible) "Hide password" else "Show password",
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            BannerCard(
                                text = "Anyone nearby can join an open network and read unencrypted traffic.",
                                icon = Icons.Default.Warning,
                                tone = BannerTone.ERROR,
                            )
                        }
                    }

                    SectionCard(label = "Bands", contentPadding = false) {
                        form.availableRadios.forEachIndexed { index, radio ->
                            if (index > 0) RowDivider()
                            CheckboxListRow(
                                title = radio.band.label(),
                                checked = radio.name in form.radios,
                                onCheckedChange = { viewModel.onRadio(radio.name, it) },
                            )
                        }
                        if (form.radioError) {
                            Text(
                                "Pick at least one band",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            )
                        }
                    }

                    SectionCard(label = "Options", contentPadding = false) {
                        SwitchListRow(title = "Turned on", checked = form.enabled, onCheckedChange = viewModel::onEnabled)
                        RowDivider()
                        SwitchListRow(
                            title = "Hidden",
                            detail = "Does not appear in network lists. Devices must type the name.",
                            checked = form.hidden,
                            onCheckedChange = viewModel::onHidden,
                        )
                        RowDivider()
                        SwitchListRow(
                            title = "Keep devices apart",
                            detail = "Devices on this network cannot see each other.",
                            checked = form.isolate,
                            onCheckedChange = viewModel::onIsolate,
                        )
                    }

                    Button(onClick = { review(viewModel.save()) }, enabled = form.canSave, modifier = Modifier.fillMaxWidth()) {
                        Text(if (form.isNew) "Review and add" else "Review changes")
                    }

                    if (!form.isNew) {
                        SteeringCard(
                            form = form,
                            onAssist = { on -> review(viewModel.steeringChange(enable = on, fastRoaming = false)) },
                            onFast = { review(viewModel.steeringChange(enable = true, fastRoaming = true)) },
                            onStart = viewModel::startUsteer,
                            onInstall = { confirmInstall = true },
                        )
                        FilledTonalButton(
                            onClick = { confirmRemove = true },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Remove network")
                        }
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${form.ssid}?") },
            text = { Text("Devices using this network will be disconnected. You will see exactly what is removed before anything changes.") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; review(viewModel.removeChange()) }) {
                    Text("Continue", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
    if (confirmInstall) {
        AlertDialog(
            onDismissRequest = { confirmInstall = false },
            title = { Text("Install usteer?") },
            text = { Text("This installs the package \"usteer\" on the router from its package feed. It needs internet access on the router.") },
            confirmButton = { TextButton(onClick = { confirmInstall = false; viewModel.installUsteer() }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { confirmInstall = false }) { Text("Cancel") } },
        )
    }
    ApplySheet(applyViewModel)
}

@Composable
private fun SteeringCard(
    form: NetworkForm,
    onAssist: (Boolean) -> Unit,
    onFast: () -> Unit,
    onStart: () -> Unit,
    onInstall: () -> Unit,
) {
    SectionCard(label = "Band steering", contentPadding = false) {
        SwitchListRow(
            title = "Help devices pick the better band",
            detail = "Uses one name on every band and tells devices about alternatives.",
            checked = form.steeringAssist,
            onCheckedChange = onAssist,
        )
        if (form.security != Security.OPEN) {
            RowDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Fast roaming (802.11r)", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (form.fastRoaming) "Enabled" else "Shortens switching between bands. A few old devices dislike it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!form.fastRoaming) OutlinedButton(onClick = onFast) { Text("Turn on") }
            }
        }
        form.usteer?.let { usteer ->
            RowDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Active steering (usteer)", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            usteer.running -> "Running: the router nudges devices to the better band"
                            usteer.installed -> "Installed but not running"
                            else -> "Not installed"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    usteer.running -> Unit
                    usteer.installed -> FilledTonalButton(onClick = onStart, enabled = !form.usteerBusy) { Text("Start") }
                    else -> FilledTonalButton(onClick = onInstall, enabled = !form.usteerBusy) { Text("Install") }
                }
            }
        }
    }
}
