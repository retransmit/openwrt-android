package dev.openwrtmanager.app.testing

import dev.openwrtmanager.app.data.Profile
import dev.openwrtmanager.app.data.ProfileRepository
import dev.openwrtmanager.app.data.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeProfileRepository(private val secrets: SecretStore = InMemorySecretStore()) : ProfileRepository {
    private val profiles = MutableStateFlow<List<Profile>>(emptyList())
    private val active = MutableStateFlow<Long?>(null)
    private var nextId = 1L

    override fun observeAll(): Flow<List<Profile>> =
        profiles.map { list -> list.sortedBy { it.name.lowercase() } }

    override suspend fun get(id: Long): Profile? = profiles.value.firstOrNull { it.id == id }

    override suspend fun save(profile: Profile): Long {
        val saved = if (profile.id == 0L) profile.copy(id = nextId++) else profile
        profiles.value = profiles.value.filterNot { it.id == saved.id } + saved
        return saved.id
    }

    override suspend fun delete(id: Long) {
        profiles.value = profiles.value.filterNot { it.id == id }
        secrets.remove(id)
        if (active.value == id) active.value = null
    }

    override suspend fun setFingerprint(id: Long, fingerprint: String?) {
        profiles.value = profiles.value.map { if (it.id == id) it.copy(hostKeyFingerprint = fingerprint) else it }
    }

    override fun observeActiveId(): Flow<Long?> = active

    override suspend fun setActive(id: Long?) {
        active.value = id
    }
}
