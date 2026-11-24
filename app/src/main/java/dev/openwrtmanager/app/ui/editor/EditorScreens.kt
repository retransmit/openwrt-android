package dev.openwrtmanager.app.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openwrtmanager.app.editor.EditorEdit
import dev.openwrtmanager.app.editor.ItemState
import dev.openwrtmanager.app.editor.OptionView
import dev.openwrtmanager.app.editor.SectionView
import dev.openwrtmanager.app.editor.isSecretOption
import dev.openwrtmanager.app.editor.stagedView
import dev.openwrtmanager.app.ui.apply.ApplySheet
import dev.openwrtmanager.app.ui.apply.ApplyViewModel
import dev.openwrtmanager.app.ui.kit.BannerCard
import dev.openwrtmanager.app.ui.kit.BannerTone
import dev.openwrtmanager.app.ui.kit.ChipTone
import dev.openwrtmanager.app.ui.kit.ListCard
import dev.openwrtmanager.app.ui.kit.ListRow
import dev.openwrtmanager.app.ui.kit.RowDivider
import dev.openwrtmanager.app.ui.kit.SwitchListRow
import dev.openwrtmanager.app.ui.kit.TagChip
import dev.openwrtmanager.app.ui.kit.SectionCard as KitSectionCard
import dev.openwrtmanager.app.ui.theme.MonoFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigListScreen(onOpen: (String) -> Unit, onBack: () -> Unit, viewModel: EditorViewModel = hiltViewModel()) {
    LaunchedEffect(viewModel) { viewModel.loadConfigs() }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = { Text("Config editor") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (ui.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape)) }
            if (ui.offline) item { BannerCard(text = "Connect to a device on the Home tab first.", tone = BannerTone.INFO) }
            item {
                BannerCard(
                    text = "Every UCI config file on the device. Changes are staged locally, reviewed, and protected by the 90s undo timer.",
                    tone = BannerTone.INFO,
                )
            }
            if (ui.configs.isNotEmpty()) {
                item {
                    ListCard(label = "Config files · ${ui.configs.size}") {
                        ui.configs.forEachIndexed { index, name ->
                            if (index > 0) RowDivider()
                            ListRow(
                                title = name,
                                icon = Icons.Outlined.Description,
                                onClick = { onOpen(name) },
                            )
                        }
                    }
                }
            }
        }
    }
    ui.message?.let { MessageDialog(it, viewModel::clearMessage) }
}

private data class OptionTarget(val section: String, val option: String?, val value: String, val isList: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditScreen(
    name: String,
    onBack: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
    applyViewModel: ApplyViewModel = hiltViewModel(),
) {
    LaunchedEffect(name) { viewModel.open(name) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<OptionTarget?>(null) }
    var addingSection by remember { mutableStateOf(false) }
    var showStaged by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, fontFamily = MonoFamily) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            if (ui.config != null) {
                ExtendedFloatingActionButton(
                    onClick = { addingSection = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add section") },
                )
            }
        },
        bottomBar = {
            if (ui.edits.isNotEmpty()) {
                BottomAppBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    FilledTonalButton(onClick = { showStaged = true }, modifier = Modifier.padding(start = 8.dp)) {
                        Text("${ui.edits.size} staged")
                    }
                    Row(
                        Modifier.weight(1f).padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = viewModel::discard) { Text("Discard") }
                        Button(onClick = { scope.launch { viewModel.review()?.let { applyViewModel.review(it) { viewModel.applied() } } } }) {
                            Text("Review")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
            val config = ui.config?.takeIf { it.name == name }
            val sections = remember(config, ui.edits) { config?.let { stagedView(it, ui.edits) }.orEmpty() }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sections, key = { it.name }) { section ->
                    SectionCard(
                        section = section,
                        onEdit = { option, value, isList -> target = OptionTarget(section.name, option, value, isList) },
                        onAddOption = { target = OptionTarget(section.name, null, "", false) },
                        onRemove = { viewModel.stage(EditorEdit.DeleteSection(section.name)) },
                    )
                }
            }
        }
    }

    target?.let { t ->
        OptionDialog(t, onDismiss = { target = null }, onRemove = {
            t.option?.let { viewModel.stage(EditorEdit.DeleteOption(t.section, it)) }
            target = null
        }) { option, value, isList ->
            viewModel.stage(
                if (isList) EditorEdit.SetList(t.section, option, value.lines()) else EditorEdit.SetOption(t.section, option, value),
            )
            target = null
        }
    }
    if (addingSection) {
        SectionDialog(onDismiss = { addingSection = false }) { sectionName, type ->
            viewModel.stage(EditorEdit.AddSection(sectionName, type))
            addingSection = false
        }
    }
    if (showStaged) {
        AlertDialog(
            onDismissRequest = { showStaged = false },
            title = { Text("Staged changes") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ui.edits.forEachIndexed { index, edit ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(describe(edit), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = MonoFamily)
                            TextButton(onClick = { viewModel.unstage(index) }) { Text("Undo") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showStaged = false }) { Text("Close") } },
        )
    }
    ui.message?.let { MessageDialog(it, viewModel::clearMessage) }
    ApplySheet(applyViewModel)
}

