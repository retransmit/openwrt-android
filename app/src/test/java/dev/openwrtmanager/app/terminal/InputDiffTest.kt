package dev.openwrtmanager.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class InputDiffTest {
    @Test
    fun typing_adds_only_the_new_characters() {
        // Fast typing: each change arrives before the field was reset, so the text keeps growing.
        assertEquals(InputDiff(0, "t"), diffInput(" ", " t"))
        assertEquals(InputDiff(0, "o"), diffInput(" t", " to"))
        assertEquals(InputDiff(0, "p"), diffInput(" to", " top"))
    }

    @Test
    fun deleting_sends_backspaces() {
        assertEquals(InputDiff(1, ""), diffInput(" top", " to"))
        assertEquals("the placeholder itself was deleted", InputDiff(1, ""), diffInput(" ", ""))
    }

    @Test
    fun a_rewritten_word_is_backspaced_and_retyped() {
        // A keyboard correcting "teh" to "the".
        assertEquals(InputDiff(2, "he"), diffInput(" teh", " the"))
    }

    @Test
    fun the_field_is_cleared_only_when_safe() {
        assertEquals("mid-word", false, shouldClearField(" ls", composing = true, controlSent = false))
        assertEquals("a finished line", true, shouldClearField(" ls\n", composing = false, controlSent = false))
        assertEquals("still composing the line", false, shouldClearField(" ls\n", composing = true, controlSent = false))
        // Ctrl+C went out as one control byte, so the "c" left in the field never reached the shell.
        assertEquals("after a control key", true, shouldClearField(" c", composing = true, controlSent = true))
        assertEquals("too long", true, shouldClearField(" " + "x".repeat(300), composing = false, controlSent = false))
    }

    @Test
    fun several_characters_at_once() {
        assertEquals(InputDiff(0, "ls -la"), diffInput(" ", " ls -la"))
    }
}
