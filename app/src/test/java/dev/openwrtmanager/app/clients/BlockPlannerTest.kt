package dev.openwrtmanager.app.clients

import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import dev.openwrtmanager.app.uci.parseUciConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** fw4 (verified on 192.168.1.1): ipset needs `name` and `list match 'src_mac'`; the default family is fine for MACs. */
class BlockPlannerTest {
    private val firewall = parseUciConfig("firewall", Fixtures.read("uci_firewall.json"))

    private fun withBlocks(vararg macs: String) = UciConfig(
        "firewall",
        firewall.sections +
            UciSection("owm_blocked", "ipset", false, 90, mapOf("name" to "owm_blocked"), mapOf("match" to listOf("src_mac"), "entry" to macs.toList())) +
            UciSection("owm_blocked_drop", "include", false, 91, mapOf("type" to "nftables"), emptyMap()),
    )

    @Test
    fun first_block_creates_set_and_include() {
        val change = BlockPlanner.block(firewall, "aa:bb:cc:dd:ee:ff", "Living room TV")
        assertEquals(
            listOf(
                "set firewall.owm_blocked=ipset",
                "set firewall.owm_blocked.name='owm_blocked'",
                "set firewall.owm_blocked.match='src_mac'",
                "add_list firewall.owm_blocked.entry='AA:BB:CC:DD:EE:FF'",
                "set firewall.owm_blocked_drop=include",
                "set firewall.owm_blocked_drop.type='nftables'",
                "set firewall.owm_blocked_drop.path='/etc/owm/blocked.nft'",
                "set firewall.owm_blocked_drop.position='chain-prepend'",
                "set firewall.owm_blocked_drop.chain='forward'",
            ),
            change.batch.script().lines(),
        )
        assertTrue(change.mayDropConnection)
    }

    @Test
    fun second_block_only_adds_an_entry() {
        val change = BlockPlanner.block(withBlocks("AA:BB:CC:DD:EE:FF"), "11:22:33:44:55:66", "Laptop")
        assertEquals(listOf("add_list firewall.owm_blocked.entry='11:22:33:44:55:66'"), change.batch.script().lines())
    }

    @Test
    fun blocking_twice_adds_no_duplicate() {
        assertTrue(BlockPlanner.block(withBlocks("AA:BB:CC:DD:EE:FF"), "aa:bb:cc:dd:ee:ff", "TV").isEmpty())
    }

    @Test
    fun unblock_removes_the_entry() {
        val change = BlockPlanner.unblock(withBlocks("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"), "aa:bb:cc:dd:ee:ff", "TV")
        assertEquals(listOf("del_list firewall.owm_blocked.entry='AA:BB:CC:DD:EE:FF'"), change.batch.script().lines())
        assertEquals(listOf("Unblock TV (AA:BB:CC:DD:EE:FF)"), change.summary)
    }

    @Test
    fun unblocking_the_last_removes_both_sections() {
        val lines = BlockPlanner.unblock(withBlocks("AA:BB:CC:DD:EE:FF"), "AA:BB:CC:DD:EE:FF", "TV").batch.script().lines()
        assertEquals(listOf("delete firewall.owm_blocked_drop", "delete firewall.owm_blocked"), lines)
    }

    @Test
    fun unblocking_a_mac_that_is_not_blocked_is_empty() {
        assertTrue(BlockPlanner.unblock(withBlocks("AA:BB:CC:DD:EE:FF"), "11:22:33:44:55:66", "X").isEmpty())
        assertTrue(BlockPlanner.unblock(firewall, "11:22:33:44:55:66", "X").isEmpty())
    }

    @Test
    fun non_mac_entries_are_ignored_not_crashed_on() {
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), BlockPlanner.blockedMacs(withBlocks("AA:BB:CC:DD:EE:FF", "garbage", "10.0.0.1")))
    }

    @Test
    fun ensure_file_command_keeps_the_file_across_upgrades() {
        val command = BlockPlanner.ensureFileCommand()
        assertTrue(command.contains("/etc/sysupgrade.conf"))
        assertTrue("only added once", command.contains("grep -qx '/etc/owm/' /etc/sysupgrade.conf"))
    }

    @Test
    fun blocked_macs_are_read_upper_case() {
        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), BlockPlanner.blockedMacs(withBlocks("aa:bb:cc:dd:ee:ff")))
        assertEquals(emptyList<String>(), BlockPlanner.blockedMacs(firewall))
    }

    @Test
    fun bad_mac_is_rejected() {
        for (bad in listOf("", "aa:bb:cc:dd:ee", "aa:bb:cc:dd:ee:fg", "aa-bb-cc-dd-ee-ff", "aa:bb:cc:dd:ee:ff; reboot")) {
            assertFalse(bad, isMac(bad))
            assertThrows(bad, IllegalArgumentException::class.java) { BlockPlanner.block(firewall, bad, "x") }
        }
        assertTrue(isMac("aA:bB:cC:dD:eE:fF"))
        assertEquals("AA:BB:CC:DD:EE:FF", normalMac("aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun summary_says_immediate_and_names_the_device() {
        val change = BlockPlanner.block(firewall, "aa:bb:cc:dd:ee:ff", "Living room TV")
        assertEquals("Block device", change.title)
        assertEquals(
            listOf("Block Living room TV (AA:BB:CC:DD:EE:FF)", "It cannot reach the internet or your other networks, starting now"),
            change.summary,
        )
    }

    @Test
    fun ensure_file_command_is_idempotent_text() {
        val command = BlockPlanner.ensureFileCommand()
        assertTrue(command.contains("cmp -s"))
        assertTrue(command.contains("/etc/owm/blocked.nft"))
        assertTrue(command.contains("ether saddr @owm_blocked counter drop"))
        assertTrue("written through a temp file", command.contains("mv "))
        assertEquals("ether saddr @owm_blocked counter drop\n", BlockPlanner.FILE_CONTENT)
    }

    @Test
    fun offloading_reads_defaults() {
        assertFalse(BlockPlanner.offloadingOn(firewall))
        val on = UciConfig(
            "firewall",
            firewall.sections.map { if (it.type == "defaults") it.copy(options = it.options + ("flow_offloading" to "1")) else it },
        )
        assertTrue(BlockPlanner.offloadingOn(on))
    }
}
