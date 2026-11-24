package dev.openwrtmanager.app.ui.clients

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.openwrtmanager.app.clients.Client
import dev.openwrtmanager.app.clients.ClientLink
import dev.openwrtmanager.app.ui.apply.ApplySheet
import dev.openwrtmanager.app.ui.apply.ApplyViewModel
import dev.openwrtmanager.app.ui.kit.BannerCard
import dev.openwrtmanager.app.ui.kit.BannerTone
import dev.openwrtmanager.app.ui.kit.ChipTone
import dev.openwrtmanager.app.ui.kit.ListCard
import dev.openwrtmanager.app.ui.kit.ListRow
import dev.openwrtmanager.app.ui.kit.RowDivider
import dev.openwrtmanager.app.ui.kit.StateNotice
import dev.openwrtmanager.app.ui.kit.TagChip
import dev.openwrtmanager.app.wifi.label

private enum class ClientFilter(val label: String) {
    ALL("All"),
    WIFI("Wi-Fi"),
    WIRED("Wired"),
    BLOCKED("Blocked"),
}

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
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ClientFilter.ALL) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { MediumTopAppBar(title = { Text("Clients") }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (val state = ui) {
                    ClientsUi.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                    ClientsUi.Offline -> StateNotice(
                        icon = Icons.Default.LinkOff,
                        title = "Not connected",
                        detail = "Connect to a device on the Home tab to see its clients.",
                    )
                    ClientsUi.Failed -> StateNotice(
                        icon = Icons.Default.LinkOff,
                        title = "Could not read clients",
                        detail = "The device did not answer as expected.",
                        actionLabel = "Try again",
                        onAction = viewModel::refresh,
                    )
                    is ClientsUi.Ready -> {
                        val trimmed = query.trim()
                        val matches: (Client) -> Boolean = { c ->
                            trimmed.isEmpty() ||
                                (c.name?.contains(trimmed, ignoreCase = true) == true) ||
                                (c.ip?.contains(trimmed, ignoreCase = true) == true) ||
                                c.mac.contains(trimmed, ignoreCase = true) ||
                                (c.ssid?.contains(trimmed, ignoreCase = true) == true)
                        }
                        val onlineFiltered = state.online.filter {
                            matches(it) && when (filter) {
                                ClientFilter.ALL -> true
                                ClientFilter.WIFI -> it.link == ClientLink.WIFI
                                ClientFilter.WIRED -> it.link == ClientLink.WIRED
                                ClientFilter.BLOCKED -> false
                            }
                        }
                        val blockedFiltered = if (filter == ClientFilter.ALL || filter == ClientFilter.BLOCKED) {
                            state.blocked.filter(matches)
                        } else {
                            emptyList()
                        }

                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search name, IP, or MAC") },
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

                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val wifiCount = state.online.count { it.link == ClientLink.WIFI }
                            val wiredCount = state.online.count { it.link == ClientLink.WIRED }
                            val counts = mapOf(
                                ClientFilter.ALL to (state.online.size + state.blocked.size),
                                ClientFilter.WIFI to wifiCount,
                                ClientFilter.WIRED to wiredCount,
                                ClientFilter.BLOCKED to state.blocked.size,
                            )
                            ClientFilter.entries.forEach { option ->
                                FilterChip(
                                    selected = filter == option,
                                    onClick = { filter = option },
                                    label = { Text("${option.label} · ${counts[option] ?: 0}") },
                                )
                            }
                        }

                        if (state.missing.isNotEmpty()) {
                            BannerCard(
                                text = "Partial results: ${state.missing.joinToString(", ")} did not respond.",
                                icon = Icons.Default.Info,
                                tone = BannerTone.INFO,
                            )
                        }

                        if (onlineFiltered.isEmpty() && blockedFiltered.isEmpty()) {
                            StateNotice(
                                icon = Icons.Default.Devices,
                                title = if (trimmed.isNotEmpty() || filter != ClientFilter.ALL) "No matching clients" else "No connected devices",
                                detail = if (trimmed.isNotEmpty() || filter != ClientFilter.ALL) {
                                    "Try clearing the filter or search query."
                                } else {
                                    "Devices connected via Wi-Fi or Ethernet will show up here."
                                },
                            )
                        } else {
                            ClientList("Connected · ${onlineFiltered.size}", onlineFiltered) { open = it }
                            ClientList("Blocked · ${blockedFiltered.size}", blockedFiltered) { open = it }
                        }
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
private fun ClientList(label: String, clients: List<Client>, onOpen: (Client) -> Unit) {
    if (clients.isEmpty()) return
    ListCard(label = label) {
        clients.forEachIndexed { index, client ->
            if (index > 0) RowDivider()
            ListRow(
                client.name ?: "Unknown device",
                icon = when {
                    client.blocked -> Icons.Default.Block
                    client.link == ClientLink.WIFI -> Icons.Default.Wifi
                    else -> Icons.Default.Lan
                },
                detail = listOfNotNull(client.ip, linkText(client)).joinToString("\n"),
                monoDetail = true,
                onClick = { onOpen(client) },
                trailing = {
                    when {
                        client.blocked -> TagChip("Blocked", tone = ChipTone.ERROR)
                        client.reserved -> TagChip("Reserved", tone = ChipTone.ACCENT)
                        client.link == ClientLink.WIRED -> TagChip("LAN")
                        client.band != null -> TagChip(client.band.label())
                    }
                },
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
