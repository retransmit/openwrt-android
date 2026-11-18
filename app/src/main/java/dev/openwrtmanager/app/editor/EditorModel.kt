package dev.openwrtmanager.app.editor

import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.clients.Planned
import dev.openwrtmanager.app.uci.UciBatch
import dev.openwrtmanager.app.uci.UciConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** One staged change in the config editor. Sections are named by their real name, anonymous ones included. */
sealed interface EditorEdit {
    data class SetOption(val section: String, val option: String, val value: String) : EditorEdit
    data class SetList(val section: String, val option: String, val values: List<String>) : EditorEdit
    data class DeleteOption(val section: String, val option: String) : EditorEdit
    data class AddSection(val name: String, val type: String) : EditorEdit
    data class DeleteSection(val section: String) : EditorEdit
}

enum class EditorProblem { BAD_NAME, SECTION_GONE, SECTION_EXISTS, LINE_BREAK, EMPTY_VALUE, NOTHING_TO_DO }

private val NAME = Regex("[A-Za-z0-9_]+")

/** Config file names the apply step accepts. */
private val CONFIG_NAME = Regex("[a-z0-9_]+")
private val SECRET = Regex("key|pass|secret|psk", RegexOption.IGNORE_CASE)

/** Options whose values are passwords or keys, kept off the screen unless the user opens them. */
fun isSecretOption(option: String) = SECRET.containsMatchIn(option)

private fun hasLineBreak(value: String) = value.any { it == '\n' || it == '\r' }

/**
 * Turns staged edits into one change, checked against [config] as it is now: an edit to a section
 * that is gone (removed elsewhere, or earlier in the same change) is refused rather than written.
 * Every editor change is marked as possibly dropping the connection, since any config may affect access.
 */
fun planEdits(config: UciConfig, edits: List<EditorEdit>): Planned<EditorProblem> {
    val c = config.name
    val options: MutableMap<String, MutableSet<String>> = config.sections
        .associate { s -> s.name to (s.options.keys + s.lists.keys).toMutableSet() }
        .toMutableMap()
    val batch = UciBatch()
    val summary = mutableListOf<String>()
    val added = mutableMapOf<String, String>()

    fun shown(option: String, value: String) = if (SECRET.containsMatchIn(option)) "a new value" else value

    for (edit in edits) {
        when (edit) {
            is EditorEdit.SetOption -> {
                if (!NAME.matches(edit.section) || !NAME.matches(edit.option)) return Planned.Problem(EditorProblem.BAD_NAME)
                val existing = options[edit.section] ?: return Planned.Problem(EditorProblem.SECTION_GONE)
                if (hasLineBreak(edit.value)) return Planned.Problem(EditorProblem.LINE_BREAK)
                // uci turns an empty value into a delete, which "Set ... to" would hide.
                if (edit.value.isEmpty()) return Planned.Problem(EditorProblem.EMPTY_VALUE)
                batch.set(c, edit.section, edit.option, edit.value)
                existing += edit.option
                summary += "Set ${edit.section}.${edit.option} to ${shown(edit.option, edit.value)}"
            }
            is EditorEdit.SetList -> {
                if (!NAME.matches(edit.section) || !NAME.matches(edit.option)) return Planned.Problem(EditorProblem.BAD_NAME)
                val existing = options[edit.section] ?: return Planned.Problem(EditorProblem.SECTION_GONE)
                if (edit.values.any(::hasLineBreak)) return Planned.Problem(EditorProblem.LINE_BREAK)
                val values = edit.values.filter { it.isNotBlank() }
                if (edit.option in existing) batch.delete(c, edit.section, edit.option)
                values.forEach { batch.addList(c, edit.section, edit.option, it) }
                if (values.isEmpty()) {
                    if (edit.option in existing) summary += "Remove ${edit.section}.${edit.option}"
                    existing -= edit.option
                } else {
                    existing += edit.option
                    summary += "Set ${edit.section}.${edit.option} to ${values.joinToString(", ") { shown(edit.option, it) }}"
                }
            }
            is EditorEdit.DeleteOption -> {
                if (!NAME.matches(edit.section) || !NAME.matches(edit.option)) return Planned.Problem(EditorProblem.BAD_NAME)
                val existing = options[edit.section] ?: return Planned.Problem(EditorProblem.SECTION_GONE)
                // Deleting an option that is not there would make uci fail the whole change.
                if (edit.option in existing) {
                    batch.delete(c, edit.section, edit.option)
                    existing -= edit.option
                    summary += "Remove ${edit.section}.${edit.option}"
                }
            }
            is EditorEdit.AddSection -> {
                if (!NAME.matches(edit.name) || !NAME.matches(edit.type)) return Planned.Problem(EditorProblem.BAD_NAME)
                if (edit.name in options) return Planned.Problem(EditorProblem.SECTION_EXISTS)
                batch.section(c, edit.name, edit.type)
                options[edit.name] = mutableSetOf()
                added[edit.name] = edit.type
                summary += "Add section ${edit.name} (${edit.type})"
            }
            is EditorEdit.DeleteSection -> {
                if (!NAME.matches(edit.section)) return Planned.Problem(EditorProblem.BAD_NAME)
                options.remove(edit.section) ?: return Planned.Problem(EditorProblem.SECTION_GONE)
                batch.delete(c, edit.section)
                summary += "Remove ${describeSection(edit.section, config, added)}"
            }
        }
    }
    if (batch.isEmpty()) return Planned.Problem(EditorProblem.NOTHING_TO_DO)
    return Planned.Ready(ChangeSet("Edit $c", summary, batch, mayDropConnection = true))
}

/**
 * A section as the review names it: its type plus its `name` option when it has one, since unnamed
 * sections such as firewall rules are otherwise only a cfgXXXX id.
 */
private fun describeSection(section: String, config: UciConfig, added: Map<String, String>): String {
    val existing = config.sections.find { it.name == section }
    val type = added[section] ?: existing?.type ?: return "section $section"
    val label = existing?.options?.get("name")?.takeIf { added[section] == null && it.isNotBlank() && !hasLineBreak(it) }
    return if (label != null) "$type '$label' ($section)" else "$type section $section"
}

/** `ubus call uci configs`, sorted. */
fun parseConfigs(json: String): List<String> = try {
    Json.parseToJsonElement(json).jsonObject["configs"]?.jsonArray.orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { name -> CONFIG_NAME.matches(name) } }
        .sorted()
} catch (e: IllegalArgumentException) {
    emptyList()
}
