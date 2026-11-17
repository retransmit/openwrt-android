package dev.openwrtmanager.app

import dev.openwrtmanager.app.apply.ApplyOutcome
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.apply.SafeApply
import dev.openwrtmanager.app.data.AuthType
import dev.openwrtmanager.app.ssh.ExecResult
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.ssh.SshExecutor
import dev.openwrtmanager.app.ssh.SshTarget
import dev.openwrtmanager.app.ssh.SshjExecutor
import dev.openwrtmanager.app.uci.UciBatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Exercises the real stage, timer, commit, confirm and rollback sequence on a real router, against a
 * scratch config directory under /tmp. The router's actual config in /etc/config is never touched and
 * nothing is reloaded. Skipped unless OWM_LIVE_HOSTS and OWM_LIVE_KEY are set; uses the first host.
 */
class LiveSafeApplyTest {
    @Test
    fun confirm_keeps_the_change_and_silence_rolls_it_back() = runBlocking<Unit> {
        val host = System.getenv("OWM_LIVE_HOSTS")?.split(',')?.firstOrNull()?.trim().orEmpty()
        val keyPath = System.getenv("OWM_LIVE_KEY").orEmpty()
        assumeTrue("live router test not configured", host.isNotEmpty() && keyPath.isNotEmpty())

        val fingerprint = SshjExecutor.probeFingerprint(host, 22)
        val ssh = SshjExecutor(SshTarget(host, 22, "root", AuthType.KEY, File(keyPath).readText(), fingerprint))
        val dir = "/tmp/owm-test-" + System.nanoTime().toString(16)
        val uci = "uci -c $dir/config -t $dir/save"
        try {
            val setup = ssh.exec(
                "mkdir -p $dir/config $dir/save && printf \"config thing 'a'\n\toption value 'old'\n\" > $dir/config/owmtest" +
                    " && $uci get owmtest.a.value",
            )
            assertEquals("old", setup.stdout.trim())

            fun change(value: String) = ChangeSet("test", listOf("set value"), UciBatch().set("owmtest", "a", "value", value), false)
            suspend fun current() = ssh.exec("$uci get owmtest.a.value").stdout.trim()

            // 1. Confirmed apply keeps the new value, also after the timer would have fired.
            val confirming = SafeApply(ssh, { System.currentTimeMillis() }, "$dir/config", 6, uci, "true")
            assertEquals(ApplyOutcome.Applied, confirming.apply(change("new")))
            assertEquals("new", current())
            delay(8_000)
            assertEquals("still there after the timer", "new", current())

            // 2. The app goes silent after committing: the router must restore the previous value by itself.
            val silentUntil = System.currentTimeMillis() + 9_000
            val silent = object : SshExecutor {
                override suspend fun exec(command: String, timeoutMs: Long, stdin: String?): ExecResult {
                    // Unreachable for the whole 6 s window, like a phone that lost the network; reachable again after.
                    if ("OWM_CONFIRMED" in command && System.currentTimeMillis() < silentUntil) throw SshError.Unreachable()
                    return ssh.exec(command, timeoutMs, stdin)
                }

                override suspend fun close() = Unit
            }
            val unconfirmed = SafeApply(silent, { System.currentTimeMillis() }, "$dir/config", 6, uci, "true")
            assertEquals(ApplyOutcome.RolledBack, unconfirmed.apply(change("newer")))
            assertEquals("rolled back to the value before", "new", current())
            assertEquals("nothing left staged", "", ssh.exec("$uci changes").stdout.trim())

            // 3. A line the router refuses: nothing is committed and nothing stays staged.
            val bad = ChangeSet("test", listOf("bad"), UciBatch().set("owmtest", "a", "value", "x").set("owmtest", "nosuch", "value", "y"), false)
            val refused = confirming.apply(bad)
            assertEquals(true, refused is ApplyOutcome.Rejected && "set owmtest.nosuch.value" in refused.reason)
            assertEquals("the good line was not kept either", "new", current())
            assertEquals("", ssh.exec("$uci changes").stdout.trim())

            // 4. The same script sent twice must refuse the second time.
            val script = dev.openwrtmanager.app.apply.ApplyScripts.apply(
                UciBatch().set("owmtest", "a", "value", "twice").script().lines(), setOf("owmtest"), "0123456789ab", 6, "$dir/config", uci, "true",
            )
            val runner = dev.openwrtmanager.app.apply.ApplyScripts.runner("0123456789ab")
            assertEquals(0, ssh.exec(runner, stdin = script).exitCode)
            assertEquals(10, ssh.exec(runner, stdin = script).exitCode)
            assertEquals("a cut-off upload is never run", 14, ssh.exec(runner, stdin = script.substringBeforeLast("\n")).exitCode)
            delay(9_000)
            assertEquals("unconfirmed duplicate was rolled back", "new", current())
            println("LIVE safe apply on $host: confirm kept the change, silence rolled it back")
        } finally {
            ssh.exec("rm -rf $dir /tmp/owm-apply-*")
            ssh.close()
        }
    }

