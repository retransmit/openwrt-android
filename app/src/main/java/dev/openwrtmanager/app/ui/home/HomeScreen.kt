package dev.openwrtmanager.app.ui.home

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.ui.common.formatRate
import dev.openwrtmanager.app.ui.common.formatUptime
import dev.openwrtmanager.app.ui.common.memoryUsedPercent
import dev.openwrtmanager.app.ui.theme.OpenWrtTheme
import dev.openwrtmanager.app.ui.wifi.QrDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.sp
import dev.openwrtmanager.app.ui.common.flashUsedPercent
import dev.openwrtmanager.app.ui.common.formatBytes
import dev.openwrtmanager.app.ui.kit.IconBadge
import dev.openwrtmanager.app.ui.kit.Label
import dev.openwrtmanager.app.ui.kit.LiveMarker
import dev.openwrtmanager.app.ui.kit.MonoText
import dev.openwrtmanager.app.ui.kit.Readout
import dev.openwrtmanager.app.ui.kit.SectionCard
import dev.openwrtmanager.app.ui.kit.StatTile
import dev.openwrtmanager.app.ui.kit.StatusDot
import dev.openwrtmanager.app.ui.kit.TagChip
import dev.openwrtmanager.app.ui.theme.LocalExtraColors
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
    val qr by viewModel.qr.collectAsStateWithLifecycle()
    HomeContent(ui, viewModel::refresh, onSwitchDevice, onAddDevice, onShowQr = viewModel::showQr)
    qr?.let { QrDialog(it.ssid, it.payload, onDismiss = viewModel::dismissQr) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    ui: HomeUi,
    onRefresh: () -> Unit,
    onSwitchDevice: () -> Unit,
    onAddDevice: () -> Unit,
    onShowQr: (String) -> Unit = {},
) {
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
                        if (ui.status != HomeStatus.NoDevice) StatusLine(ui.status, ui.responseMs)
                    }
                },
                actions = {
                    IconButton(
                        onClick = onSwitchDevice,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(36.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "Switch device", modifier = Modifier.size(20.dp))
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                (ui.status as? HomeStatus.Offline)?.let { OfflineCard(it.error, onRefresh) }
                if (!ui.loaded) {
                    if (ui.status !is HomeStatus.Offline) LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    // Last known values stay visible but dimmed while the device is unreachable.
                    Column(
                        modifier = Modifier.alpha(if (stale) 0.5f else 1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (ui.uplinkPresent) SpeedCard(ui.rate, ui.history) else NoUplinkCard()
                        StatsGrid(ui)
                        WifiCard(ui.wifi, onShowQr)
                        ui.system?.let { SystemFooter(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(status: HomeStatus, responseMs: Long?) {
    val extra = LocalExtraColors.current
    val (label, color) = when (status) {
        HomeStatus.Online -> "Online" to extra.online
        HomeStatus.Connecting -> "Connecting" to MaterialTheme.colorScheme.outline
        is HomeStatus.Offline -> "Offline" to MaterialTheme.colorScheme.error
        HomeStatus.NoDevice -> "" to Color.Transparent
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusDot(color, glow = status == HomeStatus.Online)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status == HomeStatus.Online && responseMs != null) {
            MonoText("· $responseMs ms", color = MaterialTheme.colorScheme.onSurfaceVariant, size = 11.sp)
        }
    }
}

/** "42.3 Mbps" split into the number and its unit, for a readout. */
private fun rateParts(bps: Long): Pair<String, String> = formatRate(bps).let { it.substringBefore(' ') to it.substringAfter(' ') }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedCard(rate: Rate?, history: List<Rate>) {
    SectionCard(label = "Internet", trailing = { LiveMarker() }, glow = true) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SpeedFigure(Icons.Default.ArrowDownward, "Download", rate?.downBps, MaterialTheme.colorScheme.primary)
            SpeedFigure(Icons.Default.ArrowUpward, "Upload", rate?.upBps, MaterialTheme.colorScheme.tertiary)
        }
        RateGraph(history, slots = GRAPH_SLOTS, modifier = Modifier.fillMaxWidth().height(96.dp))
    }
}

@Composable
private fun SpeedFigure(icon: ImageVector, label: String, bps: Long?, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        if (bps == null) {
            Text("measuring", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val (value, unit) = rateParts(bps)
            Readout(value, unit = unit, color = tint, size = 24.sp)
        }
    }
}

@Composable
private fun NoUplinkCard() {
    SectionCard(label = "Internet") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(Icons.Default.CloudOff)
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

@Composable
private fun StatsGrid(ui: HomeUi) {
    val system = ui.system
    val gap = Arrangement.spacedBy(8.dp)
    Column(verticalArrangement = gap) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = gap) {
            StatTile("Uptime", system?.let { formatUptime(it.uptimeSeconds) } ?: "?", Modifier.weight(2f).fillMaxHeight())
            StatTile("Clients", ui.clientTotal?.toString() ?: "?", Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = gap) {
            StatTile("Load", system?.let { String.format(Locale.US, "%.2f", it.load1) } ?: "?", Modifier.weight(1f).fillMaxHeight())
            val ram = system?.let { memoryUsedPercent(it.memTotalBytes, it.memAvailableBytes) }
            StatTile("RAM", ram?.toString() ?: "?", Modifier.weight(1f).fillMaxHeight(), unit = ram?.let { "%" }, usage = ram)
            val flash = system?.let { flashUsedPercent(it.flashTotalBytes, it.flashUsedBytes) }
            val free = system?.let { s -> s.flashTotalBytes?.let { total -> s.flashUsedBytes?.let { used -> "${formatBytes(total - used)} free" } } }
            StatTile(
                "Flash",
                flash?.toString() ?: "–",
                Modifier.weight(1f).fillMaxHeight(),
                unit = flash?.let { "%" },
                usage = flash,
                detail = free,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WifiCard(wifi: List<WifiRow>, onShowQr: (String) -> Unit) {
    SectionCard(label = "Wi-Fi networks · ${wifi.size}") {
        if (wifi.isEmpty()) {
            Text(
                "This device is not broadcasting any networks.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        wifi.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().alpha(if (row.up) 1f else 0.5f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconBadge(
                    if (row.up) Icons.Default.Wifi else Icons.Default.WifiOff,
                    contentDescription = if (row.up) null else "Turned off",
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.bands.forEach { band -> TagChip(band.label()) }
                    }
                }
                IconButton(
                    onClick = { onShowQr(row.ssid) },
                    modifier = Modifier.size(34.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
                ) {
                    Icon(Icons.Default.QrCode2, contentDescription = "Show join code for ${row.ssid}", modifier = Modifier.size(18.dp))
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(min = 44.dp)) {
                    Readout("${row.clients}", size = 16.sp)
                    Label(if (row.clients == 1) "device" else "devices")
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
private fun SystemFooter(system: SystemInfo) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        if (system.model.isNotBlank()) MonoText(system.model, color = color, size = 11.sp)
        if (system.firmware.isNotBlank()) MonoText(system.firmware, color = color, size = 11.sp)
    }
}

@Composable
private fun OfflineCard(error: Throwable, onRetry: () -> Unit) {
    val (title, detail) = offlineMessage(error)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
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
    system = SystemInfo("Home-Router", "GL.iNet GL-MT6000", "OpenWrt 24.10.2", 93_784, 0.08, 1_016_201_216, 600_000_000, 8_388_608, 1_006_632),
    responseMs = 18,
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

@Preview(name = "Home dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeDarkPreview() = OpenWrtTheme { HomeContent(previewUi, {}, {}, {}) }

@Preview(name = "Home long names, no flash")
@Composable
private fun HomeEdgePreview() = OpenWrtTheme {
    HomeContent(
        previewUi.copy(
            wifi = listOf(WifiRow("A-very-long-network-name-1234567", listOf(Band.GHZ_2_4, Band.GHZ_5, Band.GHZ_6), true, true, 128)),
            system = previewUi.system?.copy(flashTotalBytes = null, flashUsedBytes = null),
        ),
        {}, {}, {},
    )
}
