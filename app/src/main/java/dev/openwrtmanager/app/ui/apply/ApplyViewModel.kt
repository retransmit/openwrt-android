package dev.openwrtmanager.app.ui.apply

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openwrtmanager.app.apply.Applier
import dev.openwrtmanager.app.apply.ApplyOutcome
import dev.openwrtmanager.app.apply.ApplyPhase
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.apply.ForeignChangesException
import dev.openwrtmanager.app.apply.HealthProblem
import dev.openwrtmanager.app.apply.SafeApply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** An applier on its own connection, so applying is independent of whatever else is polling the device. */
class ApplySession(
    val applier: Applier,
    /** Checks the interfaces a change created, once it is applied. */
    val health: suspend (List<String>) -> List<HealthProblem> = { emptyList() },
    private val onClose: suspend () -> Unit,
) {
    suspend fun close() = onClose()
}

fun interface ApplySessionFactory {
    /** Null when there is no device to talk to. */
    suspend fun open(): ApplySession?
}

sealed interface ApplyUi {
    data object Idle : ApplyUi
    data class Review(val change: ChangeSet, val rawChanges: String?, val loadingChanges: Boolean) : ApplyUi
    data class Working(val phase: ApplyPhase, val secondsLeft: Int) : ApplyUi
    data class Done(val outcome: ApplyOutcome, val problems: List<HealthProblem> = emptyList()) : ApplyUi
}

/** Drives the review, apply and result steps for one change at a time. */
@HiltViewModel
class ApplyViewModel @Inject constructor(
    private val sessions: ApplySessionFactory,
) : ViewModel() {
    private val _ui = MutableStateFlow<ApplyUi>(ApplyUi.Idle)
    val ui: StateFlow<ApplyUi> = _ui.asStateFlow()

    private var onApplied: () -> Unit = {}

    fun review(change: ChangeSet, onApplied: () -> Unit) {
        if (change.isEmpty() || _ui.value is ApplyUi.Working) return
        this.onApplied = onApplied
        _ui.value = ApplyUi.Review(change, rawChanges = null, loadingChanges = true)
        viewModelScope.launch {
            val raw: String? = try {
                withSession { it.applier.preview(change) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForeignChangesException) {
                _ui.value = ApplyUi.Done(ApplyOutcome.ForeignChanges(e.pending))
                return@launch
            } catch (e: Exception) {
                // The exact text is a nice-to-have; the plain summary is still shown.
                null
            }
            _ui.update { current ->
                if (current is ApplyUi.Review && current.change === change) {
                    current.copy(rawChanges = raw, loadingChanges = false)
                } else {
                    current
                }
            }
        }
    }

    fun confirm() {
        val review = _ui.value as? ApplyUi.Review ?: return
        // The preview stages and un-stages on the router; applying must not overlap it.
        if (review.loadingChanges) return
        _ui.value = ApplyUi.Working(ApplyPhase.Staging, ROLLBACK_SECONDS)
        viewModelScope.launch {
            // Counts down once and stops: there is nothing left to count after zero.
            val ticker = launch {
                repeat(ROLLBACK_SECONDS) {
                    delay(1_000)
                    _ui.update { if (it is ApplyUi.Working) it.copy(secondsLeft = (it.secondsLeft - 1).coerceAtLeast(0)) else it }
                }
            }
            var problems = emptyList<HealthProblem>()
            val outcome = try {
                withSession { session ->
                    val result = session.applier.apply(review.change) { phase ->
                        _ui.update { if (it is ApplyUi.Working) it.copy(phase = phase) else it }
                    }
                    ticker.cancel()
                    if (result == ApplyOutcome.Applied && review.change.checkInterfaces.isNotEmpty()) {
                        problems = runCatching { session.health(review.change.checkInterfaces) }.getOrDefault(emptyList())
                    }
                    result
                } ?: ApplyOutcome.Rejected("Not connected to the device")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ApplyOutcome.Unknown(e)
            } finally {
                ticker.cancel()
            }
            _ui.value = ApplyUi.Done(outcome, problems)
            if (outcome == ApplyOutcome.Applied) onApplied()
        }
    }

    /** Closes the sheet. Ignored while a change is being applied: that must run to its end. */
    fun dismiss() {
        if (_ui.value !is ApplyUi.Working) _ui.value = ApplyUi.Idle
    }

    private suspend fun <T> withSession(block: suspend (ApplySession) -> T): T? {
        val session = sessions.open() ?: return null
        try {
            return block(session)
        } finally {
            withContext(NonCancellable) { runCatching { session.close() } }
        }
    }

    private companion object {
        const val ROLLBACK_SECONDS = SafeApply.DEFAULT_ROLLBACK_SECONDS
    }
}
