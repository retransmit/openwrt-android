package dev.openwrtmanager.app.testing

import dev.openwrtmanager.app.data.SecretStore

class InMemorySecretStore : SecretStore {
    val values = mutableMapOf<Long, String>()

    override suspend fun put(profileId: Long, secret: String) {
        values[profileId] = secret
    }

    override suspend fun get(profileId: Long): String? = values[profileId]

    override suspend fun remove(profileId: Long) {
        values.remove(profileId)
    }
}
