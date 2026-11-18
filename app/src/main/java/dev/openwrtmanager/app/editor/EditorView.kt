package dev.openwrtmanager.app.editor

import dev.openwrtmanager.app.uci.UciConfig

enum class ItemState { SAME, CHANGED, ADDED, REMOVED }

/**
 * One option as the editor shows it: [old] is what the device has (null if the option is new),
 * [new] what it will have once applied, and [editValue] what the edit dialog starts from.
 */
data class OptionView(
    val option: String,
    val old: String?,
    val new: String,
    val editValue: String,
    val isList: Boolean,
    val state: ItemState,
)

data class SectionView(
    val name: String,
    val type: String,
    val anonymous: Boolean,
    val state: ItemState,
    val options: List<OptionView>,
)

/** The device's config with the staged [edits] laid over it, so the screen shows what will change. */
fun stagedView(config: UciConfig, edits: List<EditorEdit>): List<SectionView> {
    val sections = config.sections.map { section ->
        val options = section.options.map { (option, value) -> OptionView(option, value, value, value, false, ItemState.SAME) } +
            section.lists.map { (option, values) -> listOption(option, values, ItemState.SAME) }
        SectionView(section.name, section.type, section.anonymous, ItemState.SAME, options)
    }.toMutableList()

    fun update(name: String, change: (SectionView) -> SectionView?) {
        val index = sections.indexOfFirst { it.name == name }
        if (index < 0) return
        val updated = change(sections[index])
        if (updated == null) sections.removeAt(index) else sections[index] = updated
    }

    fun setOption(section: String, option: String, new: String, editValue: String, isList: Boolean) = update(section) { s ->
        val existing = s.options.find { it.option == option }
        val view = when {
            existing == null -> OptionView(option, null, new, editValue, isList, ItemState.ADDED)
            existing.old == null -> existing.copy(new = new, editValue = editValue, isList = isList)
            else -> existing.copy(new = new, editValue = editValue, isList = isList, state = ItemState.CHANGED)
        }
        s.copy(options = if (existing == null) s.options + view else s.options.map { if (it.option == option) view else it })
    }

    edits.forEach { edit ->
        when (edit) {
            is EditorEdit.SetOption -> setOption(edit.section, edit.option, edit.value, edit.value, false)
            is EditorEdit.SetList -> setOption(edit.section, edit.option, edit.values.joinToString(", "), edit.values.joinToString("\n"), true)
            is EditorEdit.DeleteOption -> update(edit.section) { s ->
                s.copy(
                    options = s.options.mapNotNull {
                        when {
                            it.option != edit.option -> it
                            it.old == null -> null
                            else -> it.copy(state = ItemState.REMOVED)
                        }
                    },
                )
            }
            is EditorEdit.AddSection -> sections += SectionView(edit.name, edit.type, false, ItemState.ADDED, emptyList())
            is EditorEdit.DeleteSection -> update(edit.section) { s -> if (s.state == ItemState.ADDED) null else s.copy(state = ItemState.REMOVED) }
        }
    }
    return sections
}

private fun listOption(option: String, values: List<String>, state: ItemState): OptionView {
    val shown = values.joinToString(", ")
    return OptionView(option, shown, shown, values.joinToString("\n"), true, state)
}
