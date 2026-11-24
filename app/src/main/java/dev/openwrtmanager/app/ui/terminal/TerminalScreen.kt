package dev.openwrtmanager.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openwrtmanager.app.terminal.ScreenSnapshot
import dev.openwrtmanager.app.terminal.SessionState
import dev.openwrtmanager.app.terminal.StyledRow
import dev.openwrtmanager.app.terminal.TerminalKey
import dev.openwrtmanager.app.terminal.TerminalSessionModel
import dev.openwrtmanager.app.terminal.ctrlByte
import dev.openwrtmanager.app.terminal.diffInput
import dev.openwrtmanager.app.terminal.shouldClearField
import dev.openwrtmanager.app.ui.theme.LocalExtraColors
import dev.openwrtmanager.app.ui.theme.MonoFamily

private val TERMINAL_BACKGROUND = Color(0xFF0B0E11)
private val CELL_STYLE = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit, viewModel: TerminalViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showNotice by rememberSaveable { mutableStateOf(true) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terminal") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (showNotice) {
                val warning = LocalExtraColors.current.warning
                Surface(color = warning.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = warning, modifier = Modifier.size(16.dp))
                        Text(
                            "Commands run directly on the router without the 90s undo timer.",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = { showNotice = false }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss notice", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(TERMINAL_BACKGROUND)) {
                TerminalArea(viewModel, ui)
            }
        }
    }
}

@Composable
private fun TerminalArea(viewModel: TerminalViewModel, ui: TerminalUi) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val cell = remember(measurer) { measurer.measure("M", CELL_STYLE).size }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val keyRowHeight = 48.dp
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { (maxHeight - keyRowHeight).toPx() }
        val cols = (widthPx / cell.width).toInt().coerceAtLeast(20)
        val rows = (heightPx / cell.height).toInt().coerceAtLeast(5)
        LaunchedEffect(cols, rows) { viewModel.start(cols, rows) }
        when (ui) {
            TerminalUi.Idle, TerminalUi.Connecting -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is TerminalUi.Failed -> Notice(ui.message, "Try again", viewModel::reconnect)
            is TerminalUi.Open -> Session(ui.session, cell.width, cell.height, keyRowHeight, onReconnect = viewModel::reconnect)
        }
    }
}

