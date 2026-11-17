package dev.openwrtmanager.app.admin

import org.junit.Assert.assertEquals
import org.junit.Test

class LogsTest {
    @Test
    fun commands() {
        assertEquals("logread -l 300", logCommand(LogKind.SYSTEM))
        assertEquals("dmesg | tail -n 300", logCommand(LogKind.KERNEL))
    }

    @Test
    fun follow_appends_after_the_last_known_line() {
        val shown = listOf("a", "b", "c")
        assertEquals(listOf("a", "b", "c", "d", "e"), mergeFollow(shown, listOf("b", "c", "d", "e")))
        assertEquals("nothing new", shown, mergeFollow(shown, listOf("a", "b", "c")))
        // A line that repeats: take what follows its last occurrence.
        // A repeated line: the anchor is the occurrence whose earlier lines match what is shown.
        assertEquals(listOf("x", "c", "y", "c", "z"), mergeFollow(listOf("x", "c"), listOf("c", "y", "c", "z")))
    }

    @Test
    fun a_repeated_line_does_not_swallow_the_lines_between() {
        assertEquals(listOf("A", "B", "C", "B"), mergeFollow(listOf("A", "B"), listOf("B", "C", "B")))
        assertEquals(listOf("A", "B", "C", "B"), mergeFollow(listOf("A", "B"), listOf("A", "B", "C", "B")))
        assertEquals("already caught up", listOf("A", "B", "C", "B"), mergeFollow(listOf("A", "B", "C", "B"), listOf("B", "C", "B")))
    }

    @Test
    fun follow_replaces_when_the_anchor_is_gone() {
        assertEquals(listOf("p", "q"), mergeFollow(listOf("a", "b"), listOf("p", "q")))
        assertEquals(listOf("p"), mergeFollow(emptyList(), listOf("p")))
    }

    @Test
    fun follow_is_capped() {
        val shown = (1..999).map { "line $it" }
        val merged = mergeFollow(shown, listOf("line 999", "new 1", "new 2", "new 3"))
        assertEquals(1000, merged.size)
        assertEquals("new 3", merged.last())
        assertEquals("line 3", merged.first())
    }

    @Test
    fun filter_is_case_insensitive() {
        val lines = listOf("dnsmasq-dhcp: DHCPACK", "kernel: br-lan", "Dnsmasq started")
        assertEquals(listOf("dnsmasq-dhcp: DHCPACK", "Dnsmasq started"), filterLines(lines, "DNSMASQ"))
        assertEquals(lines, filterLines(lines, "  "))
    }
}
