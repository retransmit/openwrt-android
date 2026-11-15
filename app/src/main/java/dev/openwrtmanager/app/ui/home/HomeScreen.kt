package dev.openwrtmanager.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.openwrtmanager.app.domain.Band
import dev.openwrtmanager.app.domain.Rate
import dev.openwrtmanager.app.domain.SystemInfo
import dev.openwrtmanager.app.router.RouterError
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.ui.common.formatRate
import dev.openwrtmanager.app.ui.common.formatUptime
import dev.openwrtmanager.app.ui.common.memoryUsedPercent
import dev.openwrtmanager.app.ui.theme.OpenWrtTheme
import java.util.Locale

private const val GRAPH_SLOTS = 60

@Composable
fun HomeScreen(
    onSwitchDevice: () -> Unit,
    onAddDevice: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Poll only while this screen is actually on display.
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.poll() }
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    HomeContent(ui, onRefresh = viewModel::refresh, onSwitchDevice = onSwitchDevice, onAddDevice = onAddDevice)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(ui: HomeUi, onRefresh: () -> Unit, onSwitchDevice: () -> Unit, onAddDevice: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            ui.deviceName.ifEmpty { "OpenWrt Manager" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (ui.status != HomeStatus.NoDevice) StatusLine(ui.status)
                    }
                },
                actions = {
                    IconButton(onClick = onSwitchDevice) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "Switch device")
                    }
                },
            )
        },
    ) { padding ->
        if (ui.status == HomeStatus.NoDevice) {
            NoDevice(onAddDevice, Modifier.padding(padding))
            return@Scaffold
        }
        PullToRefreshBox(
            isRefreshing = ui.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val stale = ui.status !is HomeStatus.Online
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                (ui.status as? HomeStatus.Offline)?.let { OfflineCard(it.error, onRefresh) }
                if (!ui.loaded) {
                    if (ui.status !is HomeStatus.Offline) LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    // Last known values stay visible but dimmed while the device is unreachable.
                    Column(
                        modifier = Modifier.alpha(if (stale) 0.5f else 1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (ui.uplinkPresent) SpeedCard(ui.rate, ui.history) else NoUplinkCard()
                        WifiCard(ui.wifi)
                        StatsRow(ui)
                        ui.system?.let { SystemFooter(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(status: HomeStatus) {
    val (label, color) = when (status) {
        HomeStatus.Online -> "Online" to Color(0xFF2E9E5B)
        HomeStatus.Connecting -> "Connecting" to MaterialTheme.colorScheme.outline
        is HomeStatus.Offline -> "Offline" to MaterialTheme.colorScheme.error
        HomeStatus.NoDevice -> "" to Color.Transparent
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SpeedCard(rate: Rate?, history: List<Rate>) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Internet", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth()) {
                SpeedFigure(
                    icon = Icons.Default.ArrowDownward,
                    label = "Download",
                    value = rate?.let { formatRate(it.downBps) },
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                SpeedFigure(
                    icon = Icons.Default.ArrowUpward,
                    label = "Upload",
                    value = rate?.let { formatRate(it.upBps) },
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
            }
            RateGraph(history, slots = GRAPH_SLOTS, modifier = Modifier.fillMaxWidth().height(96.dp))
        }
    }
}

@Composable
private fun SpeedFigure(icon: ImageVector, label: String, value: String?, tint: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = tint)
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value ?: "measuring", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        }
    }
}

@Composable
private fun NoUplinkCard() {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.CloudOff, contentDescription = null)
            Column {
                Text("No internet connection detected", style = MaterialTheme.typography.titleSmall)
                Text(
                    "This device is not routing an internet connection right now (for example it is an access point, or the WAN is down), so there is no link speed to show.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WifiCard(wifi: List<WifiRow>) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Text(
                "Wi-Fi networks (${wifi.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (wifi.isEmpty()) {
                Text(
                    "This device is not broadcasting any networks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }
            wifi.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (row.up) 1f else 0.5f)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        if (row.up) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = if (row.up) null else "Turned off",
                    )
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                row.ssid.ifEmpty { "(no name)" },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                if (row.encrypted) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = if (row.encrypted) "Password protected" else "Open network",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.bands.forEach { band ->
                                SuggestionChip(
                                    onClick = {},
                                    enabled = false,
                                    label = { Text(band.label(), style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.height(24.dp),
                                )
                            }
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${row.clients}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (row.clients == 1) "device" else "devices",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun Band.label(): String = when (this) {
    Band.GHZ_2_4 -> "2.4 GHz"
    Band.GHZ_5 -> "5 GHz"
    Band.GHZ_6 -> "6 GHz"
    Band.UNKNOWN -> "Wi-Fi"
}

@Composable
private fun StatsRow(ui: HomeUi) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Clients", ui.clientTotal?.toString() ?: "?", Modifier.weight(1f))
        StatTile("Load", ui.system?.let { String.format(Locale.US, "%.2f", it.load1) } ?: "?", Modifier.weight(1f))
        StatTile(
            "Memory",
            ui.system?.let { "${memoryUsedPercent(it.memTotalBytes, it.memAvailableBytes)}%" } ?: "?",
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        }
    }
}

@Composable
private fun SystemFooter(system: SystemInfo) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val style = MaterialTheme.typography.bodySmall
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        if (system.model.isNotBlank()) Text(system.model, style = style, color = color, textAlign = TextAlign.Center)
        if (system.firmware.isNotBlank()) Text(system.firmware, style = style, color = color, textAlign = TextAlign.Center)
        Text("Up ${formatUptime(system.uptimeSeconds)}", style = style, color = color)
    }
}

@Composable
private fun OfflineCard(error: Throwable, onRetry: () -> Unit) {
    val (title, detail) = when (error) {
        is SshError.AuthFailed -> "Login was refused" to "The saved password or key no longer works. Edit the device to fix it."
        is SshError.HostKeyChanged -> "This device's identity has changed" to
            "The app stopped connecting to be safe. Edit the device and test it again to review the new fingerprint."
        is SshError.HostKeyUnknown -> "This device is not trusted yet" to "Edit the device and test it to confirm its fingerprint."
        is RouterError.NotOpenWrt -> "This does not look like OpenWrt" to "The device has no ubus command."
        else -> "Cannot reach the device" to "Trying again automatically. Check that this phone is on the right network."
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Button(onClick = onRetry) { Text("Try now") }
        }
    }
}

@Composable
private fun NoDevice(onAddDevice: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Router, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text("No device selected", style = MaterialTheme.typography.titleLarge)
        Text(
            "Add an OpenWrt router or access point to see its status here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(1.dp))
        Button(onClick = onAddDevice) { Text("Add device") }
    }
}

private val previewUi = HomeUi(
    deviceName = "Home-Router",
    status = HomeStatus.Online,
    loaded = true,
    uplinkPresent = true,
    rate = Rate(42_300_000, 8_100_000),
    history = List(40) { Rate((10 + (it * 7) % 40) * 1_000_000L, (2 + it % 6) * 1_000_000L) },
    wifi = listOf(
        WifiRow("Home", listOf(Band.GHZ_2_4, Band.GHZ_5), encrypted = true, up = true, clients = 12),
        WifiRow("Guest", listOf(Band.GHZ_5), encrypted = false, up = true, clients = 2),
        WifiRow("IoT", listOf(Band.GHZ_2_4), encrypted = true, up = false, clients = 0),
    ),
    clientTotal = 21,
    system = SystemInfo("Home-Router", "GL.iNet GL-MT6000", "OpenWrt 24.10.2", 93_784, 0.08, 1_016_201_216, 600_000_000),
)

@Preview(showBackground = true)
@Composable
private fun HomeOnlinePreview() = OpenWrtTheme { HomeContent(previewUi, {}, {}, {}) }

@Preview(showBackground = true)
@Composable
private fun HomeOfflinePreview() = OpenWrtTheme {
    HomeContent(previewUi.copy(status = HomeStatus.Offline(SshError.Unreachable())), {}, {}, {})
}

@Preview(showBackground = true)
@Composable
private fun HomeNoUplinkPreview() = OpenWrtTheme {
    HomeContent(previewUi.copy(uplinkPresent = false, rate = null, history = emptyList()), {}, {}, {})
}
