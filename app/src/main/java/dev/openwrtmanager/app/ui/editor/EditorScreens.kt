package dev.openwrtmanager.app.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openwrtmanager.app.editor.EditorEdit
import dev.openwrtmanager.app.editor.ItemState
import dev.openwrtmanager.app.editor.OptionView
import dev.openwrtmanager.app.editor.SectionView
import dev.openwrtmanager.app.editor.stagedView
import dev.openwrtmanager.app.ui.apply.ApplySheet
import dev.openwrtmanager.app.ui.apply.ApplyViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigListScreen(onOpen: (String) -> Unit, onBack: () -> Unit, viewModel: EditorViewModel = hiltViewModel()) {
    LaunchedEffect(viewModel) { viewModel.loadConfigs() }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Config editor") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (ui.offline) Text("Connect to a device on the Home tab first.", Modifier.padding(16.dp))
            Text(
                "Every setting on the device, file by file. Changes are reviewed and go through the undo timer.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn {
                items(ui.configs) { name ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(name) },
                        headlineContent = { Text(name, fontFamily = FontFamily.Monospace) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    ui.message?.let { MessageDialog(it, viewModel::clearMessage) }
}

/** What an option dialog is editing. */
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
                title = { Text(name, fontFamily = FontFamily.Monospace) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            if (ui.config != null) ExtendedFloatingActionButton(onClick = { addingSection = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add section") })
        },
        bottomBar = {
            if (ui.edits.isNotEmpty()) {
                BottomAppBar {
                    TextButton(onClick = { showStaged = true }) { Text("${ui.edits.size} staged") }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = viewModel::discard) { Text("Discard") }
                        Button(onClick = { scope.launch { viewModel.review()?.let { applyViewModel.review(it) { viewModel.applied() } } } }) { Text("Review") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val config = ui.config?.takeIf { it.name == name }
            val sections = remember(config, ui.edits) { config?.let { stagedView(it, ui.edits) }.orEmpty() }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sections, key = { it.name }) { section ->
                    SectionCard(
                        section = section,
                        onEdit = { option, value, isList -> target = OptionTarget(section.name, option, value, isList) },
                        onAddOption = { target = OptionTarget(section.name, null, "", false) },
                        onRemove = { viewModel.stage(EditorEdit.DeleteSection(section.name)) },
                    )
                }
                item { Text("", Modifier.padding(bottom = 80.dp)) }
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ui.edits.forEachIndexed { index, edit ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(describe(edit), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
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
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(section.type, style = MaterialTheme.typography.titleSmall)
                    Text(
                        section.name + if (section.anonymous) " (unnamed)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (section.state) {
                        ItemState.ADDED -> Text("New section", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        ItemState.REMOVED -> Text("Will be removed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        else -> Unit
                    }
                }
                if (!removed) TextButton(onClick = onRemove) { Text("Remove") }
            }
            section.options.forEach { option ->
                OptionRow(option, enabled = !removed) { onEdit(option.option, option.editValue, option.isList) }
            }
            if (!removed) TextButton(onClick = onAddOption) { Text("Add option") }
        }
    }
}

@Composable
private fun OptionRow(option: OptionView, enabled: Boolean, onClick: () -> Unit) {
    val text = when (option.state) {
        ItemState.SAME -> option.new
        ItemState.CHANGED -> "${option.old} → ${option.new}"
        ItemState.ADDED -> "${option.new} (new)"
        ItemState.REMOVED -> "${option.old} (removed)"
    }
    val color = when (option.state) {
        ItemState.SAME -> MaterialTheme.colorScheme.onSurface
        ItemState.REMOVED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp)) {
        Text(option.option, Modifier.weight(0.4f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        Text(text, Modifier.weight(0.6f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = color)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("A list (one value per line)", Modifier.weight(1f))
                        Switch(checked = isList, onCheckedChange = { isList = it })
                    }
                }
                if (!isList && (value == "0" || value == "1")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("On", Modifier.weight(1f))
                        Switch(checked = value == "1", onCheckedChange = { value = if (it) "1" else "0" })
                    }
                }
                OutlinedTextField(
                    value,
                    { value = it },
                    label = { Text(if (isList) "Values, one per line" else "Value") },
                    singleLine = !isList,
                    minLines = if (isList) 3 else 1,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = option.isNotEmpty(), onClick = { onSave(option, value, isList) }) { Text("Stage") } },
        dismissButton = {
            Row {
                if (target.option != null) TextButton(onClick = onRemove) { Text("Remove") }
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
