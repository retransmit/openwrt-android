package dev.openwrtmanager.app.ui.apply

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openwrtmanager.app.apply.ApplyOutcome
import dev.openwrtmanager.app.apply.ApplyPhase
import dev.openwrtmanager.app.apply.HealthProblem
import dev.openwrtmanager.app.apply.SafeApply
import dev.openwrtmanager.app.apply.problemMessage
import dev.openwrtmanager.app.ui.kit.BannerCard
import dev.openwrtmanager.app.ui.kit.BannerTone
import dev.openwrtmanager.app.ui.kit.Readout
import dev.openwrtmanager.app.ui.kit.SectionCard
import dev.openwrtmanager.app.ui.theme.LocalExtraColors
import dev.openwrtmanager.app.ui.theme.MonoFamily

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
    Text(state.change.title, style = MaterialTheme.typography.headlineSmall)
    SectionCard {
        state.change.summary.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                Text(line, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    if (state.change.mayDropConnection) {
        BannerCard(
            text = "Your phone may lose connection for a few seconds. If the app cannot reach the router again, " +
                "the router restores the previous config after ${SafeApply.DEFAULT_ROLLBACK_SECONDS} seconds.",
            icon = Icons.Default.Info,
            tone = BannerTone.WARNING,
        )
    }
    SectionCard(contentPadding = false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showRaw = !showRaw }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(if (showRaw) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            Text("Show exact uci changes", style = MaterialTheme.typography.labelLarge)
        }
        if (showRaw) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                when {
                    state.loadingChanges -> LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                    state.rawChanges == null -> Text(
                        "The exact changes could not be read from the router. The summary above is still what will be applied.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            maskSecrets(state.rawChanges),
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            fontFamily = MonoFamily,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                        )
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
        // Wait for preview to finish: it stages and un-stages on the router and must not overlap apply.
        Button(onClick = onApply, enabled = !state.loadingChanges) { Text("Apply") }
    }
}

@Composable
private fun Working(state: ApplyUi.Working) {
    Text("Applying changes", style = MaterialTheme.typography.headlineSmall)
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                if (state.secondsLeft > 0) {
                    CircularProgressIndicator(
                        progress = { (state.secondsLeft / SafeApply.DEFAULT_ROLLBACK_SECONDS.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.size(56.dp),
                        strokeWidth = 4.dp,
                    )
                    Readout("${state.secondsLeft}", unit = "s", color = MaterialTheme.colorScheme.primary, size = 16.sp)
                } else {
                    CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 4.dp)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (state.phase) {
                        ApplyPhase.Staging -> "Preparing the change"
                        ApplyPhase.Committing -> "Saving on the router"
                        ApplyPhase.Reconnecting -> "Reconnecting to router"
                        ApplyPhase.Confirming -> "Confirming change"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (state.secondsLeft > 0) {
                        "Auto-rollback triggers in ${state.secondsLeft} s unless confirmed"
                    } else {
                        "Checking final status on the router"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Text(
        "Keep the app open until this finishes.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Done(outcome: ApplyOutcome, problems: List<HealthProblem>, onClose: () -> Unit) {
    val message = outcomeMessage(outcome)
    val ok = outcome == ApplyOutcome.Applied
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ok) LocalExtraColors.current.online else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(28.dp),
        )
        Text(message.title, style = MaterialTheme.typography.headlineSmall)
    }
    Text(message.detail, style = MaterialTheme.typography.bodyLarge)
    if (problems.isNotEmpty()) {
        BannerCard(
            title = "Something is not right yet",
            text = problems.joinToString("\n") { problemMessage(it) },
            icon = Icons.Default.Warning,
            tone = BannerTone.ERROR,
        )
    }
    message.code?.let { code ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Text(
                maskSecrets(code),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFamily,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onClose) { Text("Done") }
    }
}
