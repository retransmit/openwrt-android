package dev.openwrtmanager.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Behaviour every SecretStore must have. Subclasses supply the implementation under test. */
abstract class SecretStoreContract {
    abstract fun createStore(): SecretStore

    @Test
    fun missing_secret_is_null() = runBlocking {
        assertNull(createStore().get(1))
    }

    @Test
    fun password_round_trips_including_non_ascii() = runBlocking {
        val store = createStore()
        store.put(1, "pä55 wörd 📶 'quoted'")
        assertEquals("pä55 wörd 📶 'quoted'", store.get(1))
    }

    @Test
    fun multi_line_pem_round_trips() = runBlocking {
        val pem = "-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIEIA==\n-----END PRIVATE KEY-----\n"
        val store = createStore()
        store.put(7, pem)
        assertEquals(pem, store.get(7))
    }

    @Test
    fun secrets_are_per_profile() = runBlocking {
        val store = createStore()
        store.put(1, "one")
        store.put(2, "two")
        assertEquals("one", store.get(1))
        assertEquals("two", store.get(2))
    }

    @Test
    fun put_overwrites() = runBlocking {
        val store = createStore()
        store.put(1, "old")
        store.put(1, "new")
        assertEquals("new", store.get(1))
    }

    @Test
    fun remove_deletes() = runBlocking {
        val store = createStore()
        store.put(1, "x")
        store.remove(1)
        assertNull(store.get(1))
    }
}
