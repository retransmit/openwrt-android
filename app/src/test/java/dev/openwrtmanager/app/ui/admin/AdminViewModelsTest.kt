package dev.openwrtmanager.app.ui.admin

import dev.openwrtmanager.app.admin.AdminGateway
import dev.openwrtmanager.app.admin.LogKind
import dev.openwrtmanager.app.admin.Service
import dev.openwrtmanager.app.admin.ServiceAction
import dev.openwrtmanager.app.data.AuthType
import dev.openwrtmanager.app.data.Profile
import dev.openwrtmanager.app.testing.FakeProfileRepository
import dev.openwrtmanager.app.testing.InMemorySecretStore
import dev.openwrtmanager.app.testing.MainDispatcherRule
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FakeAdminGateway : AdminGateway {
    var services = listOf(Service("cron", true, true), Service("dropbear", true, true))
    val ran = mutableListOf<Pair<String, ServiceAction>>()
    var serviceWorks = true
    var logs = mutableMapOf(LogKind.SYSTEM to listOf("a", "b"), LogKind.KERNEL to listOf("k1"))
    var logReads = 0
    var system = UciConfig("system", listOf(UciSection("cfg01e48a", "system", true, 0, mapOf("hostname" to "OpenWrt", "zonename" to "Asia/Kolkata", "timezone" to "IST-5:30"), emptyMap())))
    val passwords = mutableListOf<String>()
    var passwordResult = dev.openwrtmanager.app.admin.PasswordResult.CHANGED
    var reboots = 0

    override suspend fun services() = services
    override suspend fun runService(name: String, action: ServiceAction): Boolean {
        ran += name to action
        return serviceWorks
    }
    override suspend fun log(kind: LogKind): List<String> {
        logReads++
        return logs.getValue(kind)
    }
    override suspend fun systemConfig() = system
    override suspend fun setRootPassword(password: String): dev.openwrtmanager.app.admin.PasswordResult {
        passwords += password
        return passwordResult
    }
    override suspend fun reboot(): Boolean {
        reboots++
        return true
    }

    var tool: dev.openwrtmanager.app.admin.PackageTool? = dev.openwrtmanager.app.admin.PackageTool.APK
    val packageRuns = mutableListOf<Pair<dev.openwrtmanager.app.admin.PackageAction, String?>>()
    var packageOutputs = mutableMapOf<dev.openwrtmanager.app.admin.PackageAction, dev.openwrtmanager.app.admin.CommandOutput>()

    override suspend fun packageTool() = tool

    override suspend fun runPackage(
        tool: dev.openwrtmanager.app.admin.PackageTool,
        action: dev.openwrtmanager.app.admin.PackageAction,
        name: String?,
    ): dev.openwrtmanager.app.admin.CommandOutput? {
        packageRuns += action to name
        return packageOutputs[action] ?: dev.openwrtmanager.app.admin.CommandOutput(0, "")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AdminViewModelsTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val gateway = FakeAdminGateway()

    @Test
    fun service_action_refreshes_and_reports() = runTest {
        val vm = ServicesViewModel { gateway }.also { it.load() }
        vm.run(Service("cron", true, true), ServiceAction.RESTART)
        assertEquals(listOf("cron" to ServiceAction.RESTART), gateway.ran)
        assertEquals("cron: restart done.", (vm.ui.value as ServicesUi.Ready).message)
        gateway.serviceWorks = false
        vm.run(Service("cron", true, true), ServiceAction.STOP)
        assertEquals("The router could not stop cron.", (vm.ui.value as ServicesUi.Ready).message)
    }

    @Test
    fun refused_action_is_a_message() = runTest {
        val vm = ServicesViewModel { gateway }.also { it.load() }
        vm.run(Service("dropbear", true, true), ServiceAction.STOP)
        assertTrue(gateway.ran.isEmpty())
        assertEquals("Stopping dropbear would cut this app off from the device.", (vm.ui.value as ServicesUi.Ready).message)
    }

    @Test
    fun logs_follow_while_polling() = runTest {
        val vm = LogsViewModel { gateway }
        val job = launch { vm.poll() }
        runCurrent()
        assertEquals(listOf("a", "b"), vm.ui.value.lines)
        gateway.logs[LogKind.SYSTEM] = listOf("b", "c")
        advanceTimeBy(2_001)
        assertEquals(listOf("a", "b", "c"), vm.ui.value.lines)
        vm.setKind(LogKind.KERNEL)
        advanceTimeBy(1)
        assertEquals(listOf("k1"), vm.ui.value.lines)
        vm.setQuery("K1")
        assertEquals(listOf("k1"), vm.ui.value.visible)
        job.cancel()
    }

    @Test
    fun pause_stops_following() = runTest {
        val vm = LogsViewModel { gateway }
        val job = launch { vm.poll() }
        runCurrent()
        vm.setFollowing(false)
        val reads = gateway.logReads
        advanceTimeBy(10_000)
        assertEquals(reads, gateway.logReads)
        job.cancel()
    }

    private val profiles = FakeProfileRepository()
    private val secrets = InMemorySecretStore()

    private var loginRefreshes = 0

    private suspend fun systemViewModel(auth: AuthType, user: String = "root"): SystemViewModel {
        val id = profiles.save(Profile(name = "Router", host = "192.168.1.1", username = user, authType = auth))
        profiles.setActive(id)
        secrets.put(id, "old secret")
        return SystemViewModel({ gateway }, profiles, secrets, { loginRefreshes++ }) { listOf("Asia/Kolkata" to "IST-5:30", "Europe/London" to "GMT0BST,M3.5.0/1,M10.5.0") }.also { it.load() }
    }

    @Test
    fun saved_password_follows_a_change() = runTest {
        val vm = systemViewModel(AuthType.PASSWORD)
        assertTrue(vm.changePassword("new password 1", "new password 1"))
        assertEquals(listOf("new password 1"), gateway.passwords)
        assertEquals("new password 1", secrets.values.values.single())
        assertEquals("Password changed. The app's saved login was updated too.", (vm.ui.value as SystemUi.Ready).message)
        assertEquals("the open connection must pick up the new password", 1, loginRefreshes)
    }

    @Test
    fun unconfirmed_change_says_so() = runTest {
        val vm = systemViewModel(AuthType.PASSWORD)
        gateway.passwordResult = dev.openwrtmanager.app.admin.PasswordResult.UNKNOWN
        assertFalse(vm.changePassword("new password 1", "new password 1"))
        assertEquals("old secret", secrets.values.values.single())
        assertEquals(
            "The app could not confirm whether the password changed. If it cannot log in afterwards, edit this device and enter the new password.",
            (vm.ui.value as SystemUi.Ready).message,
        )
    }

    @Test
    fun a_login_that_is_not_root_is_not_updated() = runTest {
        val vm = systemViewModel(AuthType.PASSWORD, user = "admin")
        assertTrue(vm.changePassword("new password 1", "new password 1"))
        assertEquals("old secret", secrets.values.values.single())
        assertEquals(0, loginRefreshes)
    }

    @Test
    fun key_profile_is_not_touched() = runTest {
        val vm = systemViewModel(AuthType.KEY)
        assertTrue(vm.changePassword("new password 1", "new password 1"))
        assertEquals("old secret", secrets.values.values.single())
    }

    @Test
    fun mismatched_or_weak_passwords_are_not_sent() = runTest {
        val vm = systemViewModel(AuthType.PASSWORD)
        assertFalse(vm.changePassword("new password 1", "new password 2"))
        assertEquals("The two passwords do not match.", (vm.ui.value as SystemUi.Ready).message)
        assertFalse(vm.changePassword("short", "short"))
        assertTrue(gateway.passwords.isEmpty())
        gateway.passwordResult = dev.openwrtmanager.app.admin.PasswordResult.REFUSED
        assertFalse(vm.changePassword("new password 1", "new password 1"))
        assertEquals("old secret", secrets.values.values.single())
    }

    @Test
    fun password_state_never_prints_it() = runTest {
        val vm = systemViewModel(AuthType.PASSWORD)
        vm.changePassword("new password 1", "new password 1")
        assertFalse(vm.ui.value.toString().contains("new password 1"))
    }

    @Test
    fun hostname_and_timezone_are_planned_from_fresh_config() = runTest {
        val vm = systemViewModel(AuthType.KEY)
        assertEquals("OpenWrt", (vm.ui.value as SystemUi.Ready).config.hostname)
        gateway.system = UciConfig("system", listOf(UciSection("cfg99", "system", true, 0, mapOf("hostname" to "Renamed"), emptyMap())))
        val change = vm.planHostname("home-router")!!
        assertEquals(listOf("set system.cfg99.hostname='home-router'"), change.batch.script().lines())
        assertNull(vm.planHostname("bad name"))
        assertEquals("Use letters, digits and hyphens, up to 63, not starting or ending with a hyphen.", (vm.ui.value as SystemUi.Ready).message)
        assertTrue(vm.planTimezone("Europe/London")!!.batch.script().contains("GMT0BST"))
        assertNull(vm.planTimezone("Mars/Base"))
    }

    @Test
    fun reboot_asks_the_router() = runTest {
        val vm = systemViewModel(AuthType.KEY)
        vm.reboot()
        assertEquals(1, gateway.reboots)
    }
}
