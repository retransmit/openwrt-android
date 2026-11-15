package dev.openwrtmanager.app.uci

import dev.openwrtmanager.app.ssh.shellQuote

/**
 * Builds the text fed to `uci batch`. It only stages changes: committing is SafeApply's job.
 * Identifiers never come from user text and are checked; values are always quoted.
 */
class UciBatch {
    private val lines = mutableListOf<String>()
    private val touched = linkedSetOf<String>()

    val configs: Set<String> get() = touched.toSet()

    fun isEmpty(): Boolean = lines.isEmpty()

    /** Creates or retypes a named section. */
    fun section(config: String, section: String, type: String): UciBatch {
        require(TYPE.matches(type)) { "bad section type: $type" }
        return add(config, "set ${id(config)}.${id(section)}=$type")
    }

    fun set(config: String, section: String, option: String, value: String): UciBatch =
        add(config, "set ${id(config)}.${id(section)}.${id(option)}=${quote(value)}")

    fun addList(config: String, section: String, option: String, value: String): UciBatch =
        add(config, "add_list ${id(config)}.${id(section)}.${id(option)}=${quote(value)}")

    /** Deletes an option, or the whole section when [option] is null. */
    fun delete(config: String, section: String, option: String? = null): UciBatch {
        val target = "${id(config)}.${id(section)}" + (option?.let { ".${id(it)}" } ?: "")
        return add(config, "delete $target")
    }

    /** One command per line, with no commit. */
    fun script(): String = lines.joinToString("\n")

    /** Appends everything from [other]. */
    fun append(other: UciBatch): UciBatch {
        lines += other.lines
        touched += other.touched
        return this
    }

    private fun add(config: String, line: String): UciBatch {
        lines += line
        touched += config
        return this
    }

    private fun id(value: String): String {
        require(IDENTIFIER.matches(value)) { "bad uci identifier: $value" }
        return value
    }

    private fun quote(value: String): String {
        require(value.none { it == '\n' || it == '\r' || it == '\u0000' }) { "uci values cannot span lines" }
        return shellQuote(value)
    }

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9_]+")
        val TYPE = Regex("[A-Za-z0-9_-]+")
    }
}
