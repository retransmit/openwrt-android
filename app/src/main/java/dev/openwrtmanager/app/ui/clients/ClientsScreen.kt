package dev.openwrtmanager.app.ui.clients

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.openwrtmanager.app.clients.Client
import dev.openwrtmanager.app.clients.ClientLink
import dev.openwrtmanager.app.ui.apply.ApplySheet
import dev.openwrtmanager.app.ui.apply.ApplyViewModel
import dev.openwrtmanager.app.wifi.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen(
    viewModel: ClientsViewModel = hiltViewModel(),
    applyViewModel: ApplyViewModel = hiltViewModel(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.poll() }
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Client?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Clients") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val state = ui) {
                ClientsUi.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                ClientsUi.Offline -> Notice("Not connected", "Connect to a device on the Home tab to see its clients.")
                ClientsUi.Failed -> {
                    Notice("Could not read the clients", "The device did not answer as expected.")
                    Button(onClick = viewModel::refresh, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Try again") }
                }
                is ClientsUi.Ready -> {
                    if (state.missing.isNotEmpty()) {
                        Text(
                            "Not shown: ${state.missing.joinToString(", ")}. The router did not answer for these.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("Connected now (${state.online.size})", style = MaterialTheme.typography.titleMedium)
                    if (state.online.isEmpty()) Text("No devices seen right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ClientList(state.online) { open = it }
                    if (state.blocked.isNotEmpty()) {
                        Text("Blocked (${state.blocked.size})", style = MaterialTheme.typography.titleMedium)
                        ClientList(state.blocked) { open = it }
                    }
                }
            }
        }
    }

    val ready = ui as? ClientsUi.Ready
    ready?.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearMessage,
            confirmButton = { TextButton(onClick = viewModel::clearMessage) { Text("OK") } },
            text = { Text(message) },
        )
    }
    open?.let { client ->
        // Follow the live list so the sheet shows fresh signal and flags.
        val current = ready?.let { s -> (s.online + s.blocked).firstOrNull { it.mac == client.mac } } ?: client
        ClientSheet(
            client = current,
            actions = viewModel.actions(current),
            offloading = ready?.offloading == true,
            thisPhone = viewModel.isThisPhone(current),
            viewModel = viewModel,
            onDismiss = { open = null },
            onReview = { change ->
                open = null
                applyViewModel.review(change) { viewModel.refresh() }
            },
        )
    }
    ApplySheet(applyViewModel)
}

@Composable
private fun ClientList(clients: List<Client>, onOpen: (Client) -> Unit) {
    if (clients.isEmpty()) return
    ElevatedCard(Modifier.fillMaxWidth()) {
        clients.forEachIndexed { index, client ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onOpen(client) },
                leadingContent = {
                    Icon(
                        when {
                            client.blocked -> Icons.Default.Block
                            client.link == ClientLink.WIFI -> Icons.Default.Wifi
                            else -> Icons.Default.Lan
                        },
                        contentDescription = null,
                    )
                },
                headlineContent = { Text(client.name ?: "Unknown device") },
                supportingContent = { Text(listOfNotNull(client.ip, linkText(client)).joinToString(", ")) },
                trailingContent = { if (client.reserved) SuggestionChip(onClick = { onOpen(client) }, label = { Text("Reserved") }) },
            )
        }
    }
}

internal fun linkText(client: Client): String = when (client.link) {
    ClientLink.WIFI -> listOfNotNull(client.ssid, client.band?.label(), client.signal?.let { "${signalWord(it)} ($it dBm)" }).joinToString(", ")
    ClientLink.WIRED -> "Wired or via another access point"
    ClientLink.OFFLINE -> "Not connected now"
}

internal fun signalWord(dbm: Int): String = when {
    dbm >= -55 -> "Excellent"
    dbm >= -67 -> "Good"
    dbm >= -75 -> "Fair"
    else -> "Weak"
}

@Composable
private fun Notice(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
