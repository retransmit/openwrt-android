package dev.openwrtmanager.app

import dev.openwrtmanager.app.data.AuthType
import dev.openwrtmanager.app.domain.RateSampler
import dev.openwrtmanager.app.router.RouterClient
import dev.openwrtmanager.app.ssh.SshTarget
import dev.openwrtmanager.app.ssh.SshjExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Read-only check against real routers. Skipped unless both variables are set:
 *   OWM_LIVE_HOSTS  comma separated addresses, for example 192.168.1.1,192.168.0.2
 *   OWM_LIVE_KEY    path to a private key that root accepts on those hosts
 * It runs only the status queries the Home screen uses and changes nothing on the router.
 */
class LiveRouterTest {
    @Test
    fun home_screen_queries_work_on_real_routers() = runBlocking<Unit> {
        val hosts = System.getenv("OWM_LIVE_HOSTS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val keyPath = System.getenv("OWM_LIVE_KEY").orEmpty()
        assumeTrue("live router test not configured", hosts.isNotEmpty() && keyPath.isNotEmpty())
        val key = File(keyPath).readText()

        for (host in hosts) {
            val fingerprint = SshjExecutor.probeFingerprint(host, 22)
            assertTrue(fingerprint.startsWith("SHA256:"))
            val ssh = SshjExecutor(SshTarget(host, 22, "root", AuthType.KEY, key, fingerprint))
            try {
                val router = RouterClient(ssh)
                val info = router.systemInfo()
                assertTrue("hostname on $host", info.hostname.isNotBlank())
                assertTrue("memory on $host", info.memTotalBytes > 0 && info.memAvailableBytes in 1..info.memTotalBytes)
                assertTrue("uptime on $host", info.uptimeSeconds > 0)

                val wifi = router.wifiNetworks()
                assertTrue("wifi on $host", wifi.isNotEmpty() && wifi.all { it.ssid.isNotEmpty() })

                val uplink = router.uplinkDevice()
                assertNotNull("uplink on $host", uplink)
                val sampler = RateSampler()
                val first = router.counters(uplink!!)
                assertTrue("counters on $host", first.rxBytes > 0 && first.txBytes > 0)
                sampler.sample(first, 0)
                delay(1_000)
                val rate = sampler.sample(router.counters(uplink), 1_000)
                assertNotNull(rate)

                val leases = router.leaseCount()
                println(
                    "LIVE $host: model='${info.model}' firmware='${info.firmware}' uplink=$uplink " +
                        "wifi=${wifi.size} clients=${wifi.sumOf { it.clientCount }} leases=$leases " +
                        "down=${rate!!.downBps}bps up=${rate.upBps}bps",
                )
            } finally {
                ssh.close()
            }
        }
    }
}
