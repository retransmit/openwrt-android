package dev.openwrtmanager.app.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openwrtmanager.app.testing.InMemorySecretStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProfileRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var secrets: InMemorySecretStore
    private lateinit var repo: ProfileRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        secrets = InMemorySecretStore()
        val settings = PreferenceDataStoreFactory.create { tmp.newFile("settings.preferences_pb") }
        repo = RoomProfileRepository(db.profileDao(), secrets, settings)
    }

    @After
    fun tearDown() = db.close()

    private fun profile(name: String) =
        Profile(name = name, host = "192.168.1.1", authType = AuthType.PASSWORD)

    @Test
    fun saved_profile_is_observed_with_defaults() = runBlocking {
        val id = repo.save(profile("Home"))
        val all = repo.observeAll().first()
        assertEquals(1, all.size)
        assertEquals(id, all[0].id)
        assertEquals(22, all[0].port)
        assertEquals("root", all[0].username)
        assertNull(all[0].hostKeyFingerprint)
    }

    @Test
    fun profiles_sort_by_name_ignoring_case() = runBlocking {
        repo.save(profile("gamma"))
        repo.save(profile("Beta"))
        repo.save(profile("alpha"))
        assertEquals(listOf("alpha", "Beta", "gamma"), repo.observeAll().first().map { it.name })
    }

    @Test
    fun saving_existing_profile_updates_and_keeps_id() = runBlocking {
        val id = repo.save(profile("Home"))
        val again = repo.save(repo.get(id)!!.copy(name = "Home 2", host = "10.0.0.1"))
        assertEquals(id, again)
        assertEquals(listOf("Home 2"), repo.observeAll().first().map { it.name })
        assertEquals("10.0.0.1", repo.get(id)!!.host)
    }

    @Test
    fun delete_removes_profile_and_its_secret() = runBlocking {
        val id = repo.save(profile("Home"))
        secrets.put(id, "hunter2")
        repo.delete(id)
        assertNull(repo.get(id))
        assertNull(secrets.get(id))
    }

    @Test
    fun fingerprint_can_be_set_and_cleared() = runBlocking {
        val id = repo.save(profile("Home"))
        repo.setFingerprint(id, "SHA256:abc")
        assertEquals("SHA256:abc", repo.get(id)!!.hostKeyFingerprint)
        repo.setFingerprint(id, null)
        assertNull(repo.get(id)!!.hostKeyFingerprint)
    }

    @Test
    fun active_profile_round_trips() = runBlocking {
        assertNull(repo.observeActiveId().first())
        val id = repo.save(profile("Home"))
        repo.setActive(id)
        assertEquals(id, repo.observeActiveId().first())
    }

    @Test
    fun deleting_active_profile_clears_active() = runBlocking {
        val id = repo.save(profile("Home"))
        repo.setActive(id)
        repo.delete(id)
        assertNull(repo.observeActiveId().first())
    }

    @Test
    fun deleting_other_profile_keeps_active() = runBlocking {
        val a = repo.save(profile("A"))
        val b = repo.save(profile("B"))
        repo.setActive(a)
        repo.delete(b)
        assertEquals(a, repo.observeActiveId().first())
    }
}
