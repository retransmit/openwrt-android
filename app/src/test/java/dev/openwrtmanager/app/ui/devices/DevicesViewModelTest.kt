package dev.openwrtmanager.app.ui.devices

import app.cash.turbine.test
import dev.openwrtmanager.app.data.AuthType
import dev.openwrtmanager.app.data.Profile
import dev.openwrtmanager.app.testing.FakeProfileRepository
import dev.openwrtmanager.app.testing.InMemorySecretStore
import dev.openwrtmanager.app.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class DevicesViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val secrets = InMemorySecretStore()
    private val profiles = FakeProfileRepository(secrets)

    private suspend fun add(name: String): Long {
        val id = profiles.save(Profile(name = name, host = "$name.lan", authType = AuthType.PASSWORD))
        secrets.put(id, "pw")
        return id
    }

    @Test
    fun rows_are_sorted_and_mark_the_active_device() = runTest {
        val router = add("Router")
        add("AP upstairs")
        profiles.setActive(router)
        val vm = DevicesViewModel(profiles)
        vm.ui.test {
            var rows = awaitItem()
            while (rows.devices.size < 2) rows = awaitItem()
            assertEquals(listOf("AP upstairs", "Router"), rows.devices.map { it.profile.name })
            assertEquals(listOf(false, true), rows.devices.map { it.active })
        }
    }

    @Test
    fun select_makes_device_active() = runTest {
        add("Router")
        val ap = add("AP")
        val vm = DevicesViewModel(profiles)
        vm.select(ap)
        assertEquals(ap, profiles.observeActiveId().first())
    }

    @Test
    fun delete_removes_profile_and_secret() = runTest {
        val id = add("Router")
        profiles.setActive(id)
        val vm = DevicesViewModel(profiles)
        vm.delete(id)
        assertNull(profiles.get(id))
        assertNull(secrets.get(id))
        assertNull(profiles.observeActiveId().first())
    }
}
