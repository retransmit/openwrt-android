package dev.openwrtmanager.app.ui.apply

import dev.openwrtmanager.app.apply.ApplyOutcome

data class OutcomeMessage(val title: String, val detail: String, val code: String? = null)

/** One honest sentence per outcome. "Unknown" must never be dressed up as success or failure. */
fun outcomeMessage(outcome: ApplyOutcome): OutcomeMessage = when (outcome) {
    ApplyOutcome.Applied -> OutcomeMessage("Applied", "The change is saved and active on the router.")
    ApplyOutcome.RolledBack -> OutcomeMessage(
        "Change undone",
        "The app could not reach the router again in time, so the router undid the change by itself. Nothing was lost.",
    )
    is ApplyOutcome.Rejected -> OutcomeMessage("Not applied", "The router refused the change. Nothing was changed.", outcome.reason)
    is ApplyOutcome.ForeignChanges -> OutcomeMessage(
        "Unsaved changes on the router",
        "Another tool, such as the web interface, has changes waiting to be saved. Save or discard them there first. " +
            "Nothing was changed.",
        outcome.pending,
    )
    is ApplyOutcome.Unknown -> OutcomeMessage(
        "Could not confirm",
        "The app could not find out whether the change was kept or undone. Check the router before changing anything else.",
    )
}

private val SECRET_LINE = Regex("""(\.(?:key|sae_password|password|wpa_psk)=)(.*)""")

/** Hides Wi-Fi passwords in `uci changes` text before it is shown on screen. */
fun maskSecrets(text: String): String = text.lineSequence().joinToString("\n") { line ->
    SECRET_LINE.replace(line) { it.groupValues[1] + "'(hidden)'" }
}