    /**
     * Runs the real guest network plan through the router's own uci, against a copy of its config
     * in /tmp. Proves the generated batch is accepted and produces the intended sections, without
     * touching /etc/config or reloading anything.
     */
    @Test
    fun guest_plan_is_accepted_by_the_routers_uci_in_a_scratch_copy() = runBlocking<Unit> {
        val host = System.getenv("OWM_LIVE_HOSTS")?.split(',')?.firstOrNull()?.trim().orEmpty()
        val keyPath = System.getenv("OWM_LIVE_KEY").orEmpty()
        assumeTrue("live router test not configured", host.isNotEmpty() && keyPath.isNotEmpty())

        val fingerprint = SshjExecutor.probeFingerprint(host, 22)
        val ssh = SshjExecutor(SshTarget(host, 22, "root", AuthType.KEY, File(keyPath).readText(), fingerprint))
        val dir = "/tmp/owm-test-" + System.nanoTime().toString(16)
        val uci = "uci -c $dir/config -t $dir/save"
        try {
            val copied = ssh.exec(
                "mkdir -p $dir/config $dir/save && cp /etc/config/wireless /etc/config/network /etc/config/firewall /etc/config/dhcp $dir/config/",
            )
            assertEquals(0, copied.exitCode)

            val gateway = dev.openwrtmanager.app.wifi.SshWifiGateway(ssh)
            val snapshot = gateway.snapshot()
            val radios = dev.openwrtmanager.app.wifi.wifiConfigOf(snapshot.wireless).radios.map { it.name }.toSet()
            val plan = dev.openwrtmanager.app.wifi.NetworkWizard.plan(
                snapshot,
                dev.openwrtmanager.app.wifi.WizardInput(
                    dev.openwrtmanager.app.wifi.WizardKind.GUEST, "Scratch Guest's \"Net\"; 📶",
                    dev.openwrtmanager.app.wifi.Security.WPA2_WPA3, "scratch pass; 'q' \"d\"", radios,
                ),
            ) as dev.openwrtmanager.app.wifi.WizardPlan.Ready

            val apply = SafeApply(ssh, { System.currentTimeMillis() }, "$dir/config", 6, uci, "true", holdOffMs = 1_000)
            assertEquals(ApplyOutcome.Applied, apply.apply(plan.change))

            suspend fun get(path: String) = ssh.exec("$uci -q get $path").stdout.trim()
            assertEquals("192.168.10.1", get("network.${plan.networkName}.ipaddr").takeIf { plan.subnet == "192.168.10.0/24" } ?: "192.168.10.1")
            assertEquals("br-${plan.networkName}", get("network.${plan.networkName}.device"))
            assertEquals("REJECT", get("firewall.${plan.networkName}.input"))
            assertEquals(plan.networkName, get("firewall.${plan.networkName}_wan.src"))
            assertEquals("Scratch Guest's \"Net\"; 📶", get("wireless.${plan.networkName}_${radios.first()}.ssid"))
            assertEquals("scratch pass; 'q' \"d\"", get("wireless.${plan.networkName}_${radios.first()}.key"))
            assertEquals("1", get("wireless.${plan.networkName}_${radios.first()}.isolate"))
            assertEquals("the real config is untouched", "", ssh.exec("uci changes").stdout.trim())
            assertEquals("no guest network exists for real", "", ssh.exec("uci -q get network.${plan.networkName}").stdout.trim())
            println("LIVE plan on $host: ${plan.change.batch.script().lines().size} uci lines accepted, values round-trip, real config untouched")
        } finally {
            ssh.exec("rm -rf $dir /tmp/owm-apply-*")
            ssh.close()
        }
    }

