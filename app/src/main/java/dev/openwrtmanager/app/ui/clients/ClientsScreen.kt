package dev.openwrtmanager.app.ui.clients

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
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
import dev.openwrtmanager.app.ui.kit.IconBadge
import dev.openwrtmanager.app.ui.kit.ListCard
import dev.openwrtmanager.app.ui.kit.LiveMarker
import dev.openwrtmanager.app.ui.kit.MonoText
import dev.openwrtmanager.app.ui.kit.RowDivider
import dev.openwrtmanager.app.ui.kit.StateNotice
import dev.openwrtmanager.app.ui.kit.TagChip
import dev.openwrtmanager.app.ui.theme.LocalExtraColors
import dev.openwrtmanager.app.wifi.label
import androidx.compose.foundation.clickable

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
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Clients") },
                actions = {
                    if (ui is ClientsUi.Ready) {
                        Box(Modifier.padding(end = 16.dp)) { LiveMarker() }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
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
                    .padding(horizontal = 16.dp, vertical = 10.dp),
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

                        val activeFilter = if (filter == ClientFilter.BLOCKED && state.blocked.isEmpty()) {
                            ClientFilter.ALL
                        } else {
                            filter
                        }
                        val wifiClients = state.online.filter { it.link == ClientLink.WIFI && !it.blocked && matches(it) }
                        val wiredClients = state.online.filter { it.link == ClientLink.WIRED && !it.blocked && matches(it) }
                        val blockedClients = state.blocked.filter(matches)

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

                        val options = buildList {
                            add(ClientFilter.ALL)
                            add(ClientFilter.WIFI)
                            add(ClientFilter.WIRED)
                            if (state.blocked.isNotEmpty()) add(ClientFilter.BLOCKED)
                        }
                        val counts = mapOf(
                            ClientFilter.ALL to (state.online.size + state.blocked.count { it.link == ClientLink.OFFLINE }),
                            ClientFilter.WIFI to state.online.count { it.link == ClientLink.WIFI && !it.blocked },
                            ClientFilter.WIRED to state.online.count { it.link == ClientLink.WIRED && !it.blocked },
                            ClientFilter.BLOCKED to state.blocked.size,
                        )
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            options.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = activeFilter == option,
                                    onClick = { filter = option },
                                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                                ) {
                                    Text("${option.label} (${counts[option] ?: 0})", maxLines = 1)
                                }
                            }
                        }

                        if (state.missing.isNotEmpty()) {
                            BannerCard(
                                text = "Partial results: ${state.missing.joinToString(", ")} did not respond.",
                                icon = Icons.Default.Info,
                                tone = BannerTone.INFO,
                            )
                        }

                        val shownWifi = if (activeFilter == ClientFilter.ALL || activeFilter == ClientFilter.WIFI) wifiClients else emptyList()
                        val shownWired = if (activeFilter == ClientFilter.ALL || activeFilter == ClientFilter.WIRED) wiredClients else emptyList()
                        val shownBlocked = if (activeFilter == ClientFilter.ALL || activeFilter == ClientFilter.BLOCKED) blockedClients else emptyList()

                        if (shownWifi.isEmpty() && shownWired.isEmpty() && shownBlocked.isEmpty()) {
                            StateNotice(
                                icon = Icons.Default.Devices,
                                title = if (trimmed.isNotEmpty() || activeFilter != ClientFilter.ALL) "No matching clients" else "No connected devices",
                                detail = if (trimmed.isNotEmpty() || activeFilter != ClientFilter.ALL) {
                                    "Try clearing the filter or search query."
                                } else {
                                    "Devices connected via Wi-Fi or Ethernet will show up here."
                                },
                            )
                        } else {
                            ClientList("Wi-Fi · ${shownWifi.size}", shownWifi) { open = it }
                            ClientList("Wired · ${shownWired.size}", shownWired) { open = it }
                            ClientList("Blocked · ${shownBlocked.size}", shownBlocked) { open = it }
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
            ClientRow(client, onClick = { onOpen(client) })
        }
    }
}

@Composable
private fun ClientRow(client: Client, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    val (icon, tint, container) = when {
        client.blocked -> Triple(Icons.Default.Block, scheme.onErrorContainer, scheme.errorContainer.copy(alpha = 0.7f))
        client.link == ClientLink.WIFI -> {
            val sigTint = when {
                client.signal == null -> scheme.primary
                client.signal >= -67 -> extra.online
                else -> extra.warning
            }
            Triple(Icons.Default.Wifi, sigTint, extra.accentTint)
        }
        else -> Triple(Icons.Outlined.Lan, scheme.primary, extra.accentTint)
    }
    val subline = when (client.link) {
        ClientLink.WIFI -> listOfNotNull(
            client.ip ?: client.mac,
            client.ssid,
            client.signal?.let { "$it dBm" },
        ).joinToString(" · ")
        ClientLink.WIRED, ClientLink.OFFLINE -> listOfNotNull(
            client.ip,
            client.mac,
        ).joinToString(" · ")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(icon, tint = tint, containerColor = container)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                client.name ?: "Unnamed device",
                style = MaterialTheme.typography.titleSmall,
                color = if (client.name != null) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MonoText(
                subline,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (client.reserved) TagChip("Reserved", tone = ChipTone.ACCENT)
            when {
                client.blocked -> TagChip("Blocked", tone = ChipTone.ERROR)
                client.link == ClientLink.WIRED -> TagChip("LAN")
                client.band != null -> TagChip(client.band.label())
            }
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
