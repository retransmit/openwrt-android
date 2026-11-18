package dev.openwrtmanager.app.ui.apply

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.sp
import dev.openwrtmanager.app.ui.kit.Readout
import dev.openwrtmanager.app.ui.theme.LocalExtraColors
import dev.openwrtmanager.app.ui.theme.MonoFamily
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openwrtmanager.app.apply.ApplyOutcome
import dev.openwrtmanager.app.apply.ApplyPhase
import dev.openwrtmanager.app.apply.SafeApply

/**
 * Review, apply and result for one change. Shown whenever [viewModel] is not idle.
 * It cannot be dismissed while a change is being applied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApplySheet(viewModel: ApplyViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    if (ui == ApplyUi.Idle) return
    val working by rememberUpdatedState(ui is ApplyUi.Working)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !working },
    )
    ModalBottomSheet(onDismissRequest = viewModel::dismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val state = ui) {
                is ApplyUi.Review -> Review(state, onApply = viewModel::confirm, onCancel = viewModel::dismiss)
                is ApplyUi.Working -> Working(state)
                is ApplyUi.Done -> Done(state.outcome, state.problems, onClose = viewModel::dismiss)
                ApplyUi.Idle -> Unit
            }
        }
    }
}

@Composable
private fun Review(state: ApplyUi.Review, onApply: () -> Unit, onCancel: () -> Unit) {
    var showRaw by rememberSaveable { mutableStateOf(false) }
    Text(state.change.title, style = MaterialTheme.typography.titleLarge)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.change.summary.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = MaterialTheme.typography.bodyLarge)
                Text(line, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    if (state.change.mayDropConnection) {
        val warning = LocalExtraColors.current.warning
        Surface(
            color = warning.copy(alpha = 0.12f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, warning.copy(alpha = 0.5f)),
        ) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Info, contentDescription = null, tint = warning)
                Text(
                    "Your phone may lose connection for a few seconds. If the app cannot reach the router again, " +
                        "the router undoes this change by itself after ${SafeApply.DEFAULT_ROLLBACK_SECONDS} seconds.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { showRaw = !showRaw }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (showRaw) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            Text("Show exact changes", style = MaterialTheme.typography.labelLarge)
        }
        if (showRaw) {
            when {
                state.loadingChanges -> LinearProgressIndicator(Modifier.fillMaxWidth())
                state.rawChanges == null -> Text(
                    "The exact changes could not be read from the router. The summary above is still what will be applied.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        maskSecrets(state.rawChanges),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontFamily = MonoFamily,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
        // Wait for the preview to finish: it stages and un-stages on the router and must not overlap the apply.
        Button(onClick = onApply, enabled = !state.loadingChanges) { Text("Apply") }
    }
}

@Composable
private fun Working(state: ApplyUi.Working) {
    Text("Applying", style = MaterialTheme.typography.titleLarge)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.secondsLeft > 0) {
            Readout("${state.secondsLeft}", unit = "s", color = MaterialTheme.colorScheme.primary, size = 32.sp)
        } else {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        Column {
            Text(
                when (state.phase) {
                    ApplyPhase.Staging -> "Preparing the change"
                    ApplyPhase.Committing -> "Saving it on the router"
                    ApplyPhase.Reconnecting -> "Reconnecting to the router"
                    ApplyPhase.Confirming -> "Confirming"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                if (state.secondsLeft > 0) {
                    "The router undoes the change in ${state.secondsLeft} s unless the app confirms it"
                } else {
                    "Checking what the router did"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        "Keep the app open until this finishes.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Done(outcome: ApplyOutcome, problems: List<dev.openwrtmanager.app.apply.HealthProblem>, onClose: () -> Unit) {
    val message = outcomeMessage(outcome)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (outcome == ApplyOutcome.Applied) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (outcome == ApplyOutcome.Applied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text(message.title, style = MaterialTheme.typography.titleLarge)
    }
    Text(message.detail, style = MaterialTheme.typography.bodyLarge)
    if (problems.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Something is not right yet", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                problems.forEach {
                    Text(
                        dev.openwrtmanager.app.apply.problemMessage(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
    message.code?.let { code ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
            Text(
                maskSecrets(code),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onClose) { Text("Close") }
    }
}
