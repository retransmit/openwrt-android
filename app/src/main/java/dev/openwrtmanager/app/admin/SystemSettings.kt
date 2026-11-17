package dev.openwrtmanager.app.admin

import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.uci.UciBatch
import dev.openwrtmanager.app.uci.UciConfig

/** The device's `system` section. Its name is anonymous (cfgXXXXXX) on every OpenWrt. */
data class SystemConfig(val section: String, val hostname: String, val zonename: String?, val timezone: String?)

fun systemConfigOf(system: UciConfig): SystemConfig? = system.ofType("system").firstOrNull()?.let {
    SystemConfig(it.name, it.opt("hostname") ?: "OpenWrt", it.opt("zonename"), it.opt("timezone"))
}

private val HOSTNAME = Regex("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?")

fun isValidHostname(name: String): Boolean = HOSTNAME.matches(name)

object SystemPlanner {
    /** Null when the name does not change. */
    fun hostname(current: SystemConfig, name: String): ChangeSet? {
        if (name == current.hostname) return null
        require(isValidHostname(name)) { "bad hostname" }
        return ChangeSet.of(
            "Rename device",
            listOf("Rename this device from ${current.hostname} to $name"),
            UciBatch().set("system", current.section, "hostname", name),
        )
    }

    fun timezone(current: SystemConfig, zone: String, tz: String): ChangeSet = ChangeSet.of(
        "Set time zone",
        listOf("Set the time zone to $zone"),
        UciBatch().set("system", current.section, "zonename", zone).set("system", current.section, "timezone", tz),
    )
}

/** Zone name and POSIX TZ string per line, tab separated, as bundled in assets/zoneinfo.tsv. */
fun parseZones(tsv: String): List<Pair<String, String>> = tsv.lines().mapNotNull { line ->
    val parts = line.split('\t')
    if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0] to parts[1] else null
}

/** What the router said about a password change. UNKNOWN: the link dropped, so it may or may not have changed. */
enum class PasswordResult { CHANGED, REFUSED, UNKNOWN }

/** 8 to 128 printable ASCII characters; spaces allowed, also at the ends. */
fun isValidPassword(password: String): Boolean = password.length in 8..128 && password.all { it.code in 0x20..0x7e }

/**
 * Reads the new password from standard input and gives it to busybox passwd twice, the way LuCI
 * does. The password never appears in a command line, so not in logs or the process list.
 * `IFS=` keeps spaces at the ends.
 */
const val PASSWORD_COMMAND =
    """sh -c 'IFS= read -r p; { printf "%s\n" "${'$'}p"; sleep 1; printf "%s\n" "${'$'}p"; } | passwd root'"""
