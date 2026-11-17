package dev.openwrtmanager.app.admin

import dev.openwrtmanager.app.ssh.shellQuote
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** An init script. [running] is null when it starts no background process. */
data class Service(val name: String, val enabled: Boolean, val running: Boolean?)

enum class ServiceAction(val verb: String) { START("start"), STOP("stop"), RESTART("restart"), ENABLE("enable"), DISABLE("disable") }

internal val SAFE_NAME = Regex("[A-Za-z0-9_.-]+")

private val NO_DAEMON = setOf("firewall")

/** Parses `ubus call rc list`, sorted by name. */
fun parseServices(json: String): List<Service> = try {
    Json.parseToJsonElement(json).jsonObject.mapNotNull { (name, value) ->
        val state = value as? JsonObject ?: return@mapNotNull null
        if (!SAFE_NAME.matches(name)) return@mapNotNull null
        Service(
            name = name,
            enabled = (state["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
            // fw4 loads its rules and exits, so "not running" says nothing about whether it protects.
            running = if (name in NO_DAEMON) null else (state["running"] as? JsonPrimitive)?.booleanOrNull,
        )
    }.sortedBy { it.name }
} catch (e: IllegalArgumentException) {
    emptyList()
}

/**
 * Stopping or disabling these would cut the app off from the device: SSH, the network, and rpcd,
 * which serves the ubus uci and rc calls the app reads everything through.
 */
private val PROTECTED = setOf("dropbear", "network", "rpcd")

fun actionAllowed(name: String, action: ServiceAction): Boolean =
    !(name in PROTECTED && (action == ServiceAction.STOP || action == ServiceAction.DISABLE))

fun actionWarning(name: String, action: ServiceAction): String? {
    val stopping = action == ServiceAction.STOP || action == ServiceAction.DISABLE
    return when {
        stopping && name == "dnsmasq" -> "Devices will lose names and new addresses until it runs again."
        stopping && name == "odhcpd" -> "Devices will lose IPv6 addresses until it runs again."
        stopping && name == "firewall" -> "Your networks will be unprotected and guest isolation stops."
        stopping && name == "log" -> "The device stops recording its log until it runs again."
        action == ServiceAction.RESTART && name in PROTECTED -> "The app reconnects after a few seconds."
        else -> null
    }
}

/** Null when [name] is not a plain service name. */
fun serviceCommand(name: String, action: ServiceAction): String? {
    if (!SAFE_NAME.matches(name)) return null
    val args = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("action", JsonPrimitive(action.verb))
    }.toString()
    return "ubus call rc init ${shellQuote(args)}"
}
