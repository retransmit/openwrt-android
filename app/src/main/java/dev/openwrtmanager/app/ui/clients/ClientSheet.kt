package dev.openwrtmanager.app.ui.clients

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.clients.Client
import dev.openwrtmanager.app.clients.ClientActions
import dev.openwrtmanager.app.clients.ClientLink
import dev.openwrtmanager.app.clients.Reservations
import dev.openwrtmanager.app.ui.kit.BannerCard
import dev.openwrtmanager.app.ui.kit.BannerTone
import dev.openwrtmanager.app.ui.kit.ChipTone
import dev.openwrtmanager.app.ui.kit.IconBadge
import dev.openwrtmanager.app.ui.kit.Label
import dev.openwrtmanager.app.ui.kit.MonoText
import dev.openwrtmanager.app.ui.kit.RowDivider
import dev.openwrtmanager.app.ui.kit.SectionCard
import dev.openwrtmanager.app.ui.kit.TagChip
import dev.openwrtmanager.app.ui.theme.LocalExtraColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Confirm { RECONNECT, BLOCK, UNBLOCK, RESERVE }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ClientSheet(
    client: Client,
    actions: ClientActions,
    offloading: Boolean,
    thisPhone: Boolean,
    viewModel: ClientsViewModel,
    onDismiss: () -> Unit,
    onReview: (ChangeSet) -> Unit,
) {
    val name = ClientsViewModel.label(client)
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var copiedKey by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    LaunchedEffect(copiedKey) {
        if (copiedKey != null) {
            delay(1_500)
            copiedKey = null
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(
                    when {
                        client.blocked -> Icons.Default.Block
                        client.link == ClientLink.WIFI -> Icons.Default.Wifi
                        else -> Icons.Outlined.Lan
                    },
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(client.name ?: "Unnamed device", style = MaterialTheme.typography.titleLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (client.blocked) TagChip("Blocked", tone = ChipTone.ERROR)
                        if (client.reserved) TagChip("Reserved", tone = ChipTone.ACCENT)
                        when (client.link) {
                            ClientLink.WIFI -> {
                                val tone = if ((client.signal ?: -60) >= -67) ChipTone.OK else ChipTone.WARNING
                                TagChip("Wi-Fi", tone = tone)
                            }
                            ClientLink.WIRED -> TagChip("Wired")
                            ClientLink.OFFLINE -> TagChip("Offline")
                        }
                    }
                }
            }

            SectionCard(contentPadding = false) {
                DetailRow("Connection", linkText(client))
                client.ip?.let { ip ->
                    RowDivider()
                    CopyableDetailRow(
                        label = "IP address",
                        value = ip,
                        copied = copiedKey == "ip",
                        onCopy = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("IP address", ip)))
                                copiedKey = "ip"
                            }
                        },
                    )
                }
                RowDivider()
                CopyableDetailRow(
                    label = "MAC address",
                    value = client.mac,
                    copied = copiedKey == "mac",
                    onCopy = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MAC address", client.mac)))
                            copiedKey = "mac"
                        }
                    },
                )
                client.connectedSeconds?.let {
                    RowDivider()
                    DetailRow("Connected for", duration(it), mono = true)
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (actions.reconnect) {
                    OutlinedButton(onClick = { confirm = Confirm.RECONNECT }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Reconnect")
                    }
                }
                if (actions.reserve && !client.reserved) {
                    FilledTonalButton(onClick = { confirm = Confirm.RESERVE }) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Reserve IP")
                    }
                }
                if (actions.block && !client.blocked) {
                    FilledTonalButton(
                        onClick = { confirm = Confirm.BLOCK },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(Icons.Default.Block, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Block")
                    }
                }
                if (actions.block && client.blocked) {
                    FilledTonalButton(onClick = { confirm = Confirm.UNBLOCK }) { Text("Unblock") }
                }
            }

            val elsewhere = listOfNotNull(
                "reserving an address".takeIf { !actions.reserve && !client.reserved },
                "blocking".takeIf { !actions.block },
            )
            if (elsewhere.isNotEmpty()) {
                BannerCard(
                    text = "Do ${elsewhere.joinToString(" and ")} on your main router.",
                    tone = BannerTone.INFO,
                )
            }
        }
    }

    when (confirm) {
        Confirm.RECONNECT -> Ask(
            title = "Make $name reconnect?",
            text = "The router drops it from Wi-Fi once. It usually rejoins within a few seconds.",
            action = "Reconnect",
            onDismiss = { confirm = null },
        ) {
            confirm = null
            viewModel.reconnect(client)
        }
        Confirm.BLOCK -> Ask(
            title = "Block $name?",
            text = buildString {
                if (thisPhone) append("This is the phone you are using. The app keeps working, but its internet stops.\n\n")
                append("It stops reaching the internet and your other networks straight away, until you unblock it.")
                if (offloading) append(" Connections already open may keep working for a few minutes.")
            },
            action = "Block",
            onDismiss = { confirm = null },
        ) {
            confirm = null
            scope.launch { viewModel.block(client)?.let(onReview) }
        }
        Confirm.UNBLOCK -> Ask(
            title = "Unblock $name?",
            text = "It can reach the internet and your other networks again.",
            action = "Unblock",
            onDismiss = { confirm = null },
        ) {
            confirm = null
            scope.launch { viewModel.unblock(client)?.let(onReview) }
        }
        Confirm.RESERVE -> ReserveDialog(client, onDismiss = { confirm = null }) { ip, hostName ->
            scope.launch {
                viewModel.reserve(client, ip, hostName)?.let {
                    confirm = null
                    onReview(it)
                }
            }
        }
        null -> Unit
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label(label)
        if (mono) {
            MonoText(value, maxLines = 2)
        } else {
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CopyableDetailRow(label: String, value: String, copied: Boolean, onCopy: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label(label)
            MonoText(value)
        }
        IconButton(onClick = onCopy) {
            Icon(
                if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = if (copied) "Copied $label" else "Copy $label",
                tint = if (copied) LocalExtraColors.current.online else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReserveDialog(client: Client, onDismiss: () -> Unit, onReserve: (String, String) -> Unit) {
    var ip by remember { mutableStateOf(client.ip.orEmpty()) }
    var name by remember { mutableStateOf(Reservations.proposeName(client.name, client.mac)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reserve an address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This device will always get the same address from the router.")
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.trim() },
                    label = { Text("Address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onReserve(ip, name) }) { Text("Review") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Ask(title: String, text: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun duration(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3_600 -> "${seconds / 60} min"
    seconds < 86_400 -> "${seconds / 3_600} h ${seconds % 3_600 / 60} min"
    else -> "${seconds / 86_400} d ${seconds % 86_400 / 3_600} h"
}
