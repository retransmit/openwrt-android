package dev.openwrtmanager.app.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TextStyle
import dev.openwrtmanager.app.ssh.SshShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StyledRun(
    val text: String,
    /** ARGB. */
    val fg: Int,
    val bg: Int,
    val bold: Boolean,
    val underline: Boolean,
)

data class StyledRow(val runs: List<StyledRun>)

data class ScreenSnapshot(val rows: List<StyledRow>, val cursorRow: Int, val cursorCol: Int, val cursorVisible: Boolean)

sealed interface SessionState {
    data object Open : SessionState
    data class Closed(val reason: String) : SessionState
}

/**
 * Joins an SSH shell to Termux's emulator: bytes from the device go into the emulator, the emulator's
 * screen is published as snapshots, and keys and the emulator's own replies go back to the device.
 * The emulator is not thread safe, so every use of it holds [lock].
 */
class TerminalSessionModel(
    private val shell: SshShell,
    cols: Int,
    rows: Int,
    scope: CoroutineScope,
    private val scrollback: Int = 2000,
) {
    private val lock = Any()
    private val outLock = Any()

    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = send(data.copyOfRange(offset, offset + count))
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private val emulator = TerminalEmulator(output, cols, rows, 1, 1, scrollback, QuietClient)

    private val _screen = MutableStateFlow(snapshot())
    val screen: StateFlow<ScreenSnapshot> = _screen.asStateFlow()

    private val _state = MutableStateFlow<SessionState>(SessionState.Open)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * Everything that touches the network goes through here, drained by one background writer:
     * Android forbids network use on the main thread, and one writer keeps the bytes in order.
     */
    private val outbox = Channel<() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            for (job in outbox) {
                try {
                    job()
                } catch (e: Exception) {
                    _state.value = SessionState.Closed("The connection was lost.")
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            val reason = try {
                while (true) {
                    val n = shell.input.read(buffer)
                    if (n < 0) break
                    synchronized(lock) { emulator.append(buffer, n) }
                    _screen.value = snapshot()
                }
                "The device closed the session."
            } catch (e: Exception) {
                "The connection was lost."
            }
            _state.value = SessionState.Closed(reason)
        }
    }

    /** Raw bytes to the device, written in order off the calling thread; dropped once the session is closed. */
    fun send(bytes: ByteArray) {
        if (_state.value != SessionState.Open) return
        outbox.trySend {
            if (_state.value == SessionState.Open) {
                synchronized(outLock) {
                    shell.output.write(bytes)
                    shell.output.flush()
                }
            }
        }
    }

    /** Text from the keyboard. Enter becomes a carriage return, as a real terminal sends it. */
    fun type(text: String) = send(text.replace("\r\n", "\r").replace('\n', '\r').toByteArray())

    fun key(key: TerminalKey, ctrl: Boolean) {
        val applicationCursor = synchronized(lock) { emulator.isCursorKeysApplicationMode }
        send(keyBytes(key, ctrl, applicationCursor))
    }

    fun resize(cols: Int, rows: Int) {
        synchronized(lock) { emulator.resize(cols, rows, 1, 1) }
        outbox.trySend { runCatching { shell.resize(cols, rows) } }
        _screen.value = snapshot()
    }

    fun close() {
        _state.value = SessionState.Closed("Closed.")
        outbox.close()
        // Closing the channel can block on the network too.
        Thread { runCatching { shell.close() } }.start()
    }

    /** The visible screen, or [scrolledBack] lines further up into the scrollback. */
    fun snapshot(scrolledBack: Int = 0): ScreenSnapshot = synchronized(lock) {
        val screen = emulator.screen
        val cols = emulator.mColumns
        val rowCount = emulator.mRows
        val back = scrolledBack.coerceIn(0, screen.activeTranscriptRows)
        val colors = emulator.mColors.mCurrentColors
        val rows = (0 until rowCount).map { visible ->
            val row = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(visible - back))
            val runs = mutableListOf<StyledRun>()
            val text = StringBuilder()
            var current: StyledRun? = null
            var lastIndex = -1
            for (col in 0 until cols) {
                val index = row.findStartOfColumn(col)
                val style = row.getStyle(col)
                val run = styleOf(style, colors)
                if (current != null && (run.fg != current.fg || run.bg != current.bg || run.bold != current.bold || run.underline != current.underline)) {
                    runs += current.copy(text = text.toString())
                    text.clear()
                }
                current = run
                // A wide character covers two columns but is one entry in the row.
                if (index != lastIndex && index < row.mText.size) {
                    val c = row.mText[index]
                    text.append(c)
                    if (Character.isHighSurrogate(c) && index + 1 < row.mText.size) text.append(row.mText[index + 1])
                }
                lastIndex = index
            }
            current?.let { runs += it.copy(text = text.toString()) }
            StyledRow(runs)
        }
        ScreenSnapshot(rows, emulator.cursorRow + back, emulator.cursorCol, emulator.shouldCursorBeVisible() && back == 0)
    }

    private fun styleOf(style: Long, colors: IntArray): StyledRun {
        val effect = TextStyle.decodeEffect(style)
        val bold = effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0
        var fgIndex = TextStyle.decodeForeColor(style)
        // Bold on one of the eight basic colours shows its bright version, as terminals do.
        if (bold && fgIndex in 0..7) fgIndex += 8
        var fg = resolve(fgIndex, colors)
        var bg = resolve(TextStyle.decodeBackColor(style), colors)
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0) {
            val t = fg
            fg = bg
            bg = t
        }
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE != 0) fg = bg
        return StyledRun("", fg, bg, bold, effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0)
    }

    /** An index into the palette, or a 24-bit colour already carrying its alpha. */
    private fun resolve(color: Int, colors: IntArray): Int =
        if (color and 0xff000000.toInt() == 0xff000000.toInt()) color else colors.getOrElse(color) { colors[TextStyle.COLOR_INDEX_FOREGROUND] }
}
