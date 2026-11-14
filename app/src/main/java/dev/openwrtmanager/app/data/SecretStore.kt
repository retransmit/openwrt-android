package dev.openwrtmanager.app.data

/** Holds one secret per profile: the password, or the private key in PEM form. */
interface SecretStore {
    suspend fun put(profileId: Long, secret: String)

    suspend fun get(profileId: Long): String?

    suspend fun remove(profileId: Long)
}
