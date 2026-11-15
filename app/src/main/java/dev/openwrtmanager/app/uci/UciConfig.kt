package dev.openwrtmanager.app.uci

import dev.openwrtmanager.app.router.RouterError
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** One section of a UCI config file, as the router reports it. */
data class UciSection(
    val name: String,
    val type: String,
    val anonymous: Boolean,
    val index: Int,
    val options: Map<String, String>,
    val lists: Map<String, List<String>>,
) {
    /** A scalar option, or the first element when the option is a list. */
    fun opt(key: String): String? = options[key] ?: lists[key]?.firstOrNull()

    /** A list option, or a scalar option as a one-element list. */
    fun list(key: String): List<String> = lists[key] ?: options[key]?.let(::listOf) ?: emptyList()

    fun flag(key: String, default: Boolean = false): Boolean {
        val value = opt(key) ?: return default
        return value in TRUE_VALUES
    }

    private companion object {
        val TRUE_VALUES = setOf("1", "on", "true", "yes", "enabled")
    }
}

/** A snapshot of one UCI config file. Sections are in the order they appear on the router. */
data class UciConfig(val name: String, val sections: List<UciSection>) {
    fun ofType(type: String): List<UciSection> = sections.filter { it.type == type }

    operator fun get(sectionName: String): UciSection? = sections.firstOrNull { it.name == sectionName }
}

/** Parses the output of `ubus call uci get '{"config":"..."}'`. */
fun parseUciConfig(configName: String, ubusJson: String): UciConfig {
    val values = try {
        Json.parseToJsonElement(ubusJson).jsonObject["values"]?.jsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: throw RouterError.BadResponse("uci get $configName")

    val sections = values.mapNotNull { (key, element) ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val options = mutableMapOf<String, String>()
        val lists = mutableMapOf<String, List<String>>()
        for ((option, value) in obj) {
            if (option.startsWith(".")) continue
            when (value) {
                is JsonPrimitive -> options[option] = value.content
                is JsonArray -> lists[option] = value.mapNotNull { (it as? JsonPrimitive)?.content }
                else -> Unit
            }
        }
        UciSection(
            name = (obj[".name"] as? JsonPrimitive)?.content ?: key,
            type = (obj[".type"] as? JsonPrimitive)?.content.orEmpty(),
            anonymous = (obj[".anonymous"] as? JsonPrimitive)?.booleanOrNull ?: false,
            index = (obj[".index"] as? JsonPrimitive)?.intOrNull ?: Int.MAX_VALUE,
            options = options,
            lists = lists,
        )
    }
    return UciConfig(configName, sections.sortedBy { it.index })
}
