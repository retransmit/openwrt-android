package dev.openwrtmanager.app.terminal

import com.termux.terminal.KeyHandler

/** Keys the soft keyboard lacks, shown in the terminal's key row. */
enum class TerminalKey { ESC, TAB, UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, ENTER, BACKSPACE }

// Android KeyEvent codes, as KeyHandler expects them.
private const val KEYCODE_DPAD_UP = 19
private const val KEYCODE_DPAD_DOWN = 20
private const val KEYCODE_DPAD_LEFT = 21
private const val KEYCODE_DPAD_RIGHT = 22
private const val KEYCODE_PAGE_UP = 92
private const val KEYCODE_PAGE_DOWN = 93
private const val KEYCODE_MOVE_HOME = 122
private const val KEYCODE_MOVE_END = 123

/**
 * The bytes a key sends. Cursor keys follow the shell's mode: programs such as vi and top switch the
 * terminal to application cursor keys and expect ESC O A instead of ESC [ A.
 */
fun keyBytes(key: TerminalKey, ctrl: Boolean, applicationCursor: Boolean): ByteArray {
    val simple = when (key) {
        TerminalKey.ESC -> byteArrayOf(0x1b)
        TerminalKey.TAB -> "\t".toByteArray()
        TerminalKey.ENTER -> "\r".toByteArray()
        TerminalKey.BACKSPACE -> byteArrayOf(0x7f)
        else -> null
    }
    if (simple != null) return simple
    val code = when (key) {
        TerminalKey.UP -> KEYCODE_DPAD_UP
        TerminalKey.DOWN -> KEYCODE_DPAD_DOWN
        TerminalKey.LEFT -> KEYCODE_DPAD_LEFT
        TerminalKey.RIGHT -> KEYCODE_DPAD_RIGHT
        TerminalKey.HOME -> KEYCODE_MOVE_HOME
        TerminalKey.END -> KEYCODE_MOVE_END
        TerminalKey.PAGE_UP -> KEYCODE_PAGE_UP
        TerminalKey.PAGE_DOWN -> KEYCODE_PAGE_DOWN
        else -> error("handled above")
    }
    val mode = if (ctrl) KeyHandler.KEYMOD_CTRL else 0
    return KeyHandler.getCode(code, mode, applicationCursor, false)?.toByteArray() ?: ByteArray(0)
}

/** Ctrl plus a character: a to z (either case) and @ [ \ ] ^ _ space; null for anything else. */
fun ctrlByte(c: Char): Byte? = when (c) {
    in 'a'..'z' -> (c - 'a' + 1).toByte()
    in 'A'..'Z' -> (c - 'A' + 1).toByte()
    ' ', '@' -> 0
    '[' -> 27
    '\\' -> 28
    ']' -> 29
    '^' -> 30
    '_' -> 31
    '?' -> 127
    else -> null
}
