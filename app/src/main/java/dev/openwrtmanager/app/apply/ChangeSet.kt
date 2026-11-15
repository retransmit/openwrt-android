package dev.openwrtmanager.app.apply

import dev.openwrtmanager.app.uci.UciBatch

/**
 * One reviewable change to router config.
 * [summary] is shown to the user in plain words and must never contain a password.
 */
data class ChangeSet(
    val title: String,
    val summary: List<String>,
    val batch: UciBatch,
    /** True when applying may briefly cut the phone off from the router (wireless, network, firewall). */
    val mayDropConnection: Boolean,
) {
    fun isEmpty(): Boolean = batch.isEmpty()

    override fun toString(): String = "ChangeSet($title, ${summary.size} lines, configs=${batch.configs})"

    companion object {
        private val DISRUPTIVE = setOf("wireless", "network", "firewall")

        fun of(title: String, summary: List<String>, batch: UciBatch): ChangeSet =
            ChangeSet(title, summary, batch, batch.configs.any { it in DISRUPTIVE })
    }
}

sealed interface ApplyPhase {
    data object Staging : ApplyPhase
    data object Committing : ApplyPhase
    data object Reconnecting : ApplyPhase
    data object Confirming : ApplyPhase
}

sealed interface ApplyOutcome {
    data object Applied : ApplyOutcome

    /** The router undid the change by itself, and that was verified. */
    data object RolledBack : ApplyOutcome

    /** Nothing was committed. */
    data class Rejected(val reason: String) : ApplyOutcome

    /** Another tool left uncommitted changes on the router. Nothing was touched. */
    data class ForeignChanges(val pending: String) : ApplyOutcome

    /** The app could not find out whether the change was kept. */
    data class Unknown(val error: Throwable) : ApplyOutcome
}

class ForeignChangesException(val pending: String) : Exception("the router has uncommitted changes from another tool")

interface Applier {
    /** Stages the change, returns `uci changes` text for review, then un-stages. Never commits. */
    suspend fun preview(change: ChangeSet): String

    suspend fun apply(change: ChangeSet, onPhase: (ApplyPhase) -> Unit = {}): ApplyOutcome
}
