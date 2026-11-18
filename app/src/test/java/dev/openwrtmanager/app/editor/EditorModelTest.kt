package dev.openwrtmanager.app.editor

import dev.openwrtmanager.app.clients.Planned
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorModelTest {
    private val system = UciConfig(
        "system",
        listOf(
            UciSection("cfg01e48a", "system", true, 0, mapOf("hostname" to "OpenWrt", "log_size" to "128"), emptyMap()),
            UciSection("ntp", "timeserver", false, 1, mapOf("enabled" to "1"), mapOf("server" to listOf("0.openwrt.pool.ntp.org", "1.openwrt.pool.ntp.org"))),
        ),
    )

    private fun lines(edits: List<EditorEdit>) = ((planEdits(system, edits) as Planned.Ready).change).batch.script().lines()

    @Test
    fun option_edits_become_uci_lines() {
        val planned = planEdits(
            system,
            listOf(
                EditorEdit.SetOption("cfg01e48a", "hostname", "home"),
                EditorEdit.SetOption("ntp", "enabled", "0"),
                EditorEdit.DeleteOption("cfg01e48a", "log_size"),
                EditorEdit.SetList("ntp", "server", listOf("time.cloudflare.com", "pool.ntp.org")),
            ),
        ) as Planned.Ready
        assertEquals(
            listOf(
                "set system.cfg01e48a.hostname='home'",
                "set system.ntp.enabled='0'",
                "delete system.cfg01e48a.log_size",
                "delete system.ntp.server",
                "add_list system.ntp.server='time.cloudflare.com'",
                "add_list system.ntp.server='pool.ntp.org'",
            ),
            planned.change.batch.script().lines(),
        )
        assertTrue("any config may cut access", planned.change.mayDropConnection)
        assertEquals("Edit system", planned.change.title)
        assertEquals("Set cfg01e48a.hostname to home", planned.change.summary.first())
    }

    @Test
    fun emptied_list_deletes_the_option() {
        assertEquals(listOf("delete system.ntp.server"), lines(listOf(EditorEdit.SetList("ntp", "server", listOf("", "  ")))))
        assertEquals(
            "a list that never existed and stays empty is nothing to do",
            Planned.Problem(EditorProblem.NOTHING_TO_DO),
            planEdits(system, listOf(EditorEdit.SetList("ntp", "pool", emptyList()))),
        )
    }

    @Test
    fun edits_to_a_gone_section_are_refused() {
        assertEquals(Planned.Problem(EditorProblem.SECTION_GONE), planEdits(system, listOf(EditorEdit.SetOption("cfg99", "x", "1"))))
        assertEquals(
            "removed earlier in the same change",
            Planned.Problem(EditorProblem.SECTION_GONE),
            planEdits(system, listOf(EditorEdit.DeleteSection("ntp"), EditorEdit.SetOption("ntp", "enabled", "1"))),
        )
    }

    @Test
    fun new_section_needs_a_free_name() {
        assertEquals(
            listOf("set system.mytimer=timeserver", "set system.mytimer.enabled='1'"),
            lines(listOf(EditorEdit.AddSection("mytimer", "timeserver"), EditorEdit.SetOption("mytimer", "enabled", "1"))),
        )
        assertEquals(Planned.Problem(EditorProblem.SECTION_EXISTS), planEdits(system, listOf(EditorEdit.AddSection("ntp", "timeserver"))))
    }

    @Test
    fun anonymous_sections_by_real_name() {
        assertEquals(listOf("delete system.cfg01e48a"), lines(listOf(EditorEdit.DeleteSection("cfg01e48a"))))
    }

    @Test
    fun deleting_a_missing_option_writes_nothing() {
        assertEquals(Planned.Problem(EditorProblem.NOTHING_TO_DO), planEdits(system, listOf(EditorEdit.DeleteOption("ntp", "nosuch"))))
    }

    @Test
    fun line_breaks_and_bad_names_are_refused() {
        assertEquals(Planned.Problem(EditorProblem.LINE_BREAK), planEdits(system, listOf(EditorEdit.SetOption("ntp", "enabled", "1\n0"))))
        assertEquals(Planned.Problem(EditorProblem.LINE_BREAK), planEdits(system, listOf(EditorEdit.SetList("ntp", "server", listOf("a\rb")))))
        assertEquals(Planned.Problem(EditorProblem.BAD_NAME), planEdits(system, listOf(EditorEdit.SetOption("ntp", "bad name", "1"))))
        assertEquals(Planned.Problem(EditorProblem.BAD_NAME), planEdits(system, listOf(EditorEdit.AddSection("x", "time-server!"))))
    }

    @Test
    fun secrets_are_not_shown_in_the_summary() {
        val planned = planEdits(
            UciConfig("wireless", listOf(UciSection("wl0", "wifi-iface", false, 0, mapOf("key" to "old"), emptyMap()))),
            listOf(EditorEdit.SetOption("wl0", "key", "new secret")),
        ) as Planned.Ready
        assertEquals(listOf("Set wl0.key to a new value"), planned.change.summary)
    }

    @Test
    fun configs_are_listed() {
        assertEquals(listOf("dhcp", "network", "system"), parseConfigs("""{"configs":["system","dhcp","network"]}"""))
        assertEquals(emptyList<String>(), parseConfigs("nope"))
    }

    @Test
    fun configs_the_apply_step_cannot_take_are_left_out() {
        assertEquals(listOf("network"), parseConfigs("""{"configs":["network","MyConfig"]}"""))
    }

    @Test
    fun an_empty_value_is_refused() {
        // uci treats setting an empty value as a delete, which the review would not say.
        assertEquals(Planned.Problem(EditorProblem.EMPTY_VALUE), planEdits(system, listOf(EditorEdit.SetOption("cfg01e48a", "hostname", ""))))
        assertEquals(Planned.Problem(EditorProblem.EMPTY_VALUE), planEdits(system, listOf(EditorEdit.SetOption("ntp", "new_one", ""))))
    }

    @Test
    fun removing_a_section_says_which_one() {
        val firewall = UciConfig(
            "firewall",
            listOf(
                UciSection("cfg0a92bd", "rule", true, 0, mapOf("name" to "Allow-SSH", "dest_port" to "22"), emptyMap()),
                UciSection("cfg0b11aa", "include", true, 1, mapOf("path" to "/etc/x.nft"), emptyMap()),
            ),
        )
        val planned = planEdits(firewall, listOf(EditorEdit.DeleteSection("cfg0a92bd"), EditorEdit.DeleteSection("cfg0b11aa"))) as Planned.Ready
        assertEquals(
            listOf("Remove rule 'Allow-SSH' (cfg0a92bd)", "Remove include section cfg0b11aa"),
            planned.change.summary,
        )
        val added = planEdits(system, listOf(EditorEdit.AddSection("t1", "timeserver"), EditorEdit.DeleteSection("t1"))) as Planned.Ready
        assertEquals("a section added in the same change", "Remove timeserver section t1", added.change.summary.last())
    }
}
