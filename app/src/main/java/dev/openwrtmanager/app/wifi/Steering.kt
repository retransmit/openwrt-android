package dev.openwrtmanager.app.wifi

import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.ssh.SshExecutor
import dev.openwrtmanager.app.uci.UciBatch

data class SteeringRequest(val radios: Set<String>, val fastRoaming: Boolean)

/**
 * Band steering on one device: the same network on several bands, with the roaming assistance
 * (802.11k neighbour reports and 802.11v transition requests) that lets clients pick the better one.
 * Optional fast roaming (802.11r) shortens the handover itself.
 */
object Steering {
    private const val W = "wireless"

    fun enable(config: WifiConfig, group: WifiGroup, request: SteeringRequest): ChangeSet {
        val old = group.first
        val security = old.security
        require(!(request.fastRoaming && security == Security.OPEN)) { "fast roaming needs a password protected network" }
        val name = "\"${group.ssid}\""

        // First make sure the network exists on every requested radio, reusing the normal edit rules.
        val radios = group.ifaces.map { it.radio }.toSet() + request.radios
        val edit = NetworkEdit(
            ssid = old.ssid,
            security = security ?: Security.WPA2,
            key = old.key,
            hidden = old.hidden,
            isolate = old.isolate,
            enabled = group.enabled,
            radios = radios,
        )
        val added = WifiEdits.newSections(config, group, radios)
        val batch = UciBatch()
        val summary = mutableListOf<String>()
        if (added.isNotEmpty()) {
            val extended = WifiEdits.editGroup(config, group, edit)
            batch.append(extended.batch)
            summary += extended.summary
        }

        var assistChanged = false
        var fastChanged = false
        fun assist(section: String, hasK: Boolean, hasV: Boolean, hasR: Boolean) {
            if (!hasK) { batch.set(W, section, "ieee80211k", "1"); assistChanged = true }
            if (!hasV) { batch.set(W, section, "bss_transition", "1"); assistChanged = true }
            if (request.fastRoaming && !hasR) {
                batch.set(W, section, "ieee80211r", "1")
                // Only 802.11r itself and over-the-air handover are set. OpenWrt chooses the key
                // derivation that fits the security mode and derives the mobility domain from the
                // network name, so every radio agrees without the app forcing either.
                batch.set(W, section, "ft_over_ds", "0")
                fastChanged = true
            }
        }
        for (iface in group.ifaces) assist(iface.section, iface.ieee80211k, iface.bssTransition, iface.ieee80211r)
        for (section in added.values) assist(section, hasK = false, hasV = false, hasR = false)

        if (assistChanged) summary += "Help devices on $name move to the better band"
        if (fastChanged) summary += "Turn on fast roaming for $name"
        return ChangeSet.of("Band steering for $name", summary, batch)
    }

    /**
     * Turns the assistance off again. Only options known to be set are deleted, because deleting a
     * missing option would fail the whole batch. The fast roaming helper options are left in place:
     * they do nothing once 802.11r itself is off.
     */
    fun disable(config: WifiConfig, group: WifiGroup): ChangeSet {
        val batch = UciBatch()
        var changed = false
        for (iface in group.ifaces) {
            if (iface.ieee80211k) { batch.delete(W, iface.section, "ieee80211k"); changed = true }
            if (iface.bssTransition) { batch.delete(W, iface.section, "bss_transition"); changed = true }
            if (iface.ieee80211r) { batch.delete(W, iface.section, "ieee80211r"); changed = true }
        }
        val name = "\"${group.ssid}\""
        val summary = if (changed) listOf("Stop helping devices on $name move between bands") else emptyList()
        return ChangeSet.of("Band steering for $name", summary, batch)
    }

}

data class UsteerStatus(val installed: Boolean, val running: Boolean)

enum class PackageManager { APK, OPKG, NONE }

/**
 * Reads and controls the optional steering daemon. Starting or installing it is not a network
 * config change, so these commands deliberately do not go through SafeApply; the UI asks first.
 */
class SteeringClient(private val ssh: SshExecutor) {

    suspend fun usteerStatus(): UsteerStatus {
        val out = ssh.exec(STATUS_COMMAND).stdout
        return UsteerStatus(installed = "OWM_INSTALLED" in out, running = "OWM_RUNNING" in out)
    }

    suspend fun packageManager(): PackageManager = when (ssh.exec(PACKAGE_MANAGER_COMMAND).stdout.trim()) {
        "apk" -> PackageManager.APK
        "opkg" -> PackageManager.OPKG
        else -> PackageManager.NONE
    }

    suspend fun startUsteer(): Boolean =
        ssh.exec("/etc/init.d/usteer enable && /etc/init.d/usteer start", timeoutMs = 30_000).exitCode == 0

    suspend fun installUsteer(manager: PackageManager): Boolean {
        val command = when (manager) {
            PackageManager.APK -> "apk add usteer"
            PackageManager.OPKG -> "opkg update && opkg install usteer"
            PackageManager.NONE -> return false
        }
        return ssh.exec(command, timeoutMs = 120_000).exitCode == 0
    }

    companion object {
        const val STATUS_COMMAND =
            "[ -x /etc/init.d/usteer ] && echo OWM_INSTALLED; pidof usteer >/dev/null 2>&1 && echo OWM_RUNNING; true"
        const val PACKAGE_MANAGER_COMMAND =
            "if command -v apk >/dev/null 2>&1; then echo apk; elif command -v opkg >/dev/null 2>&1; then echo opkg; fi"
    }
}
