package dev.openwrtmanager.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import dev.openwrtmanager.app.ui.theme.LocalExtraColors
import dev.openwrtmanager.app.ui.theme.MonoFamily
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
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

private val TERMINAL_BACKGROUND = Color(0xFF000000)
private val CELL_STYLE = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit, viewModel: TerminalViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terminal") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        // The scaffold's padding already covers the navigation bar, so the keyboard's padding must not add it again.
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            val warning = LocalExtraColors.current.warning
            Surface(color = warning.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Commands here run directly on the device. The undo timer does not cover them.",
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = warning,
                )
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
        val keyRowHeight = 44.dp
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
private fun Session(session: TerminalSessionModel, cellWidth: Int, cellHeight: Int, keyRowHeight: androidx.compose.ui.unit.Dp, onReconnect: () -> Unit) {
    val screen by session.screen.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    var scrolledBack by remember { mutableIntStateOf(0) }
    var ctrl by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    // Following live output brings the view back to the bottom.
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
                // Returns whether a control byte went out, so the keyboard's field is cleared.
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

/** The first character with Ctrl held (as a control byte when it has one), the rest as typed. */
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

/**
 * The soft keyboard types into a hidden field that starts with one space, so deleting with nothing
 * typed still shows up as a change. Each change is compared with the last one and only the
 * difference is sent, since a keyboard composing a word resends the whole word as it grows. The
 * field goes back to the single space only after a line ends and nothing is being composed.
 */
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
private fun KeyRow(height: androidx.compose.ui.unit.Dp, ctrl: Boolean, onCtrl: () -> Unit, onKey: (TerminalKey?, String?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun key(label: String, held: Boolean = false, onClick: () -> Unit) = OutlinedButton(
            onClick = onClick,
            shape = MaterialTheme.shapes.small,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            colors = if (held) {
                androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    containerColor = LocalExtraColors.current.accentTint,
                    contentColor = MaterialTheme.colorScheme.primary,
                )
            } else {
                androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
            },
        ) { Text(label, fontFamily = MonoFamily) }
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
            Text(message, color = Color.White)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

