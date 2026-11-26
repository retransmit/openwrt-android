package dev.openwrtmanager.app.ui.network

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.clients.Pool
import dev.openwrtmanager.app.clients.Reservation
import dev.openwrtmanager.app.ui.clients.duration
import dev.openwrtmanager.app.ui.kit.ChipTone
import dev.openwrtmanager.app.ui.kit.IconBadge
import dev.openwrtmanager.app.ui.kit.ListCard
import dev.openwrtmanager.app.ui.kit.MonoText
import dev.openwrtmanager.app.ui.kit.RowDivider
import dev.openwrtmanager.app.ui.kit.SectionCard
import dev.openwrtmanager.app.ui.kit.TagChip

private fun interfaceIcon(name: String): ImageVector = when {
    name.startsWith("wan") -> Icons.Outlined.Public
    name.contains("tailscale") || name.startsWith("wg") || name.contains("vpn") -> Icons.Outlined.VpnKey
    name == "lan" -> Icons.Outlined.Lan
    else -> Icons.Outlined.Hub
}

private fun poolRangeText(pool: Pool): String {
    val end = pool.start + pool.limit - 1
    return if (pool.limit > 0 && end <= 254) {
        "DHCP .${pool.start}-.$end (${pool.limit}) · ${pool.leasetime}"
    } else {
        "DHCP ${pool.limit} from .${pool.start} · ${pool.leasetime}"
    }
}

/** Interfaces list with status badges, traffic counters, and compact DHCP pool pills. */
@Composable
internal fun InterfacesSection(state: ExtrasUi.Ready, onEditPool: (Pool) -> Unit) {
    ListCard(label = "Interfaces · ${state.interfaces.size}") {
        state.interfaces.forEachIndexed { index, row ->
            if (index > 0) RowDivider()
            val s = row.status
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(interfaceIcon(s.name))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium)
                            if (s.proto.isNotEmpty()) TagChip(s.proto)
                        }
                        val meta = listOfNotNull(s.ipv4, s.uptimeSeconds?.let { "up ${duration(it)}" })
                        if (meta.isNotEmpty()) {
                            MonoText(meta.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                    TagChip(if (s.up) "Up" else "Down", tone = if (s.up) ChipTone.OK else ChipTone.ERROR)
                }
                if (s.rxBytes != null && s.txBytes != null) {
                    MonoText(
                        "↓ ${bytes(s.rxBytes)}   ↑ ${bytes(s.txBytes)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 11.sp,
                        modifier = Modifier.padding(start = 48.dp),
                    )
                }
                row.pool?.let { pool ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .padding(start = 48.dp)
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onEditPool(pool) },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            MonoText(
                                poolRangeText(pool),
                                color = MaterialTheme.colorScheme.onSurface,
                                size = 11.sp,
                                maxLines = 2,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Outlined.ChevronRight,
                                contentDescription = "Edit pool",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** DHCP pools summary + static reservations with full non-truncated MAC addresses. */
@Composable
internal fun DhcpSection(
    state: ExtrasUi.Ready,
    onEditPool: (Pool) -> Unit,
    onEditReservation: (Reservation) -> Unit,
    onRemoveReservation: (Reservation) -> Unit,
) {
    val pools = state.interfaces.mapNotNull { it.pool }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.reservations.isEmpty()) {
            SectionCard(label = "Address reservations · 0") {
                Text(
                    "No static leases yet. Open any device on the Clients tab to reserve its IP address.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            ListCard(label = "Address reservations · ${state.reservations.size}") {
                state.reservations.forEachIndexed { index, reservation ->
                    if (index > 0) RowDivider()
                    Row(
                        Modifier.padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(reservation.name ?: "Unnamed", style = MaterialTheme.typography.titleSmall)
                                reservation.ip?.let { TagChip(it, tone = ChipTone.ACCENT) }
                            }
                            MonoText(
                                reservation.mac,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                size = 12.sp,
                                maxLines = 2,
                            )
                        }
                        IconButton(onClick = { onEditReservation(reservation) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit reservation")
                        }
                        IconButton(onClick = { onRemoveReservation(reservation) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Remove reservation", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        if (pools.isNotEmpty()) {
            ListCard(label = "DHCP address pools · ${pools.size}") {
                pools.forEachIndexed { index, pool ->
                    if (index > 0) RowDivider()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onEditPool(pool) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconBadge(Icons.Outlined.Tune)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(pool.iface, style = MaterialTheme.typography.titleSmall)
                            MonoText(poolRangeText(pool), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = "Edit pool",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PoolDialog(pool: Pool, onDismiss: () -> Unit, onSave: (Int, Int, String) -> Unit) {
    var start by remember { mutableStateOf(pool.start.toString()) }
    var limit by remember { mutableStateOf(pool.limit.toString()) }
    var lease by remember { mutableStateOf(pool.leasetime) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Addresses on ${pool.iface}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MonoText("Network ${pool.subnet}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                NumberField("First address (last number)", start) { start = it }
                NumberField("Number of addresses", limit) { limit = it }
                OutlinedTextField(lease, { lease = it.trim() }, label = { Text("Lease time") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(start.toIntOrNull() ?: -1, limit.toIntOrNull() ?: -1, lease) }) { Text("Review") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ReservationDialog(reservation: Reservation, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var ip by remember { mutableStateOf(reservation.ip.orEmpty()) }
    var name by remember { mutableStateOf(reservation.name.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit reservation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MonoText(reservation.mac, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    ip, { ip = it.trim() }, label = { Text("Address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(name, { name = it.trim() }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(ip, name) }) { Text("Review") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(5)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun bytes(value: Long): String = when {
    value >= 1L shl 30 -> "%.1f GB".format(value / (1L shl 30).toDouble())
    value >= 1L shl 20 -> "%.1f MB".format(value / (1L shl 20).toDouble())
    value >= 1L shl 10 -> "%.0f KB".format(value / 1024.0)
    else -> "$value B"
}

/** Holds which dialog is open and turns its result into a change to review. */
internal class ExtrasDialogs {
    var pool by mutableStateOf<Pool?>(null)
    var reservation by mutableStateOf<Reservation?>(null)
}

@Composable
internal fun ExtrasDialogsHost(dialogs: ExtrasDialogs, viewModel: NetworkExtrasViewModel, review: (ChangeSet?) -> Boolean) {
    dialogs.pool?.let { pool ->
        PoolDialog(pool, onDismiss = { dialogs.pool = null }) { start, limit, lease ->
            val change = viewModel.editPool(pool, start, limit, lease)
            if (change != null) {
                dialogs.pool = null
                review(change)
            } else if ((viewModel.ui.value as? ExtrasUi.Ready)?.message == null) {
                dialogs.pool = null
            }
        }
    }
    dialogs.reservation?.let { reservation ->
        ReservationDialog(reservation, onDismiss = { dialogs.reservation = null }) { ip, name ->
            val change = viewModel.editReservation(reservation, ip, name)
            if (change != null) {
                dialogs.reservation = null
                review(change)
            } else if ((viewModel.ui.value as? ExtrasUi.Ready)?.message == null) {
                dialogs.reservation = null
            }
        }
    }
    (viewModel.ui.value as? ExtrasUi.Ready)?.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearMessage,
            confirmButton = { TextButton(onClick = viewModel::clearMessage) { Text("OK") } },
            text = { Text(message) },
        )
    }
}
