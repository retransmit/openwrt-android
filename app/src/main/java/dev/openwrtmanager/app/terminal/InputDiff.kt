package dev.openwrtmanager.app.terminal

/** What changed in the keyboard's text: delete [backspaces] characters from the end, then type [typed]. */
data class InputDiff(val backspaces: Int, val typed: String)

/**
 * The keyboard reports its whole text on every change, and a keyboard that is composing a word
 * resends the word as it grows, so only the part after what both texts share is new.
 */
fun diffInput(old: String, new: String): InputDiff {
    var shared = 0
    val limit = minOf(old.length, new.length)
    while (shared < limit && old[shared] == new[shared]) shared++
    return InputDiff(old.length - shared, new.substring(shared))
}
