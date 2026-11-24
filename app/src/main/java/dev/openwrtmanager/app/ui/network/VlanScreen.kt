package dev.openwrtmanager.app.ui.network

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.ui.apply.ApplySheet
import dev.openwrtmanager.app.ui.apply.ApplyViewModel
import dev.openwrtmanager.app.ui.kit.BannerCard
import dev.openwrtmanager.app.ui.kit.BannerTone
import dev.openwrtmanager.app.ui.kit.ChipTone
import dev.openwrtmanager.app.ui.kit.Label
import dev.openwrtmanager.app.ui.kit.MonoText
import dev.openwrtmanager.app.ui.kit.Readout
import dev.openwrtmanager.app.ui.kit.SectionCard
import dev.openwrtmanager.app.ui.kit.StateNotice
import dev.openwrtmanager.app.ui.kit.SwitchListRow
import dev.openwrtmanager.app.ui.kit.TagChip
import dev.openwrtmanager.app.vlan.PortRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VlanScreen(
    viewModel: VlanViewModel = hiltViewModel(),
    extras: NetworkExtrasViewModel = hiltViewModel(),
    applyViewModel: ApplyViewModel = hiltViewModel(),
) {
    LifecycleResumeEffect(viewModel) {
        viewModel.load()
        extras.load()
        onPauseOrDispose { }
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val extrasUi by extras.ui.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<VlanRow?>(null) }
    var creating by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<VlanRow?>(null) }
    val dialogs = remember { ExtrasDialogs() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val review: (ChangeSet?) -> Boolean = { change ->
        if (change != null) {
            applyViewModel.review(change) {
                viewModel.load()
                extras.load()
            }
        }
        change != null
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { MediumTopAppBar(title = { Text("Network") }, scrollBehavior = scrollBehavior) },
        floatingActionButton = {
            if (ui is VlanUi.Ready) {
                ExtendedFloatingActionButton(
                    onClick = { creating = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Create VLAN") },
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = {
                viewModel.load()
                extras.load()
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                (extrasUi as? ExtrasUi.Ready)?.let { state -> InterfacesSection(state, onEditPool = { dialogs.pool = it }) }
                if (ui is VlanUi.Ready) Label("VLANs", Modifier.padding(horizontal = 4.dp))
                when (val state = ui) {
                    VlanUi.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                    VlanUi.Offline -> StateNotice(
                        icon = Icons.Default.LinkOff,
                        title = "Not connected",
                        detail = "Connect to a device on the Home tab to manage its network and VLANs.",
                    )
                    VlanUi.Failed -> StateNotice(
                        icon = Icons.Default.LinkOff,
                        title = "Could not read network settings",
                        detail = "The device did not answer as expected.",
                        actionLabel = "Try again",
                        onAction = viewModel::load,
                    )
                    VlanUi.Unsupported -> StateNotice(
                        icon = Icons.Default.Lan,
                        title = "VLANs unavailable here",
                        detail = "This device's home network is not on a DSA bridge with switch ports.",
                    )
                    is VlanUi.Ready -> {
                        BannerCard(
                            text = if (state.filtering) {
                                "Each port is a device port (untagged) in at most one VLAN, and can carry others tagged to an access point."
                            } else {
                                "VLANs are off: every port is on your home network. Filtering turns on with the first VLAN you create."
                            },
                            tone = BannerTone.INFO,
                        )
                        state.rows.forEach { row ->
                            VlanCard(row, state.ports, onEdit = { editing = row }, onRemove = { removing = row })
                        }
                    }
                }
                (extrasUi as? ExtrasUi.Ready)?.let { state ->
                    ReservationsSection(
                        state,
                        onEdit = { dialogs.reservation = it },
                        onRemove = { review(extras.removeReservation(it)) },
                    )
                }
                if (ui is VlanUi.Ready) Spacer(Modifier.height(76.dp))
            }
        }
    }

    val ready = ui as? VlanUi.Ready
    ready?.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearMessage,
            confirmButton = { TextButton(onClick = viewModel::clearMessage) { Text("OK") } },
            title = { Text("This would not work") },
            text = { Text(message) },
        )
    }
    if (ready != null) {
        editing?.let { row ->
            PortRolesSheet(row, ready.ports, onDismiss = { editing = null }) { roles ->
                if (review(viewModel.editRoles(row.vid, roles))) editing = null
            }
        }
        if (creating) {
            CreateVlanSheet(ready.suggestedVid, ready.ports, onDismiss = { creating = false }) { vid, name, ports, routed ->
                if (review(viewModel.create(vid, name, ports, routed))) creating = false
            }
        }
    }
    removing?.let { row ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove VLAN ${row.vid}?") },
            text = {
                Text(
                    if (row.subnet != null) {
                        "This also removes the ${row.name} network, its address range, its Wi-Fi and its firewall rules."
                    } else {
                        "Ports stop carrying this VLAN."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    review(viewModel.remove(row.vid))
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
    ExtrasDialogsHost(dialogs, extras, review)
    ApplySheet(applyViewModel)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VlanCard(row: VlanRow, ports: List<String>, onEdit: () -> Unit, onRemove: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onEdit)) {
        val title = if (row.home) "Home network" else row.name ?: "VLAN ${row.vid}"
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Readout("${row.vid}", color = MaterialTheme.colorScheme.onPrimaryContainer, size = 16.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                MonoText(
                    row.subnet ?: if (!row.home) "no address on this router" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.removable) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Remove VLAN ${row.vid}", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ports.forEach { port ->
                val role = row.roles[port] ?: PortRole.NONE
                if (role != PortRole.NONE) {
                    TagChip(
                        "$port · ${if (role == PortRole.UNTAGGED) "device" else "tagged"}",
                        tone = if (role == PortRole.UNTAGGED) ChipTone.ACCENT else ChipTone.NEUTRAL,
                    )
                }
            }
            if (row.roles.values.none { it != PortRole.NONE }) {
                Text("No ports, Wi-Fi only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** One row per port: not a member, untagged (device port) or tagged (link to an access point). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortRolesEditor(ports: List<String>, roles: Map<String, PortRole>, onRole: (String, PortRole) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ports.forEach { port ->
            MonoText(port, size = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val options = listOf(PortRole.NONE to "None", PortRole.UNTAGGED to "Device port", PortRole.TAGGED to "AP link")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (role, label) ->
                    SegmentedButton(
                        selected = (roles[port] ?: PortRole.NONE) == role,
                        onClick = { onRole(port, role) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortRolesSheet(row: VlanRow, ports: List<String>, onDismiss: () -> Unit, onSave: (Map<String, PortRole>) -> Unit) {
    val roles = remember(row) { mutableStateMapOf<String, PortRole>().apply { putAll(row.roles) } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (row.home) "Home network ports" else "Ports for ${row.name ?: "VLAN ${row.vid}"}", style = MaterialTheme.typography.titleLarge)
            Text(
                "A device port puts everything plugged into it on this VLAN. An AP link carries it tagged to an access point " +
                    "that understands VLANs; do not use it for a PC or a plain switch.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PortRolesEditor(ports, roles) { port, role -> roles[port] = role }
            Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(roles.toMap()) }) { Text("Review") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateVlanSheet(
    suggestedVid: Int,
    ports: List<String>,
    onDismiss: () -> Unit,
    onCreate: (Int, String, Map<String, PortRole>, Boolean) -> Unit,
) {
    var vid by remember { mutableStateOf(suggestedVid.toString()) }
    var name by remember { mutableStateOf("") }
    var routed by remember { mutableStateOf(true) }
    val roles = remember { mutableStateMapOf<String, PortRole>() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Create VLAN", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = vid,
                onValueChange = { vid = it.filter(Char::isDigit).take(4) },
                label = { Text("VLAN ID") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.lowercase().take(11) },
                label = { Text("Name") },
                supportingText = { Text("Lowercase letters, digits and underscores, for example cameras") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SectionCard(contentPadding = false) {
                SwitchListRow(
                    title = "Its own network",
                    detail = if (routed) {
                        "Gets an address range and internet access on this router, isolated from your home network."
                    } else {
                        "Passes through only, to be carried to another device. No address on this router."
                    },
                    checked = routed,
                    onCheckedChange = { routed = it },
                )
            }
            Text("Ports", style = MaterialTheme.typography.titleMedium)
            PortRolesEditor(ports, roles) { port, role -> roles[port] = role }
            Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                Button(
                    enabled = vid.isNotEmpty() && name.isNotEmpty(),
                    onClick = { onCreate(vid.toInt(), name, roles.filterValues { it != PortRole.NONE }, routed) },
                ) { Text("Review") }
            }
        }
    }
}
