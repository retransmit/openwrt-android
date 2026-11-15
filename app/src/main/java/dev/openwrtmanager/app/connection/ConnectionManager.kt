package dev.openwrtmanager.app.connection

import dev.openwrtmanager.app.data.Profile
import dev.openwrtmanager.app.data.ProfileRepository
import dev.openwrtmanager.app.data.SecretStore
import dev.openwrtmanager.app.router.RouterClient
import dev.openwrtmanager.app.router.RouterError
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.ssh.SshExecutor
import dev.openwrtmanager.app.ssh.SshTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

sealed interface ConnectionState {
    data object NoProfile : ConnectionState
    data class Connecting(val profile: Profile) : ConnectionState
    data class Online(val profile: Profile, val router: RouterClient) : ConnectionState
    data class Offline(val profile: Profile, val error: Throwable) : ConnectionState
}

fun interface SshExecutorFactory {
    fun create(target: SshTarget): SshExecutor
}

/**
 * Owns the connection to the active profile. Reconnects with backoff after transport failures,
 * and waits for [retryNow] after failures that retrying cannot fix.
 */
class ConnectionManager(
    private val profiles: ProfileRepository,
    private val secrets: SecretStore,
    private val executorFactory: SshExecutorFactory,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.NoProfile)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val failures = Channel<Throwable>(Channel.CONFLATED)
    private val retries = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            // Re-serve whenever the active profile changes or its stored details are edited.
            combine(profiles.observeActiveId(), profiles.observeAll()) { id, all -> all.firstOrNull { it.id == id } }
                .distinctUntilChanged()
                .collectLatest { profile -> serve(profile?.id) }
        }
    }

    /** Reconnects immediately, re-reading the profile and its secret. */
    fun retryNow() {
        retries.trySend(Unit)
    }

    /** Called by pollers when a call throws; moves Online to Offline and starts the backoff. */
    fun reportFailure(error: Throwable) {
        if (_state.value is ConnectionState.Online) failures.trySend(error)
    }

    private suspend fun serve(profileId: Long?) {
        if (profileId == null) {
            _state.value = ConnectionState.NoProfile
            return
        }
        failures.tryReceive()
        retries.tryReceive()
        var failedAttempts = 0
        while (true) {
            val profile = profiles.get(profileId)
            if (profile == null) {
                _state.value = ConnectionState.NoProfile
                return
            }
            val ended = connectAndHold(profile)
            // A connection that worked means the device is reachable again: the next drop starts at the short delay.
            if (ended.wasOnline) failedAttempts = 0
            val error = ended.error
            if (error == null) {
                // retryNow while online: reconnect straight away.
                failedAttempts = 0
                continue
            }
            _state.value = ConnectionState.Offline(profile, error)
            if (error.isRetryable()) {
                val delayMs = BACKOFF_MS.getOrElse(failedAttempts) { BACKOFF_MS.last() }
                failedAttempts++
                withTimeoutOrNull(delayMs) { retries.receive() }
            } else {
                failedAttempts = 0
                retries.receive()
            }
        }
    }

    /**
     * Connects, then suspends while the connection is in use. Returns the error that ended it,
     * or null when the user asked for a reconnect.
     */
    private suspend fun connectAndHold(profile: Profile): Ended {
        _state.value = ConnectionState.Connecting(profile)
        yield()
        val secret = try {
            secrets.get(profile.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Ended(e, wasOnline = false)
        } ?: return Ended(SshError.AuthFailed(), wasOnline = false)
        val executor = executorFactory.create(
            SshTarget(
                host = profile.host,
                port = profile.port,
                username = profile.username,
                authType = profile.authType,
                secret = secret,
                trustedFingerprint = profile.hostKeyFingerprint,
            ),
        )
        try {
            val router = RouterClient(executor)
            var wasOnline = false
            val error = try {
                router.systemInfo()
                wasOnline = true
                _state.value = ConnectionState.Online(profile, router)
                select<Throwable?> {
                    failures.onReceive { it }
                    retries.onReceive { null }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything unexpected is shown as Offline and retried; it must never take the app down.
                e
            }
            if (!wasOnline) failures.tryReceive()
            return Ended(error, wasOnline)
        } finally {
            withContext(NonCancellable) { runCatching { executor.close() } }
        }
    }

    /** How a connection attempt ended. [error] is null when the user asked for a reconnect. */
    private class Ended(val error: Throwable?, val wasOnline: Boolean)

    private fun Throwable.isRetryable(): Boolean = when (this) {
        is SshError.AuthFailed, is SshError.HostKeyChanged, is SshError.HostKeyUnknown -> false
        is RouterError.NotOpenWrt -> false
        else -> true
    }

    private companion object {
        val BACKOFF_MS = listOf(2_000L, 4_000L, 8_000L, 15_000L)
    }
}
