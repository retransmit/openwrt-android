package dev.openwrtmanager.app.terminal

import dev.openwrtmanager.app.ssh.SshShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** A shell whose output the test writes and whose input the test reads. */
class FakeShell : SshShell {
    val toApp = PipedOutputStream()
    override val input = PipedInputStream(toApp, 64 * 1024)
    val sent = ByteArrayOutputStream()
    override val output = sent
    var open = true
    override val isOpen: Boolean get() = open
    val sizes = mutableListOf<Pair<Int, Int>>()
    override fun resize(cols: Int, rows: Int) {
        sizes += cols to rows
    }
    override fun close() {
        open = false
        runCatching { toApp.close() }
    }

    fun feed(bytes: ByteArray) {
        toApp.write(bytes)
        toApp.flush()
    }
}

class TerminalSessionModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private suspend fun waitFor(what: String, check: () -> Boolean) {
        withTimeout(3_000) { while (!check()) delay(10) }
        assertTrue(what, check())
    }

    private fun text(snapshot: ScreenSnapshot, row: Int) = snapshot.rows[row].runs.joinToString("") { it.text }.trimEnd()

    @Test
    fun split_input_renders_the_same() = runBlocking<Unit> {
        val whole = FakeShell()
        val split = FakeShell()
        val a = TerminalSessionModel(whole, 40, 5, scope)
        val b = TerminalSessionModel(split, 40, 5, scope)
        val bytes = "\u001b[31mré\u001b[0m ok".toByteArray()
        whole.feed(bytes)
        // Split inside the escape sequence and inside the two-byte é.
        split.feed(bytes.copyOfRange(0, 3))
        delay(50)
        split.feed(bytes.copyOfRange(3, 7))
        delay(50)
        split.feed(bytes.copyOfRange(7, bytes.size))
        waitFor("both drawn") { text(a.screen.value, 0) == "ré ok" && text(b.screen.value, 0) == "ré ok" }
        assertEquals(a.screen.value, b.screen.value)
    }

    @Test
    fun snapshot_merges_runs_of_the_same_style() = runBlocking<Unit> {
        val shell = FakeShell()
        val model = TerminalSessionModel(shell, 20, 3, scope)
        shell.feed("\u001b[1mbold\u001b[0m plain".toByteArray())
        waitFor("drawn") { text(model.screen.value, 0) == "bold plain" }
        val runs = model.screen.value.rows[0].runs
        assertEquals("bold", runs[0].text)
        assertTrue(runs[0].bold)
        assertEquals(" plain", runs[1].text.trimEnd())
        assertEquals(false, runs[1].bold)
        assertEquals(0, model.screen.value.cursorRow)
        assertEquals(10, model.screen.value.cursorCol)
    }

    @Test
    fun typing_sends_carriage_returns_and_emulator_replies_go_out() = runBlocking<Unit> {
        val shell = FakeShell()
        val model = TerminalSessionModel(shell, 20, 3, scope)
        model.type("ls\n")
        waitFor("typed") { shell.sent.toString() == "ls\r" }
        shell.feed("\u001b[6n".toByteArray())
        waitFor("cursor report") { shell.sent.toString() == "ls\r\u001b[1;1R" }
    }

    @Test
    fun keys_follow_cursor_mode() {
        assertArrayEquals("\u001b[A".toByteArray(), keyBytes(TerminalKey.UP, ctrl = false, applicationCursor = false))
        assertArrayEquals("\u001bOA".toByteArray(), keyBytes(TerminalKey.UP, ctrl = false, applicationCursor = true))
        assertArrayEquals("\u001b[1;5D".toByteArray(), keyBytes(TerminalKey.LEFT, ctrl = true, applicationCursor = false))
        assertArrayEquals(byteArrayOf(0x1b), keyBytes(TerminalKey.ESC, ctrl = false, applicationCursor = false))
        assertArrayEquals(byteArrayOf(0x7f), keyBytes(TerminalKey.BACKSPACE, ctrl = false, applicationCursor = false))
        assertArrayEquals("\r".toByteArray(), keyBytes(TerminalKey.ENTER, ctrl = false, applicationCursor = false))
        assertArrayEquals("\t".toByteArray(), keyBytes(TerminalKey.TAB, ctrl = false, applicationCursor = false))
    }

    @Test
    fun the_shells_cursor_mode_is_used_for_keys() = runBlocking<Unit> {
        val shell = FakeShell()
        val model = TerminalSessionModel(shell, 20, 3, scope)
        shell.feed("\u001b[?1h".toByteArray())
        delay(100)
        model.key(TerminalKey.UP, ctrl = false)
        waitFor("application cursor key") { shell.sent.toString() == "\u001bOA" }
    }

    @Test
    fun ctrl_letters_are_control_bytes() {
        assertEquals(3.toByte(), ctrlByte('c'))
        assertEquals(3.toByte(), ctrlByte('C'))
        assertEquals(27.toByte(), ctrlByte('['))
        assertEquals(0.toByte(), ctrlByte(' '))
        assertEquals(null, ctrlByte('1'))
    }

    @Test
    fun close_ends_reading() = runBlocking<Unit> {
        val shell = FakeShell()
        val model = TerminalSessionModel(shell, 20, 3, scope)
        waitFor("open") { model.state.value == SessionState.Open }
        shell.close()
        waitFor("closed") { model.state.value is SessionState.Closed }
    }

    @Test
    fun resize_reaches_the_shell_and_the_screen() = runBlocking<Unit> {
        val shell = FakeShell()
        val model = TerminalSessionModel(shell, 20, 3, scope)
        model.resize(30, 5)
        assertEquals(listOf(30 to 5), shell.sizes)
        waitFor("bigger screen") { model.screen.value.rows.size == 5 }
    }
}
