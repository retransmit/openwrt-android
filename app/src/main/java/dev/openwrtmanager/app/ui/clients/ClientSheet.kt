package dev.openwrtmanager.app.ui.clients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.clients.Client
import dev.openwrtmanager.app.clients.ClientActions
import dev.openwrtmanager.app.clients.Reservations
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
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(client.name ?: "Unknown device", style = MaterialTheme.typography.titleLarge)
            Detail("Connection", linkText(client))
            client.ip?.let { Detail("Address", it) }
            Detail("MAC", client.mac)
            client.connectedSeconds?.let { Detail("Connected for", duration(it)) }
            if (client.reserved) Detail("Reserved", "Always gets the same address")
            if (client.blocked) Detail("Blocked", "Cannot reach the internet or your other networks")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (actions.reconnect) OutlinedButton(onClick = { confirm = Confirm.RECONNECT }) { Text("Reconnect") }
                if (actions.reserve && !client.reserved) OutlinedButton(onClick = { confirm = Confirm.RESERVE }) { Text("Reserve address") }
                if (actions.block && !client.blocked) FilledTonalButton(onClick = { confirm = Confirm.BLOCK }) { Text("Block") }
                if (actions.block && client.blocked) FilledTonalButton(onClick = { confirm = Confirm.UNBLOCK }) { Text("Unblock") }
            }
            val elsewhere = listOfNotNull(
                "reserving an address".takeIf { !actions.reserve && !client.reserved },
                "blocking".takeIf { !actions.block },
            )
            if (elsewhere.isNotEmpty()) {
                Text(
                    "Do ${elsewhere.joinToString(" and ")} on your main router.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun ReserveDialog(client: Client, onDismiss: () -> Unit, onReserve: (String, String) -> Unit) {
    var ip by remember { mutableStateOf(client.ip.orEmpty()) }
    var name by remember { mutableStateOf(Reservations.proposeName(client.name, client.mac)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reserve an address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

@Composable
private fun Detail(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun duration(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds < 3_600 -> "${seconds / 60} min"
    seconds < 86_400 -> "${seconds / 3_600} h ${seconds % 3_600 / 60} min"
    else -> "${seconds / 86_400} d ${seconds % 86_400 / 3_600} h"
}