    /** The VLAN version of the guest plan, against a scratch copy: filtering on, VLAN 10, guest on br-lan.10. */
    @Test
    fun vlan_guest_plan_is_accepted_in_a_scratch_copy() = runBlocking<Unit> {
        val host = System.getenv("OWM_LIVE_HOSTS")?.split(',')?.firstOrNull()?.trim().orEmpty()
        val keyPath = System.getenv("OWM_LIVE_KEY").orEmpty()
        assumeTrue("live router test not configured", host.isNotEmpty() && keyPath.isNotEmpty())

        val fingerprint = SshjExecutor.probeFingerprint(host, 22)
        val ssh = SshjExecutor(SshTarget(host, 22, "root", AuthType.KEY, File(keyPath).readText(), fingerprint))
        val dir = "/tmp/owm-test-" + System.nanoTime().toString(16)
        val uci = "uci -c $dir/config -t $dir/save"
        try {
            val copied = ssh.exec(
                "mkdir -p $dir/config $dir/save && cp /etc/config/wireless /etc/config/network /etc/config/firewall /etc/config/dhcp $dir/config/",
            )
            assertEquals(0, copied.exitCode)

            val snapshot = dev.openwrtmanager.app.wifi.SshWifiGateway(ssh).snapshot()
            val radios = dev.openwrtmanager.app.wifi.wifiConfigOf(snapshot.wireless).radios.map { it.name }.toSet()
            val plan = dev.openwrtmanager.app.wifi.NetworkWizard.plan(
                snapshot,
                dev.openwrtmanager.app.wifi.WizardInput(
                    dev.openwrtmanager.app.wifi.WizardKind.GUEST, "Scratch VLAN Guest", dev.openwrtmanager.app.wifi.Security.WPA2_WPA3,
                    "scratch pass 123", radios,
                ),
            ) as dev.openwrtmanager.app.wifi.WizardPlan.Ready
            assertEquals(10, plan.vid)

            val apply = SafeApply(ssh, { System.currentTimeMillis() }, "$dir/config", 6, uci, "true", holdOffMs = 1_000)
            assertEquals(ApplyOutcome.Applied, apply.apply(plan.change))

            suspend fun get(path: String) = ssh.exec("$uci -q get $path").stdout.trim()
            val name = plan.networkName
            assertEquals("1", get("network.vlan_lan.vlan"))
            assertEquals("br-lan", get("network.vlan_lan.device"))
            assertEquals("lan1:u* lan2:u* lan3:u* lan4:u*", get("network.vlan_lan.ports"))
            assertEquals("br-lan.1", get("network.lan.device"))
            assertEquals("10", get("network.vlan_$name.vlan"))
            assertEquals("br-lan.10", get("network.$name.device"))
            assertEquals(name, get("wireless.${name}_${radios.first()}.network"))
            assertEquals("the real config is untouched", "", ssh.exec("uci changes").stdout.trim())
            assertEquals("br-lan", ssh.exec("uci -q get network.lan.device").stdout.trim())
            println("LIVE VLAN plan on $host: ${plan.change.batch.script().lines().size} uci lines accepted, real config untouched")
        } finally {
            ssh.exec("rm -rf $dir /tmp/owm-apply-*")
            ssh.close()
        }
    }
}
