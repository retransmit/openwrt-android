package dev.openwrtmanager.app.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Termux emulator works on its own, without TerminalSession or a local process. */
class EmulatorSmokeTest {
    private val written = StringBuilder()

    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            written.append(String(data, offset, count, Charsets.UTF_8))
        }
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    @Test
    fun emulator_decodes_colour_and_cursor() {
        val emulator = TerminalEmulator(output, 80, 24, 10, 20, 2000, QuietClient)
        val bytes = "\u001b[31mred\u001b[0m ok".toByteArray()
        emulator.append(bytes, bytes.size)
        val screen = emulator.screen
        val row = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(0))
        assertEquals("red ok", String(row.mText, 0, 6))
        assertEquals(1, TextStyle.decodeForeColor(row.getStyle(0)))
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.decodeForeColor(row.getStyle(4)))
        assertEquals(0, emulator.cursorRow)
        assertEquals(6, emulator.cursorCol)
    }

    @Test
    fun emulator_answers_a_cursor_position_query() {
        val emulator = TerminalEmulator(output, 80, 24, 10, 20, 2000, QuietClient)
        val bytes = "ab\u001b[6n".toByteArray()
        emulator.append(bytes, bytes.size)
        assertEquals("\u001b[1;3R", written.toString())
    }
}