@Composable
private fun SectionCard(
    section: SectionView,
    onEdit: (String, String, Boolean) -> Unit,
    onAddOption: () -> Unit,
    onRemove: () -> Unit,
) {
    val removed = section.state == ItemState.REMOVED
    KitSectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(section.type, style = MaterialTheme.typography.titleMedium)
                    when (section.state) {
                        ItemState.ADDED -> TagChip("New", tone = ChipTone.ACCENT)
                        ItemState.REMOVED -> TagChip("Removed", tone = ChipTone.ERROR)
                        else -> Unit
                    }
                }
                Text(
                    section.name + if (section.anonymous) " (unnamed)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!removed) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Remove section", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        section.options.forEachIndexed { index, option ->
            if (index > 0) RowDivider()
            OptionRow(option, enabled = !removed) { onEdit(option.option, option.editValue, option.isList) }
        }
        if (!removed) {
            TextButton(onClick = onAddOption) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add option")
            }
        }
    }
}

@Composable
private fun OptionRow(option: OptionView, enabled: Boolean, onClick: () -> Unit) {
    fun shown(value: String?) = if (isSecretOption(option.option)) "(hidden)" else value
    val text = when (option.state) {
        ItemState.SAME -> shown(option.new)
        ItemState.CHANGED -> if (isSecretOption(option.option)) "(hidden, changed)" else "${option.old} → ${option.new}"
        ItemState.ADDED -> "${shown(option.new)} (new)"
        ItemState.REMOVED -> "${shown(option.old)} (removed)"
    }.orEmpty()
    val color = when (option.state) {
        ItemState.SAME -> MaterialTheme.colorScheme.onSurface
        ItemState.REMOVED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(option.option, Modifier.weight(0.4f), fontFamily = MonoFamily, style = MaterialTheme.typography.bodyMedium)
        Text(text, Modifier.weight(0.6f), fontFamily = MonoFamily, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun OptionDialog(target: OptionTarget, onDismiss: () -> Unit, onRemove: () -> Unit, onSave: (String, String, Boolean) -> Unit) {
    var option by remember { mutableStateOf(target.option.orEmpty()) }
    var value by remember { mutableStateOf(target.value) }
    var isList by remember { mutableStateOf(target.isList) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target.option == null) "Add option to ${target.section}" else "${target.section}.${target.option}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (target.option == null) {
                    OutlinedTextField(option, { option = it.trim() }, label = { Text("Option name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SwitchListRow(
                        title = "List (one value per line)",
                        checked = isList,
                        onCheckedChange = { isList = it },
                    )
                }
                if (!isList && (value == "0" || value == "1")) {
                    SwitchListRow(
                        title = "Enabled",
                        checked = value == "1",
                        onCheckedChange = { value = if (it) "1" else "0" },
                    )
                }
                OutlinedTextField(
                    value,
                    { value = it },
                    label = { Text(if (isList) "Values, one per line" else "Value") },
                    singleLine = !isList,
                    minLines = if (isList) 3 else 1,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFamily),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = option.isNotEmpty() && (isList || value.isNotEmpty()), onClick = { onSave(option, value, isList) }) { Text("Stage") } },
        dismissButton = {
            Row {
                if (target.option != null) TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun SectionDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add section") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(type, { type = it.trim() }, label = { Text("Type, for example rule") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it.trim() }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = name.isNotEmpty() && type.isNotEmpty(), onClick = { onSave(name, type) }) { Text("Stage") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MessageDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }, text = { Text(message) })
}

private fun describe(edit: EditorEdit): String = when (edit) {
    is EditorEdit.SetOption -> "set ${edit.section}.${edit.option}"
    is EditorEdit.SetList -> "set list ${edit.section}.${edit.option}"
    is EditorEdit.DeleteOption -> "remove ${edit.section}.${edit.option}"
    is EditorEdit.AddSection -> "add ${edit.type} ${edit.name}"
    is EditorEdit.DeleteSection -> "remove section ${edit.section}"
}
