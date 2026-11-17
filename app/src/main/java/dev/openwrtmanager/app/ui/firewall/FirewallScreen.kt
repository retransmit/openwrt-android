package dev.openwrtmanager.app.ui.firewall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.firewall.PortForward
import dev.openwrtmanager.app.firewall.TrafficRule
import dev.openwrtmanager.app.firewall.Zone
import dev.openwrtmanager.app.ui.apply.ApplySheet
import dev.openwrtmanager.app.ui.apply.ApplyViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallScreen(
    onBack: () -> Unit,
    viewModel: FirewallViewModel = hiltViewModel(),
    applyViewModel: ApplyViewModel = hiltViewModel(),
) {
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        onPauseOrDispose { }
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ForwardEdit?>(null) }
    var removingRule by remember { mutableStateOf<TrafficRule?>(null) }
    val scope = rememberCoroutineScope()
    val review: (ChangeSet?) -> Unit = { change -> if (change != null && !change.isEmpty()) applyViewModel.review(change) { viewModel.load() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Firewall") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val state = ui) {
                FirewallUi.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                FirewallUi.Offline -> Text("Connect to a device on the Home tab to see its firewall.")
                FirewallUi.Failed -> {
                    Text("Could not read the firewall.")
                    Button(onClick = viewModel::load) { Text("Try again") }
                }
                is FirewallUi.Ready -> {
                    Text("Port forwards", style = MaterialTheme.typography.titleMedium)
                    if (state.privateWan) {
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                            Text(
                                "This router's internet address is private, so it sits behind another router. A port forward " +
                                    "here works only if that router forwards the same port to this one.",
                                Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                    if (state.view.forwards.isEmpty()) Text("No port forwards.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ForwardList(
                        state.view.forwards,
                        onToggle = { review(viewModel.toggleForward(it)) },
                        onEdit = { editing = ForwardEdit(it) },
                        onRemove = { review(viewModel.removeForward(it)) },
                    )
                    OutlinedButton(onClick = { editing = ForwardEdit(null) }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text(" Add port forward")
                    }

                    Text("Traffic rules", style = MaterialTheme.typography.titleMedium)
                    RuleList(state.view.rules, onToggle = { review(viewModel.toggleRule(it)) }, onRemove = { removingRule = it })

                    Text("Zones", style = MaterialTheme.typography.titleMedium)
                    state.view.zones.forEach { ZoneCard(it) }
                }
            }
        }
    }

    val ready = ui as? FirewallUi.Ready
    ready?.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearMessage,
            confirmButton = { TextButton(onClick = viewModel::clearMessage) { Text("OK") } },
            text = { Text(message) },
        )
    }
    if (ready != null) {
        editing?.let { edit ->
            ForwardSheet(
                existing = edit.existing,
                clients = ready.clients,
                onDismiss = { editing = null },
            ) { input ->
                scope.launch {
                    val change = if (edit.existing == null) viewModel.addForward(input) else viewModel.editForward(edit.existing, input)
                    if (change != null) {
                        editing = null
                        review(change)
                    }
                }
            }
        }
    }
    removingRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { removingRule = null },
            title = { Text("Remove ${rule.name}?") },
            text = {
                Text(
                    if (rule.standard) {
                        "This is one of OpenWrt's standard rules. ${rule.description}. Removing it can break things that rely on it; " +
                            "turning it off is usually enough."
                    } else {
                        "${rule.description}."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removingRule = null
                    review(viewModel.removeRule(rule))
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removingRule = null }) { Text("Cancel") } },
        )
    }
    ApplySheet(applyViewModel)
}

/** Which forward the editor is open for; null inside means a new one. */
private class ForwardEdit(val existing: PortForward?)

@Composable
private fun ForwardList(forwards: List<PortForward>, onToggle: (PortForward) -> Unit, onEdit: (PortForward) -> Unit, onRemove: (PortForward) -> Unit) {
    if (forwards.isEmpty()) return
    ElevatedCard(Modifier.fillMaxWidth()) {
        forwards.forEachIndexed { index, forward ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(forward.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${forward.protocols.sorted().joinToString("/") { it.uppercase() }} ${forward.outsidePort} to " +
                                "${forward.insideIp ?: "?"}${forward.insidePort?.let { ":$it" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = forward.enabled, onCheckedChange = { onToggle(forward) })
                }
                Row {
                    TextButton(onClick = { onEdit(forward) }) { Text("Edit") }
                    TextButton(onClick = { onRemove(forward) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun RuleList(rules: List<TrafficRule>, onToggle: (TrafficRule) -> Unit, onRemove: (TrafficRule) -> Unit) {
    if (rules.isEmpty()) {
        Text("No traffic rules.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        rules.forEachIndexed { index, rule ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.name, style = MaterialTheme.typography.bodyLarge)
                    Text(rule.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { onRemove(rule) }) { Text("Remove") }
                }
                Switch(checked = rule.enabled, onCheckedChange = { onToggle(rule) })
            }
        }
    }
}

@Composable
private fun ZoneCard(zone: Zone) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(zone.name, style = MaterialTheme.typography.titleSmall)
            Text("Networks: ${zone.networks.joinToString(", ").ifEmpty { "none" }}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Into the router: ${policy(zone.input)}. Out: ${policy(zone.output)}. Between its networks: ${policy(zone.forward)}.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (zone.forwardsTo.isEmpty()) "Cannot start connections to other zones." else "May start connections to: ${zone.forwardsTo.joinToString(", ")}.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (zone.masq) Text("Shares one address for the internet (masquerading).", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun policy(value: String): String = when (value.uppercase()) {
    "ACCEPT" -> "allowed"
    "REJECT" -> "refused"
    "DROP" -> "dropped"
    "" -> "default"
    else -> value.lowercase()
}
