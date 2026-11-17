package dev.openwrtmanager.app.connection

import dev.openwrtmanager.app.data.ProfileRepository
import dev.openwrtmanager.app.data.SecretStore
import dev.openwrtmanager.app.ssh.SshExecutor
import dev.openwrtmanager.app.ssh.SshTarget

/** Opens a connection of its own to any saved device. The caller closes it. */
fun interface ProfileSessions {
    /** Null when the profile or its secret is gone. */
    suspend fun open(profileId: Long): SshExecutor?
}

class StoredProfileSessions(
    private val profiles: ProfileRepository,
    private val secrets: SecretStore,
    private val executors: SshExecutorFactory,
) : ProfileSessions {
    override suspend fun open(profileId: Long): SshExecutor? {
        val profile = profiles.get(profileId) ?: return null
        val secret = secrets.get(profileId) ?: return null
        return executors.create(
            SshTarget(profile.host, profile.port, profile.username, profile.authType, secret, profile.hostKeyFingerprint),
        )
    }
}
