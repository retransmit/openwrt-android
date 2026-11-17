package dev.openwrtmanager.app.ui.apply

import dev.openwrtmanager.app.apply.Applier
import dev.openwrtmanager.app.apply.ApplyOutcome
import dev.openwrtmanager.app.apply.ApplyPhase
import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.apply.ForeignChangesException
import dev.openwrtmanager.app.testing.MainDispatcherRule
import dev.openwrtmanager.app.uci.UciBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApplyViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainRule = MainDispatcherRule(dispatcher)

    private val change = ChangeSet("Rename", listOf("Rename \"A\" to \"B\""), UciBatch().set("wireless", "x", "ssid", "B"), true)

    private var previewResult: () -> String = { "wireless.x.ssid='B'" }
    private var outcome = CompletableDeferred<ApplyOutcome>()
    private var applyCalls = 0
    private var sessionsOpened = 0
    private var sessionsClosed = 0
    private var online = true
    private var phaseSink: ((ApplyPhase) -> Unit)? = null

    private val applier = object : Applier {
        override suspend fun preview(change: ChangeSet): String = previewResult()

        override suspend fun apply(change: ChangeSet, onPhase: (ApplyPhase) -> Unit): ApplyOutcome {
            applyCalls++
            phaseSink = onPhase
            onPhase(ApplyPhase.Staging)
            return outcome.await()
        }
    }

    private fun viewModel() = ApplyViewModel {
        if (!online) null else {
            sessionsOpened++
            ApplySession(applier) { sessionsClosed++ }
        }
    }

    @Test
    fun starts_idle() = runTest(dispatcher) {
        assertEquals(ApplyUi.Idle, viewModel().ui.value)
    }

    @Test
    fun review_shows_the_change_then_loads_exact_changes() = runTest(dispatcher) {
        val vm = viewModel()
        vm.review(change) {}
        assertEquals(ApplyUi.Review(change, rawChanges = null, loadingChanges = true), vm.ui.value)
        runCurrent()
        assertEquals(ApplyUi.Review(change, rawChanges = "wireless.x.ssid='B'", loadingChanges = false), vm.ui.value)
        assertEquals("preview session is closed again", 1, sessionsClosed)
    }

    @Test
    fun failed_preview_still_allows_review() = runTest(dispatcher) {
        previewResult = { throw IllegalStateException("boom") }
        val vm = viewModel()
        vm.review(change) {}
        runCurrent()
        assertEquals(ApplyUi.Review(change, rawChanges = null, loadingChanges = false), vm.ui.value)
    }

    @Test
    fun foreign_changes_found_during_preview_end_the_flow() = runTest(dispatcher) {
        previewResult = { throw ForeignChangesException("firewall.x.name='y'") }
        val vm = viewModel()
        vm.review(change) {}
        runCurrent()
        assertEquals(ApplyUi.Done(ApplyOutcome.ForeignChanges("firewall.x.name='y'")), vm.ui.value)
    }

    @Test
    fun empty_change_is_not_reviewed() = runTest(dispatcher) {
        val vm = viewModel()
        vm.review(ChangeSet("Nothing", emptyList(), UciBatch(), false)) {}
        runCurrent()
        assertEquals(ApplyUi.Idle, vm.ui.value)
    }

    @Test
    fun confirm_shows_progress_and_ends_applied() = runTest(dispatcher) {
        var applied = 0
        val vm = viewModel()
        vm.review(change) { applied++ }
        runCurrent()
        vm.confirm()
        runCurrent()
        assertEquals(ApplyUi.Working(ApplyPhase.Staging, secondsLeft = 90), vm.ui.value)
        phaseSink!!(ApplyPhase.Committing)
        assertEquals(ApplyPhase.Committing, (vm.ui.value as ApplyUi.Working).phase)
        outcome.complete(ApplyOutcome.Applied)
        runCurrent()
        assertEquals(ApplyUi.Done(ApplyOutcome.Applied), vm.ui.value)
        assertEquals(1, applied)
        assertEquals("preview and apply sessions are both closed", 2, sessionsClosed)
    }

    @Test
    fun countdown_ticks_once_a_second_and_stops_at_zero() = runTest(dispatcher) {
        val vm = viewModel()
        vm.review(change) {}
        runCurrent()
        vm.confirm()
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(87, (vm.ui.value as ApplyUi.Working).secondsLeft)
        advanceTimeBy(200_000)
        runCurrent()
        assertEquals(0, (vm.ui.value as ApplyUi.Working).secondsLeft)
    }

    @Test
    fun dismiss_is_ignored_while_working() = runTest(dispatcher) {
        val vm = viewModel()
        vm.review(change) {}
        runCurrent()
        vm.confirm()
        runCurrent()
        vm.dismiss()
        assertTrue(vm.ui.value is ApplyUi.Working)
        outcome.complete(ApplyOutcome.RolledBack)
        runCurrent()
        vm.dismiss()
        assertEquals(ApplyUi.Idle, vm.ui.value)
    }

    @Test
    fun confirm_twice_applies_once() = runTest(dispatcher) {
        val vm = viewModel()
        vm.review(change) {}
        runCurrent()
        vm.confirm()
        vm.confirm()
        runCurrent()
        assertEquals(1, applyCalls)
    }

    @Test
    fun every_outcome_is_passed_through_and_only_applied_notifies() = runTest(dispatcher) {
        val outcomes = listOf(
            ApplyOutcome.RolledBack,
            ApplyOutcome.Rejected("no"),
            ApplyOutcome.ForeignChanges("x"),
            ApplyOutcome.Unknown(IllegalStateException()),
        )
        for (expected in outcomes) {
            var applied = false
            outcome = CompletableDeferred()
            val vm = viewModel()
            vm.review(change) { applied = true }
            runCurrent()
            vm.confirm()
            runCurrent()
            outcome.complete(expected)
            runCurrent()
            assertEquals(ApplyUi.Done(expected), vm.ui.value)
            assertFalse(applied)
        }
    }

    @Test
    fun not_connected_is_reported_without_applying() = runTest(dispatcher) {
        val vm = viewModel()
        vm.review(change) {}
        runCurrent()
        online = false
        vm.confirm()
        runCurrent()
        assertTrue((vm.ui.value as ApplyUi.Done).outcome is ApplyOutcome.Rejected)
        assertEquals(0, applyCalls)
    }

    @Test
    fun unexpected_failure_while_applying_is_unknown_not_a_crash() = runTest(dispatcher) {
        val vm = viewModel()
        vm.review(change) {}
        runCurrent()
        vm.confirm()
        runCurrent()
        outcome.completeExceptionally(IllegalStateException("bug"))
        runCurrent()
        assertTrue((vm.ui.value as ApplyUi.Done).outcome is ApplyOutcome.Unknown)
        assertNull(null)
    }

    @Test
    fun apply_is_ignored_until_the_preview_has_finished() = runTest(dispatcher) {
        val vm = viewModel()
        vm.review(change) {}
        vm.confirm()
        runCurrent()
        assertTrue("still reviewing: the preview must not overlap the apply", vm.ui.value is ApplyUi.Review)
        assertEquals(0, applyCalls)
        vm.confirm()
        runCurrent()
        assertEquals(1, applyCalls)
    }
}