@Composable
private fun Session(session: TerminalSessionModel, cellWidth: Int, cellHeight: Int, keyRowHeight: Dp, onReconnect: () -> Unit) {
    val screen by session.screen.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    var scrolledBack by remember { mutableIntStateOf(0) }
    var ctrl by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val shown: ScreenSnapshot = remember(screen, scrolledBack) { if (scrolledBack == 0) screen else session.snapshot(scrolledBack) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(cellHeight) {
                    var drag = 0f
                    detectVerticalDragGestures(onDragEnd = { drag = 0f }) { _, dy ->
                        drag += dy
                        val lines = (drag / cellHeight).toInt()
                        if (lines != 0) {
                            scrolledBack = (scrolledBack + lines).coerceIn(0, 2000)
                            drag -= lines * cellHeight
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures {
                        focus.requestFocus()
                        keyboard?.show()
                    }
                },
        ) {
            Column {
                shown.rows.forEach { row -> RowText(row, cellHeight) }
            }
            if (shown.cursorVisible) {
                Box(
                    Modifier
                        .offset { IntOffset(shown.cursorCol * cellWidth, shown.cursorRow * cellHeight) }
                        .size(with(density) { cellWidth.toDp() }, with(density) { cellHeight.toDp() })
                        .background(Color(0x99FFFFFF)),
                )
            }
            KeyboardInput(focus) { typed, backspaces ->
                scrolledBack = 0
                repeat(backspaces) { session.key(TerminalKey.BACKSPACE, ctrl = false) }
                if (typed.isNotEmpty() && ctrl) {
                    sendWithCtrl(session, typed)
                    ctrl = false
                    true
                } else {
                    if (typed.isNotEmpty()) session.type(typed)
                    false
                }
            }
            if (state is SessionState.Closed) {
                Notice((state as SessionState.Closed).reason, "Reconnect", onReconnect)
            }
        }
        KeyRow(keyRowHeight, ctrl, onCtrl = { ctrl = !ctrl }) { key, text ->
            scrolledBack = 0
            when {
                key != null -> {
                    session.key(key, ctrl)
                    ctrl = false
                }
                text != null -> {
                    if (ctrl) sendWithCtrl(session, text) else session.type(text)
                    ctrl = false
                }
            }
        }
    }
}

private fun sendWithCtrl(session: TerminalSessionModel, text: String) {
    ctrlByte(text.first())?.let { session.send(byteArrayOf(it)) } ?: session.type(text.take(1))
    if (text.length > 1) session.type(text.drop(1))
}

@Composable
private fun RowText(row: StyledRow, cellHeight: Int) {
    val density = LocalDensity.current
    val text: AnnotatedString = buildAnnotatedString {
        row.runs.forEach { run ->
            pushStyle(
                SpanStyle(
                    color = Color(run.fg),
                    background = if (run.bg == 0xFF000000.toInt()) Color.Unspecified else Color(run.bg),
                    fontWeight = if (run.bold) FontWeight.Bold else null,
                    textDecoration = if (run.underline) TextDecoration.Underline else null,
                ),
            )
            append(run.text)
            pop()
        }
    }
    Text(text, style = CELL_STYLE, maxLines = 1, softWrap = false, modifier = Modifier.height(with(density) { cellHeight.toDp() }))
}

@Composable
private fun KeyboardInput(focus: FocusRequester, onInput: (typed: String, backspaces: Int) -> Boolean) {
    val sentinel = remember { TextFieldValue(" ", TextRange(1)) }
    var value by remember { mutableStateOf(sentinel) }
    BasicTextField(
        value = value,
        onValueChange = { next ->
            val diff = diffInput(value.text, next.text)
            val controlSent = (diff.backspaces > 0 || diff.typed.isNotEmpty()) && onInput(diff.typed, diff.backspaces)
            value = if (shouldClearField(next.text, next.composition != null, controlSent)) sentinel else next
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
        modifier = Modifier.size(1.dp).focusRequester(focus),
        textStyle = TextStyle(color = Color.Transparent),
    )
}

@Composable
private fun KeyRow(height: Dp, ctrl: Boolean, onCtrl: () -> Unit, onKey: (TerminalKey?, String?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(scheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun key(label: String, held: Boolean = false, onClick: () -> Unit) = FilledTonalButton(
            onClick = onClick,
            shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(34.dp),
            colors = if (held) {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer,
                )
            } else {
                ButtonDefaults.filledTonalButtonColors(
                    containerColor = scheme.surfaceContainerHigh,
                    contentColor = scheme.onSurface,
                )
            },
        ) { Text(label, fontFamily = MonoFamily, fontSize = 12.sp) }
        key("Esc") { onKey(TerminalKey.ESC, null) }
        key("Ctrl", held = ctrl, onClick = onCtrl)
        key("Tab") { onKey(TerminalKey.TAB, null) }
        key("←") { onKey(TerminalKey.LEFT, null) }
        key("↑") { onKey(TerminalKey.UP, null) }
        key("↓") { onKey(TerminalKey.DOWN, null) }
        key("→") { onKey(TerminalKey.RIGHT, null) }
        key("Home") { onKey(TerminalKey.HOME, null) }
        key("End") { onKey(TerminalKey.END, null) }
        key("PgUp") { onKey(TerminalKey.PAGE_UP, null) }
        key("PgDn") { onKey(TerminalKey.PAGE_DOWN, null) }
        listOf("|", "~", "/", "-").forEach { symbol -> key(symbol) { onKey(null, symbol) } }
    }
}

@Composable
private fun Notice(message: String, action: String, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onAction) { Text(action) }
        }
    }
}
