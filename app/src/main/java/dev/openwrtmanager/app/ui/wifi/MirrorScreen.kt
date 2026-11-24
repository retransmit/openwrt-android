package dev.openwrtmanager.app.ui.wifi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import dev.openwrtmanager.app.ui.kit.MonoText
import dev.openwrtmanager.app.ui.kit.RowDivider
import dev.openwrtmanager.app.ui.kit.SectionCard
import dev.openwrtmanager.app.ui.kit.StateNotice
import dev.openwrtmanager.app.vlan.MirrorBlock

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MirrorScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: MirrorViewModel = hiltViewModel(),
    applyViewModel: ApplyViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start() }
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add a network from my router") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val blocked = ui.blocked
            when {
                ui.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                ui.failed -> StateNotice(
                    icon = Icons.Default.WifiOff,
                    title = "Could not read this device",
                    detail = "Go back and try again.",
                    fillScreen = true,
                )
                blocked == MirrorBlock.NOT_AN_ACCESS_POINT || blocked == MirrorBlock.NOT_DSA -> StateNotice(
                    icon = Icons.Default.Block,
                    title = "Not available on this device",
                    detail = blocked.explain(),
                    fillScreen = true,
                )
                else -> {
                    BannerCard(
                        text = "The access point gets the same Wi-Fi name, password and VLAN as your router. Your router hands out addresses and keeps these networks isolated.",
                        tone = BannerTone.INFO,
                    )
                    SectionCard(label = "Router", contentPadding = false) {
                        if (ui.routers.isEmpty()) {
                            Text(
                                "Add your main router as a device first.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        ui.routers.forEachIndexed { index, profile ->
                            if (index > 0) RowDivider()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.pickRouter(profile.id) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RadioButton(selected = ui.router?.id == profile.id, onClick = { viewModel.pickRouter(profile.id) })
                                Column {
                                    Text(profile.name, style = MaterialTheme.typography.bodyLarge)
                                    MonoText(profile.host, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (ui.readingRouter) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                    ui.error?.let { BannerCard(text = it, icon = Icons.Default.Warning, tone = BannerTone.ERROR) }
                    if (ui.router != null && !ui.readingRouter && ui.error == null) {
                        SectionCard(label = "Networks", contentPadding = false) {
                            if (ui.sources.isEmpty()) {
                                Text(
                                    "${ui.router?.name} has no VLAN networks with Wi-Fi. Create a guest or IoT network there first.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                            ui.sources.forEachIndexed { index, source ->
                                if (index > 0) RowDivider()
                                CheckboxListRow(
                                    title = source.ssid,
                                    detail = "${source.name} · VLAN ${source.vid}",
                                    checked = source.name in ui.chosen,
                                    onCheckedChange = { viewModel.toggle(source.name) },
                                )
                            }
                        }
                        SectionCard(label = "Port cabled to the router") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                ui.ports.forEach { port ->
                                    FilterChip(selected = ui.uplink == port, onClick = { viewModel.onUplink(port) }, label = { Text(port) })
                                }
                            }
                        }
                        ui.warnings.forEach { warning ->
                            BannerCard(text = warning, icon = Icons.Default.Warning, tone = BannerTone.WARNING)
                        }
                        blocked?.let { BannerCard(text = it.explain(), icon = Icons.Default.Warning, tone = BannerTone.ERROR) }
                        Button(
                            enabled = ui.chosen.isNotEmpty(),
                            onClick = { viewModel.review()?.let { change -> applyViewModel.review(change, onDone) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Review") }
                    }
                }
            }
        }
    }
    ApplySheet(applyViewModel)
}

private fun MirrorBlock.explain(): String = when (this) {
    MirrorBlock.NOT_AN_ACCESS_POINT -> "This device has its own internet connection. Create the network here with the guest or IoT wizard instead."
    MirrorBlock.NOT_DSA -> "This device's home network is not on a bridge with switch ports, so it cannot carry VLANs."
    MirrorBlock.UPLINK_NOT_A_PORT -> "Pick the port that is cabled to your router."
    MirrorBlock.VID_TAKEN -> "This device already uses one of these VLAN IDs."
    MirrorBlock.NAME_TAKEN -> "This device already has a network with one of these names."
    MirrorBlock.NO_RADIO -> "This device has no Wi-Fi radio."
    MirrorBlock.NOTHING_CHOSEN -> "Pick at least one network."
}
