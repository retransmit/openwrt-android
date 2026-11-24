package dev.openwrtmanager.app.ui.firewall

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.openwrtmanager.app.clients.Client
import dev.openwrtmanager.app.firewall.ForwardInput
import dev.openwrtmanager.app.firewall.PortForward
import dev.openwrtmanager.app.ui.kit.BannerCard
import dev.openwrtmanager.app.ui.kit.BannerTone
import dev.openwrtmanager.app.ui.kit.Label
import dev.openwrtmanager.app.ui.kit.MonoText
import dev.openwrtmanager.app.ui.kit.RowDivider
import dev.openwrtmanager.app.ui.kit.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForwardSheet(existing: PortForward?, clients: List<Client>, onDismiss: () -> Unit, onSave: (ForwardInput) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var outside by remember { mutableStateOf(existing?.outsidePort.orEmpty()) }
    var inside by remember { mutableStateOf(existing?.insidePort?.takeIf { it != existing.outsidePort }.orEmpty()) }
    var tcp by remember { mutableStateOf(existing?.protocols?.contains("tcp") ?: true) }
    var udp by remember { mutableStateOf(existing?.protocols?.contains("udp") ?: false) }
    var target by remember { mutableStateOf(clients.firstOrNull { it.ip == existing?.insideIp }) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (existing == null) "Add port forward" else "Change port forward", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tcp, onClick = { tcp = !tcp }, label = { Text("TCP") })
                FilterChip(selected = udp, onClick = { udp = !udp }, label = { Text("UDP") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    outside,
                    { outside = it.trim() },
                    label = { Text("Outside port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    inside,
                    { inside = it.trim() },
                    label = { Text("Inside (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Label("Target device")
            if (existing != null && clients.none { it.ip == existing.insideIp }) {
                BannerCard(
                    text = "This forward points to ${existing.insideIp ?: "no address"}, which matches no listed device. Pick the target device below.",
                    icon = Icons.Default.Warning,
                    tone = BannerTone.ERROR,
                )
            }
            SectionCard(contentPadding = false) {
                if (clients.isEmpty()) {
                    Text(
                        "No devices with an address are connected right now.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                clients.forEachIndexed { index, client ->
                    if (index > 0) RowDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { target = client }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = target?.mac == client.mac, onClick = { target = client })
                        Column {
                            Text(client.name ?: "Unknown device", style = MaterialTheme.typography.bodyLarge)
                            MonoText(
                                listOfNotNull(client.ip, "reserved".takeIf { client.reserved }).joinToString(" · "),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            target?.takeIf { !it.reserved }?.let {
                BannerCard(
                    text = "${it.name ?: "This device"} will also get its IP address reserved so the forward keeps working.",
                    icon = Icons.Default.Info,
                    tone = BannerTone.INFO,
                )
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    enabled = target != null && name.isNotBlank() && outside.isNotEmpty(),
                    onClick = {
                        val protocols = buildSet { if (tcp) add("tcp"); if (udp) add("udp") }
                        onSave(ForwardInput(name, protocols, outside, inside.ifEmpty { null }, target!!))
                    },
                ) { Text("Review") }
            }
        }
    }
}
