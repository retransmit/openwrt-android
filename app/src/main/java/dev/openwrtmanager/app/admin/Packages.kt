package dev.openwrtmanager.app.admin

import dev.openwrtmanager.app.ssh.shellQuote

/** apk on OpenWrt 25.x and snapshots, opkg on older releases. */
enum class PackageTool { APK, OPKG }

enum class PackageAction { LIST_INSTALLED, UPDATE, SEARCH, INSTALL, REMOVE }

data class Package(val name: String, val version: String, val installed: Boolean)

/** What a package command printed, for showing to the user. */
data class CommandOutput(val exitCode: Int, val text: String)

private val PACKAGE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")

fun isPackageName(name: String): Boolean = PACKAGE_NAME.matches(name)

/**
 * Removing these would cut the app off or break the device: SSH, the package manager itself, ucode
 * (backups need it), the system's core and the firewall.
 */
private val ESSENTIAL = setOf(
    "dropbear", "opkg", "ucode", "ucode-mod-fs", "busybox", "base-files", "rpcd", "ubus", "ubusd", "uci",
    "procd", "netifd", "firewall4", "libc", "musl", "kernel",
)

fun removalAllowed(name: String): Boolean = name !in ESSENTIAL && !name.startsWith("apk-") && name != "apk"

const val DETECT_TOOL_COMMAND = "command -v apk >/dev/null && echo apk || { command -v opkg >/dev/null && echo opkg; }"

fun parseTool(output: String): PackageTool? = when (output.trim()) {
    "apk" -> PackageTool.APK
    "opkg" -> PackageTool.OPKG
    else -> null
}

/** Null when the action needs a name and [name] is missing or not a plain package name. */
fun packageCommand(tool: PackageTool, action: PackageAction, name: String?): String? {
    val needsName = action == PackageAction.SEARCH || action == PackageAction.INSTALL || action == PackageAction.REMOVE
    if (needsName && (name == null || !isPackageName(name))) return null
    return when (tool) {
        PackageTool.APK -> when (action) {
            PackageAction.LIST_INSTALLED -> "apk list --installed"
            PackageAction.UPDATE -> "apk update"
            // apk matches its patterns with fnmatch: without the stars only the exact name is found.
            PackageAction.SEARCH -> "apk list --available ${shellQuote("*$name*")}"
            PackageAction.INSTALL -> "apk add ${shellQuote(name!!)}"
            PackageAction.REMOVE -> "apk del ${shellQuote(name!!)}"
        }
        PackageTool.OPKG -> when (action) {
            PackageAction.LIST_INSTALLED -> "opkg list-installed"
            PackageAction.UPDATE -> "opkg update"
            PackageAction.SEARCH -> "opkg list ${shellQuote("*$name*")}"
            PackageAction.INSTALL -> "opkg install ${shellQuote(name!!)}"
            PackageAction.REMOVE -> "opkg remove ${shellQuote(name!!)}"
        }
    }
}

/** A version: starts with a digit, no hyphen except a final -rN release. */
private val APK_VERSION = Regex("""\d[^-]*(-r\d+)?""")

/**
 * `apk list` lines: `name-version arch {origin} (license) [installed]`. The name may itself contain
 * hyphens and digits, so the version starts at the first hyphen whose remainder reads as a version.
 */
fun parseApkList(text: String): List<Package> = text.lines().mapNotNull { line ->
    val token = line.trim().substringBefore(' ')
    if (token.isEmpty() || line.startsWith("WARNING") || line.startsWith("ERROR")) return@mapNotNull null
    val split = token.indices.firstOrNull { i -> token[i] == '-' && i > 0 && APK_VERSION.matches(token.substring(i + 1)) }
        ?: return@mapNotNull null
    Package(token.substring(0, split), token.substring(split + 1), "[installed]" in line)
}

/** `opkg list-installed` (`name - version`) and `opkg list` (`name - version - description`). */
fun parseOpkgList(text: String, installed: Boolean): List<Package> = text.lines().mapNotNull { line ->
    val parts = line.split(" - ")
    if (parts.size < 2 || !isPackageName(parts[0].trim())) return@mapNotNull null
    Package(parts[0].trim(), parts[1].trim(), installed)
}
