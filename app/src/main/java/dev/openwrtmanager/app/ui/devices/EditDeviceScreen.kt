package dev.openwrtmanager.app.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openwrtmanager.app.data.AuthType
import dev.openwrtmanager.app.ui.kit.BannerCard
import dev.openwrtmanager.app.ui.kit.BannerTone
import dev.openwrtmanager.app.ui.kit.MonoText
import dev.openwrtmanager.app.ui.kit.SectionCard
import dev.openwrtmanager.app.ui.theme.MonoFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDeviceScreen(
    profileId: Long?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: EditDeviceViewModel = hiltViewModel(),
) {
    LaunchedEffect(profileId) { viewModel.load(profileId) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val step = ui.step
    val busy = step is Step.Testing || step is Step.SettingUpKey

    LaunchedEffect(step) {
        if (step is Step.Saved && !step.keySetupFailed) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ui.isNew) "Add device" else "Edit device") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
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
            SectionCard(label = "Connection") {
                OutlinedTextField(
                    value = ui.name,
                    onValueChange = viewModel::onName,
                    label = { Text("Name") },
                    placeholder = { Text("Home router") },
                    isError = ui.nameError,
                    supportingText = if (ui.nameError) ({ Text("Give this device a name") }) else null,
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = ui.host,
                        onValueChange = viewModel::onHost,
                        label = { Text("Address") },
                        placeholder = { Text("192.168.1.1") },
                        isError = ui.hostError,
                        supportingText = if (ui.hostError) ({ Text("Enter an IP address or host name") }) else null,
                        singleLine = true,
                        enabled = !busy,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = ui.port,
                        onValueChange = viewModel::onPort,
                        label = { Text("Port") },
                        isError = ui.portError,
                        supportingText = if (ui.portError) ({ Text("1 to 65535") }) else null,
                        singleLine = true,
                        enabled = !busy,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        modifier = Modifier.weight(0.45f),
                    )
                }
            }

            SectionCard(label = "Authentication") {
                OutlinedTextField(
                    value = ui.username,
                    onValueChange = viewModel::onUsername,
                    label = { Text("User") },
                    singleLine = true,
                    enabled = !busy,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = MonoFamily),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(
                    value = ui.secret,
                    onValueChange = viewModel::onSecret,
                    isError = ui.secretError,
                    enabled = !busy,
                    supporting = when {
                        ui.secretError -> "Enter the password"
                        ui.isNew -> null
                        ui.authType == AuthType.KEY -> "This device logs in with a key. Leave blank to keep it, or type a password to switch back."
                        else -> "Leave blank to keep the saved password"
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Credentials are encrypted in the Android Keystore on this phone only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (step is Step.Failed) FailureBanner(step.reason)

            Button(
                onClick = viewModel::testAndSave,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Connecting...")
                } else {
                    Text("Test and save")
                }
            }
        }
    }

    when (step) {
        is Step.ConfirmHostKey -> HostKeyDialog(step, viewModel::acceptHostKey, viewModel::rejectHostKey)
        Step.OfferKeySetup -> AlertDialog(
            onDismissRequest = viewModel::skipKeySetup,
            icon = { Icon(Icons.Default.Key, contentDescription = null) },
            title = { Text("Use a key instead of the password?") },
            text = {
                Text(
                    "The app can create an Ed25519 login key and add it to the router. " +
                        "After that it no longer needs to keep the root password. " +
                        "This adds one line to the router's authorized keys.",
                )
            },
            confirmButton = { TextButton(onClick = viewModel::setUpKey) { Text("Set up key") } },
            dismissButton = { TextButton(onClick = viewModel::skipKeySetup) { Text("Keep password") } },
        )
        is Step.Saved -> if (step.keySetupFailed) {
            AlertDialog(
                onDismissRequest = onDone,
                title = { Text("Key setup did not work") },
                text = { Text("The device is saved and still logs in with the password.") },
                confirmButton = { TextButton(onClick = onDone) { Text("OK") } },
            )
        }
        else -> Unit
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    enabled: Boolean,
    supporting: String?,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Password") },
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
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
}

@Composable
private fun FailureBanner(reason: FailReason) {
    val (title, detail) = when (reason) {
        FailReason.UNREACHABLE -> "Could not reach the device" to
            "Check the address and port, and that this phone is on the same network or VPN."
        FailReason.AUTH -> "Login was refused" to "Check the user name and password."
        FailReason.NOT_OPENWRT -> "This does not look like OpenWrt" to
            "The login worked, but the device has no ubus command."
        FailReason.TIMEOUT -> "The device took too long to answer" to "Try again in a moment."
        FailReason.HOST_KEY -> "The device identity did not match" to "Try again and review the fingerprint."
        FailReason.OTHER -> "Something went wrong" to "The device returned an unexpected answer."
    }
    BannerCard(text = detail, title = title, icon = Icons.Default.Warning, tone = BannerTone.ERROR)
}

@Composable
private fun HostKeyDialog(step: Step.ConfirmHostKey, onAccept: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        icon = if (step.changed) ({ Icon(Icons.Default.Warning, contentDescription = null) }) else null,
        iconContentColor = MaterialTheme.colorScheme.error,
        title = { Text(if (step.changed) "This device's identity has changed" else "Trust this device?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (step.changed) {
                        "The fingerprint is different from the one you trusted before. That is expected after " +
                            "reinstalling or resetting the router. If you did not do that, someone may be " +
                            "intercepting the connection, and you should cancel."
                    } else {
                        "First connection to this device. Its SSH host key fingerprint is:"
                    },
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MonoText(
                        step.fingerprint,
                        size = 12.sp,
                        maxLines = 4,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text(if (step.changed) "Trust new identity" else "Trust") }
        },
        dismissButton = { TextButton(onClick = onReject) { Text("Cancel") } },
    )
}
