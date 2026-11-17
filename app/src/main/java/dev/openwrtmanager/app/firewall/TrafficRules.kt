package dev.openwrtmanager.app.firewall

import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.uci.UciBatch

/** Turning traffic rules on and off, and removing them. New rules are written in the config editor (4d). */
object TrafficRules {
    private const val F = "firewall"

    /** Empty when the rule is already in that state. */
    fun setEnabled(rule: TrafficRule, enabled: Boolean): ChangeSet {
        val batch = UciBatch()
        if (enabled != rule.enabled) {
            if (enabled) batch.delete(F, rule.section, "enabled") else batch.set(F, rule.section, "enabled", "0")
        }
        val word = if (enabled) "Turn on" else "Turn off"
        return ChangeSet.of("$word rule", listOf("$word ${rule.name}: ${rule.description}"), batch)
    }

    /** Later sections first: deleting an anonymous section can renumber the ones after it. */
    fun remove(rules: List<TrafficRule>): ChangeSet {
        val batch = UciBatch()
        rules.sortedByDescending { it.index }.forEach { batch.delete(F, it.section) }
        val title = if (rules.size == 1) "Remove rule" else "Remove rules"
        return ChangeSet.of(title, rules.map { "Remove ${it.name}: ${it.description}" }, batch)
    }
}
