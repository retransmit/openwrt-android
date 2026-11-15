package dev.openwrtmanager.app.connection

import app.cash.turbine.test
import dev.openwrtmanager.app.data.AuthType
import dev.openwrtmanager.app.data.Profile
import dev.openwrtmanager.app.router.RouterError
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.ssh.SshTarget
import dev.openwrtmanager.app.testing.FakeProfileRepository
import dev.openwrtmanager.app.testing.FakeSshExecutor
import dev.openwrtmanager.app.testing.InMemorySecretStore
import dev.openwrtmanager.app.testing.healthyRouter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {
    private val secrets = InMemorySecretStore()
    private val profiles = FakeProfileRepository(secrets)
    private val created = mutableListOf<FakeSshExecutor>()
    private val targets = mutableListOf<SshTarget>()

    /** Errors to throw from the next executors the factory creates, in order. Empty means healthy. */
    private val upcomingFailures = ArrayDeque<Exception>()

    private fun TestScope.manager() = ConnectionManager(
        profiles = profiles,
        secrets = secrets,
        executorFactory = { target ->
            targets += target
            val failure = upcomingFailures.removeFirstOrNull()
            val executor = when (failure) {
                null -> healthyRouter()
                is SshError -> healthyRouter().apply { failWith = failure }
                is UnsupportedOperationException -> healthyRouter().apply { crashWith = failure }
                else -> FakeSshExecutor() // answers 127 to everything: not an OpenWrt device
            }
            created += executor
            executor
        },
        scope = backgroundScope,
    )

    private suspend fun addProfile(name: String, activate: Boolean = true): Long {
        val id = profiles.save(
            Profile(name = name, host = "10.0.0.$name", authType = AuthType.PASSWORD, hostKeyFingerprint = "SHA256:fp"),
        )
        secrets.put(id, "pw-$name")
        if (activate) profiles.setActive(id)
        return id
    }

    private fun ConnectionManager.online() = state.value as ConnectionState.Online
    private fun ConnectionManager.offline() = state.value as ConnectionState.Offline

    @Test
    fun no_active_profile_is_no_profile() = runTest {
        val manager = manager()
        runCurrent()
        assertEquals(ConnectionState.NoProfile, manager.state.value)
        assertTrue(created.isEmpty())
    }

    @Test
    fun active_profile_goes_connecting_then_online() = runTest {
        val manager = manager()
        manager.state.test {
            assertEquals(ConnectionState.NoProfile, awaitItem())
            addProfile("1")
            assertTrue(awaitItem() is ConnectionState.Connecting)
            val online = awaitItem() as ConnectionState.Online
            assertEquals("1", online.profile.name)
            assertEquals("Home-Router", online.router.systemInfo().hostname)
        }
        assertEquals(SshTarget("10.0.0.1", 22, "root", AuthType.PASSWORD, "pw-1", "SHA256:fp"), targets.single())
    }

    @Test
    fun switching_profile_closes_old_executor() = runTest {
        val manager = manager()
        addProfile("1")
        runCurrent()
        val second = addProfile("2")
        runCurrent()
        assertTrue(created[0].closed)
        assertFalse(created[1].closed)
        assertEquals(second, manager.online().profile.id)
    }

    @Test
    fun clearing_active_profile_closes_and_reports_no_profile() = runTest {
        val manager = manager()
        addProfile("1")
        runCurrent()
        profiles.setActive(null)
        runCurrent()
        assertEquals(ConnectionState.NoProfile, manager.state.value)
        assertTrue(created[0].closed)
    }

    @Test
    fun reported_failure_goes_offline_then_reconnects_after_two_seconds() = runTest {
        val manager = manager()
        addProfile("1")
        runCurrent()
        manager.reportFailure(SshError.Disconnected())
        runCurrent()
        assertTrue(manager.offline().error is SshError.Disconnected)
        assertTrue(created[0].closed)
        advanceTimeBy(1_999)
        runCurrent()
        assertTrue(manager.state.value is ConnectionState.Offline)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(manager.state.value is ConnectionState.Online)
        assertEquals(2, created.size)
    }

    @Test
    fun failed_connects_back_off_2_4_8_then_15_seconds() = runTest {
        repeat(5) { upcomingFailures += SshError.Unreachable() }
        val manager = manager()
        addProfile("1")
        runCurrent()
        assertEquals(1, created.size)
        for ((attempt, waitMs) in listOf(2_000L, 4_000L, 8_000L, 15_000L, 15_000L).withIndex()) {
            advanceTimeBy(waitMs - 1)
            runCurrent()
            assertEquals("still waiting before attempt ${attempt + 2}", attempt + 1, created.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(attempt + 2, created.size)
        }
        assertTrue(manager.state.value is ConnectionState.Online)
    }

    @Test
    fun auth_failure_does_not_retry_until_asked() = runTest {
        upcomingFailures += SshError.AuthFailed()
        val manager = manager()
        addProfile("1")
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(manager.offline().error is SshError.AuthFailed)
        assertEquals(1, created.size)
        manager.retryNow()
        runCurrent()
        assertTrue(manager.state.value is ConnectionState.Online)
    }

    private suspend fun TestScope.assertNoRetryAfter(failure: Exception) {
        upcomingFailures += failure
        val manager = manager()
        addProfile("x")
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(manager.state.value is ConnectionState.Offline)
        assertEquals(1, created.size)
    }

    @Test
    fun changed_host_key_does_not_retry() = runTest {
        assertNoRetryAfter(SshError.HostKeyChanged("a", "b"))
    }

    @Test
    fun unknown_host_key_does_not_retry() = runTest {
        assertNoRetryAfter(SshError.HostKeyUnknown("a"))
    }

    @Test
    fun non_openwrt_host_does_not_retry() = runTest {
        assertNoRetryAfter(IllegalStateException("marker: use an empty fake so ubus is missing"))
    }

    @Test
    fun non_openwrt_host_reports_not_openwrt() = runTest {
        upcomingFailures += IllegalStateException("marker")
        val manager = manager()
        addProfile("1")
        runCurrent()
        assertTrue(manager.offline().error is RouterError.NotOpenWrt)
    }

    @Test
    fun missing_secret_is_auth_failed_without_connecting() = runTest {
        val manager = manager()
        val id = addProfile("1", activate = false)
        secrets.remove(id)
        profiles.setActive(id)
        runCurrent()
        assertTrue(manager.offline().error is SshError.AuthFailed)
        assertTrue(created.isEmpty())
    }

    @Test
    fun retry_now_while_online_reconnects_with_fresh_profile_data() = runTest {
        val manager = manager()
        val id = addProfile("1")
        runCurrent()
        profiles.setFingerprint(id, "SHA256:new")
        manager.retryNow()
        runCurrent()
        assertTrue(manager.state.value is ConnectionState.Online)
        assertEquals("SHA256:new", targets.last().trustedFingerprint)
        assertTrue(created[0].closed)
    }

    @Test
    fun editing_the_active_profile_reconnects_with_new_details() = runTest {
        val manager = manager()
        val id = addProfile("1")
        runCurrent()
        profiles.save(profiles.get(id)!!.copy(host = "10.9.9.9"))
        runCurrent()
        assertTrue(manager.state.value is ConnectionState.Online)
        assertEquals("10.9.9.9", targets.last().host)
        assertTrue(created[0].closed)
        assertEquals(2, created.size)
    }

    @Test
    fun editing_another_profile_does_not_reconnect() = runTest {
        manager()
        addProfile("1")
        val other = addProfile("2", activate = false)
        runCurrent()
        profiles.save(profiles.get(other)!!.copy(host = "10.9.9.9"))
        runCurrent()
        assertEquals(1, created.size)
    }

    @Test
    fun backoff_starts_over_after_a_successful_connection() = runTest {
        repeat(3) { upcomingFailures += SshError.Unreachable() }
        val manager = manager()
        addProfile("1")
        runCurrent()
        advanceTimeBy(2_000); runCurrent()
        advanceTimeBy(4_000); runCurrent()
        advanceTimeBy(8_000); runCurrent()
        assertTrue(manager.state.value is ConnectionState.Online)
        manager.reportFailure(SshError.Disconnected())
        runCurrent()
        assertTrue(manager.state.value is ConnectionState.Offline)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue("reconnects after 2 s, not 15 s", manager.state.value is ConnectionState.Online)
    }

    @Test
    fun unexpected_exception_goes_offline_and_retries_instead_of_crashing() = runTest {
        upcomingFailures += UnsupportedOperationException("keystore exploded")
        val manager = manager()
        addProfile("1")
        runCurrent()
        assertTrue(manager.offline().error is UnsupportedOperationException)
        assertTrue(created[0].closed)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(manager.state.value is ConnectionState.Online)
    }
}
