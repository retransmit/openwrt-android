package dev.openwrtmanager.app

import dev.openwrtmanager.app.apply.ApplyOutcome
import dev.openwrtmanager.app.apply.SafeApply
import dev.openwrtmanager.app.clients.BlockPlanner
import dev.openwrtmanager.app.data.AuthType
import dev.openwrtmanager.app.ssh.SshTarget
import dev.openwrtmanager.app.ssh.SshjExecutor
import dev.openwrtmanager.app.uci.UciClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Blocks a real device on a real router and unblocks it again. This changes the router's firewall,
 * so it only runs when OWM_BLOCK_MAC is set, which the user agrees to each time.
 * OWM_BLOCK_HOLD_SECONDS keeps the block in place for a manual check (default 30).
 */
class LiveBlockTest {
    @Test
    fun block_drops_ahead_of_established_flows_and_unblock_removes_it() = runBlocking<Unit> {
        val host = System.getenv("OWM_LIVE_HOSTS")?.split(',')?.firstOrNull()?.trim().orEmpty()
        val keyPath = System.getenv("OWM_LIVE_KEY").orEmpty()
        val mac = System.getenv("OWM_BLOCK_MAC").orEmpty()
        assumeTrue("live block test not configured", host.isNotEmpty() && keyPath.isNotEmpty() && mac.isNotEmpty())
        val hold = System.getenv("OWM_BLOCK_HOLD_SECONDS")?.toLongOrNull() ?: 30

        val fingerprint = SshjExecutor.probeFingerprint(host, 22)
        val ssh = SshjExecutor(SshTarget(host, 22, "root", AuthType.KEY, File(keyPath).readText(), fingerprint))
        try {
            assertEquals(0, ssh.exec(BlockPlanner.ensureFileCommand()).exitCode)
            assertEquals(BlockPlanner.FILE_CONTENT, ssh.exec("cat ${BlockPlanner.FILE_PATH}").stdout)

            val uci = UciClient(ssh)
            val block = BlockPlanner.block(uci.load("firewall"), mac, "test device")
            assertEquals(ApplyOutcome.Applied, SafeApply(ssh, { System.currentTimeMillis() }).apply(block))

            val chain = ssh.exec("nft list chain inet fw4 forward").stdout
            println(chain)
            val drop = chain.indexOf("@owm_blocked")
            val established = chain.indexOf("ct state vmap")
            assertTrue("drop rule present", drop >= 0)
            assertTrue("drop rule before the established accept", drop < established)
            assertTrue(ssh.exec("nft list set inet fw4 owm_blocked").stdout.uppercase().contains(mac.uppercase()))
            println("LIVE block on $host: $mac blocked, holding $hold s for a manual check")
            delay(hold * 1_000)
            println(ssh.exec("nft list chain inet fw4 forward | grep owm_blocked").stdout)

            val unblock = BlockPlanner.unblock(uci.load("firewall"), mac, "test device")
            assertEquals(ApplyOutcome.Applied, SafeApply(ssh, { System.currentTimeMillis() }).apply(unblock))
            assertEquals("", ssh.exec("uci -q get firewall.owm_blocked").stdout.trim())
            assertFalse(ssh.exec("nft list chain inet fw4 forward").stdout.contains("@owm_blocked"))
            println("LIVE unblock on $host: $mac unblocked, set and include gone")
        } finally {
            ssh.close()
        }
    }
}
