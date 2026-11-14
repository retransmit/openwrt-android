package dev.openwrtmanager.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface ProfileRepository {
    /** All profiles ordered by name, ignoring case. */
    fun observeAll(): Flow<List<Profile>>

    suspend fun get(id: Long): Profile?

    /** Inserts when id is 0, otherwise updates. Returns the profile id. */
    suspend fun save(profile: Profile): Long

    /** Deletes the profile and its secret. Clears the active profile if it was this one. */
    suspend fun delete(id: Long)

    suspend fun setFingerprint(id: Long, fingerprint: String?)

    fun observeActiveId(): Flow<Long?>

    suspend fun setActive(id: Long?)
}

class RoomProfileRepository(
    private val dao: ProfileDao,
    private val secrets: SecretStore,
    private val settings: DataStore<Preferences>,
) : ProfileRepository {

    override fun observeAll(): Flow<List<Profile>> = dao.observeAll()

    override suspend fun get(id: Long): Profile? = dao.get(id)

    override suspend fun save(profile: Profile): Long =
        if (profile.id == 0L) {
            dao.insert(profile)
        } else {
            dao.update(profile)
            profile.id
        }

    override suspend fun delete(id: Long) {
        dao.delete(id)
        secrets.remove(id)
        if (observeActiveId().first() == id) setActive(null)
    }

    override suspend fun setFingerprint(id: Long, fingerprint: String?) = dao.setFingerprint(id, fingerprint)

    override fun observeActiveId(): Flow<Long?> = settings.data.map { it[ACTIVE_ID] }.distinctUntilChanged()

    override suspend fun setActive(id: Long?) {
        settings.edit { prefs ->
            if (id == null) prefs.remove(ACTIVE_ID) else prefs[ACTIVE_ID] = id
        }
    }

    private companion object {
        val ACTIVE_ID = longPreferencesKey("active_profile_id")
    }
}
