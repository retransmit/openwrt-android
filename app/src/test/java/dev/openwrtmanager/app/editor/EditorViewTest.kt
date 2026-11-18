package dev.openwrtmanager.app.editor

import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorViewTest {
    private val config = UciConfig(
        "system",
        listOf(
            UciSection("cfg01", "system", true, 0, mapOf("hostname" to "OpenWrt", "log_size" to "128"), emptyMap()),
            UciSection("ntp", "timeserver", false, 1, emptyMap(), mapOf("server" to listOf("a.pool", "b.pool"))),
        ),
    )

    @Test
    fun nothing_staged_shows_the_device() {
        val view = stagedView(config, emptyList())
        assertEquals(listOf("cfg01", "ntp"), view.map { it.name })
        assertTrue(view.all { it.state == ItemState.SAME })
        assertEquals(OptionView("hostname", "OpenWrt", "OpenWrt", "OpenWrt", false, ItemState.SAME), view[0].options[0])
        assertEquals(OptionView("server", "a.pool, b.pool", "a.pool, b.pool", "a.pool\nb.pool", true, ItemState.SAME), view[1].options[0])
    }

    @Test
    fun a_changed_option_shows_old_and_new() {
        val view = stagedView(config, listOf(EditorEdit.SetOption("cfg01", "hostname", "home")))
        assertEquals(OptionView("hostname", "OpenWrt", "home", "home", false, ItemState.CHANGED), view[0].options[0])
        assertEquals(ItemState.SAME, view[0].state)
    }

    @Test
    fun added_and_removed_options() {
        val view = stagedView(
            config,
            listOf(
                EditorEdit.SetOption("cfg01", "ttylogin", "1"),
                EditorEdit.DeleteOption("cfg01", "log_size"),
                EditorEdit.SetList("ntp", "server", listOf("c.pool")),
            ),
        )
        val options = view[0].options.associateBy { it.option }
        assertEquals(ItemState.REMOVED, options.getValue("log_size").state)
        assertEquals(OptionView("ttylogin", null, "1", "1", false, ItemState.ADDED), options.getValue("ttylogin"))
        assertEquals(OptionView("server", "a.pool, b.pool", "c.pool", "c.pool", true, ItemState.CHANGED), view[1].options[0])
    }

    @Test
    fun an_option_added_then_removed_disappears() {
        val view = stagedView(config, listOf(EditorEdit.SetOption("cfg01", "x", "1"), EditorEdit.DeleteOption("cfg01", "x")))
        assertEquals(listOf("hostname", "log_size"), view[0].options.map { it.option })
    }

    @Test
    fun a_new_section_gets_a_card_that_takes_options() {
        val view = stagedView(
            config,
            listOf(EditorEdit.AddSection("pool2", "timeserver"), EditorEdit.SetOption("pool2", "enabled", "1")),
        )
        val added = view.last()
        assertEquals("pool2", added.name)
        assertEquals("timeserver", added.type)
        assertEquals(ItemState.ADDED, added.state)
        assertEquals(listOf(OptionView("enabled", null, "1", "1", false, ItemState.ADDED)), added.options)
    }

    @Test
    fun removed_sections_are_marked_and_new_ones_dropped() {
        val view = stagedView(
            config,
            listOf(EditorEdit.DeleteSection("ntp"), EditorEdit.AddSection("tmp", "x"), EditorEdit.DeleteSection("tmp")),
        )
        assertEquals(listOf("cfg01", "ntp"), view.map { it.name })
        assertEquals(ItemState.REMOVED, view[1].state)
    }
}
