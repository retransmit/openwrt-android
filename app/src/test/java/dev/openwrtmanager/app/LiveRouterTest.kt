package dev.openwrtmanager.app

import dev.openwrtmanager.app.data.AuthType
import dev.openwrtmanager.app.domain.RateSampler
import dev.openwrtmanager.app.router.RouterClient
import dev.openwrtmanager.app.ssh.SshTarget
import dev.openwrtmanager.app.ssh.SshjExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

                // Null is a valid answer: an access point has no internet-facing interface of its own.
                val uplink = router.uplinkDevice()
                var rateText = "none"
                if (uplink != null) {
                    val sampler = RateSampler()
                    val first = router.counters(uplink)
                    assertTrue("counters on $host", first.rxBytes > 0 && first.txBytes > 0)
                    sampler.sample(first, 0)
                    delay(1_000)
                    val rate = sampler.sample(router.counters(uplink), 1_000)
                    assertNotNull(rate)
                    rateText = "down=${rate!!.downBps}bps up=${rate.upBps}bps"
                }

                val leases = router.leaseCount()
                assertNotNull("lease count on $host", leases)
                println(
                    "LIVE $host: firmware='${info.firmware}' uplink=$uplink wifi=${wifi.size} " +
                        "clients=${wifi.sumOf { it.clientCount }} leases=$leases rate=$rateText",
                )
            } finally {
                ssh.close()
            }
        }
    }

    /** Phase 2 reads: config snapshots, the Wi-Fi model, and what the guest wizard would do. Nothing is staged. */
    /** Downloads a backup and uploads it back for checking, then discards it. Never restores. */
    @Test
    fun backup_round_trips_through_the_router_without_restoring() = runBlocking<Unit> {
        val host = System.getenv("OWM_LIVE_HOSTS")?.split(',')?.firstOrNull()?.trim().orEmpty()
        val keyPath = System.getenv("OWM_LIVE_KEY").orEmpty()
        assumeTrue("live router test not configured", host.isNotEmpty() && keyPath.isNotEmpty())
        val fingerprint = SshjExecutor.probeFingerprint(host, 22)
        val ssh = SshjExecutor(SshTarget(host, 22, "root", AuthType.KEY, File(keyPath).readText(), fingerprint))
        try {
            val gateway = dev.openwrtmanager.app.admin.SshAdminGateway(ssh)
            val backup = gateway.downloadBackup()!!
            assertTrue("a real archive", backup.size > 1_000 && dev.openwrtmanager.app.admin.isGzip(backup))
            val token = gateway.uploadRestore(backup)
            assertTrue("the router accepts its own backup", token != null)
            gateway.discardRestore(token!!)
            assertEquals("nothing left behind", "", ssh.exec("ls /tmp | grep -E 'owm-(backup|restore)' || true").stdout.trim())
            assertTrue("a broken archive is refused", gateway.uploadRestore(backup.copyOf(backup.size / 2)) == null)
            assertEquals("", ssh.exec("ls /tmp | grep -E 'owm-(backup|restore)' || true").stdout.trim())
            println("LIVE backup on $host: ${backup.size} bytes, round trip accepted, broken archive refused, nothing left in /tmp")
        } finally {
            ssh.close()
        }
    }

    @Test
    fun client_sources_read_on_real_routers() = runBlocking<Unit> {
        val hosts = System.getenv("OWM_LIVE_HOSTS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val keyPath = System.getenv("OWM_LIVE_KEY").orEmpty()
        assumeTrue("live router test not configured", hosts.isNotEmpty() && keyPath.isNotEmpty())
        val key = File(keyPath).readText()

        for (host in hosts) {
            val fingerprint = SshjExecutor.probeFingerprint(host, 22)
            val ssh = SshjExecutor(SshTarget(host, 22, "root", AuthType.KEY, key, fingerprint))
            try {
                val readings = dev.openwrtmanager.app.clients.ClientSources(ssh).read()
                assertTrue("all MACs upper case on $host", (readings.leases.map { it.mac } + readings.neighbours.map { it.mac }).all { it == it.uppercase() })
                println(
                    "LIVE clients on $host: ${readings.leases.size} leases, ${readings.associations.size} Wi-Fi, " +
                        "${readings.neighbours.size} neighbours, ${readings.hintNames.size} hints, missing=${readings.missing}",
                )
            } finally {
                ssh.close()
            }
        }
    }

    @Test
    fun wifi_config_and_wizard_planning_work_on_real_routers() = runBlocking<Unit> {
        val hosts = System.getenv("OWM_LIVE_HOSTS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val keyPath = System.getenv("OWM_LIVE_KEY").orEmpty()
        assumeTrue("live router test not configured", hosts.isNotEmpty() && keyPath.isNotEmpty())
        val key = File(keyPath).readText()

        for (host in hosts) {
            val fingerprint = SshjExecutor.probeFingerprint(host, 22)
            val ssh = SshjExecutor(SshTarget(host, 22, "root", AuthType.KEY, key, fingerprint))
            try {
                val gateway = dev.openwrtmanager.app.wifi.SshWifiGateway(ssh)
                val snapshot = gateway.snapshot()
                val wifi = dev.openwrtmanager.app.wifi.wifiConfigOf(snapshot.wireless)
                assertTrue("radios on $host", wifi.radios.isNotEmpty())
                assertTrue("groups on $host", wifi.groups.isNotEmpty())
                assertTrue("known security on $host", wifi.ifaces.all { it.security != null })
                assertTrue("network config on $host", snapshot.network["lan"] != null)
                assertTrue("firewall zones on $host", snapshot.firewall.ofType("zone").isNotEmpty())
                val usteer = gateway.usteerStatus()

                val input = dev.openwrtmanager.app.wifi.WizardInput(
                    dev.openwrtmanager.app.wifi.WizardKind.GUEST, "Live-Test-Guest",
                    dev.openwrtmanager.app.wifi.Security.WPA2_WPA3, "not-a-real-password",
                    wifi.radios.map { it.name }.toSet(),
                )
                val plan = dev.openwrtmanager.app.wifi.NetworkWizard.plan(snapshot, input)
                val planText = when (plan) {
                    is dev.openwrtmanager.app.wifi.WizardPlan.Ready ->
                        "READY name=${plan.networkName} subnet=${plan.subnet} lines=${plan.change.batch.script().lines().size}"
                    is dev.openwrtmanager.app.wifi.WizardPlan.Blocked -> "BLOCKED ${plan.reason}"
                }
                assertEquals(
                    "a device with an uplink can host a guest network, one without cannot",
                    snapshot.hasUplink,
                    plan is dev.openwrtmanager.app.wifi.WizardPlan.Ready,
                )
                assertEquals("nothing was staged on $host", "", dev.openwrtmanager.app.uci.UciClient(ssh).pendingChanges())
                println(
                    "LIVE2 $host: radios=${wifi.radios.map { it.band }} groups=${wifi.groups.size} " +
                        "assist=${wifi.groups.map { it.steeringAssist }} used=${snapshot.usedSubnets} uplink=${snapshot.hasUplink} " +
                        "usteer=$usteer wizard=$planText",
                )
            } finally {
                ssh.close()
            }
        }
    }
}
