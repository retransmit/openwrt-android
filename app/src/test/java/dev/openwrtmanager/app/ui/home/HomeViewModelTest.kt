package dev.openwrtmanager.app.ui.home

import dev.openwrtmanager.app.connection.ConnectionManager
import dev.openwrtmanager.app.data.AuthType
import dev.openwrtmanager.app.data.Profile
import dev.openwrtmanager.app.domain.Band
import dev.openwrtmanager.app.domain.Rate
import dev.openwrtmanager.app.domain.WifiNetwork
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.testing.CMD_CLIENTS_AP0
import dev.openwrtmanager.app.testing.CMD_ETH1
import dev.openwrtmanager.app.testing.CMD_IFACES
import dev.openwrtmanager.app.testing.CMD_LEASES
import dev.openwrtmanager.app.testing.FakeProfileRepository
import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.testing.InMemorySecretStore
import dev.openwrtmanager.app.testing.MainDispatcherRule
import dev.openwrtmanager.app.testing.healthyRouter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainRule = MainDispatcherRule(dispatcher)

    private val secrets = InMemorySecretStore()
    private val profiles = FakeProfileRepository(secrets)
    private val router = healthyRouter()
    private var connects = 0
    private val wifiGateway = dev.openwrtmanager.app.testing.FakeWifiGateway()

    private fun counters(rx: Long, tx: Long) =
        router.respond(CMD_ETH1, """{"statistics":{"rx_bytes":$rx,"tx_bytes":$tx}}""")

    private suspend fun addProfile(name: String = "Home"): Long {
        val id = profiles.save(Profile(name = name, host = "10.0.0.1", authType = AuthType.PASSWORD, hostKeyFingerprint = "fp"))
        secrets.put(id, "pw")
        profiles.setActive(id)
        return id
    }

    /** Starts a manager and a polling view model, as the screen would while visible. */
    private fun TestScope.start(): HomeViewModel {
        val manager = ConnectionManager(profiles, secrets, { connects++; router }, backgroundScope)
        val vm = HomeViewModel(manager, { wifiGateway }) { testScheduler.currentTime }
        backgroundScope.launch { vm.poll() }
        return vm
    }

    private fun countOf(command: String) = router.commands.count { it == command }

    @Test
    fun no_device_is_reported_before_any_profile_exists() = runTest(dispatcher) {
        val vm = start()
        runCurrent()
        assertEquals(HomeStatus.NoDevice, vm.ui.value.status)
    }

    @Test
    fun online_state_shows_device_system_wifi_and_clients() = runTest(dispatcher) {
        router.respond(CMD_LEASES, "1000\n0 aa 192.168.1.2 a *\n0 bb 192.168.1.3 b *\n0 cc 192.168.1.4 c *\n")
        addProfile("Home")
        val vm = start()
        runCurrent()
        val ui = vm.ui.value
        assertEquals(HomeStatus.Online, ui.status)
        assertEquals("Home", ui.deviceName)
        assertEquals("GL.iNet GL-MT6000", ui.system?.model)
        assertTrue(ui.uplinkPresent)
        assertEquals(3, ui.clientTotal)
        assertNull("no rate until two samples exist", ui.rate)
    }

    @Test
    fun two_samples_two_seconds_apart_give_a_rate() = runTest(dispatcher) {
        counters(1_000, 500)
        addProfile()
        val vm = start()
        runCurrent()
        counters(3_000, 1_500)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(Rate(8_000, 4_000), vm.ui.value.rate)
        assertEquals(listOf(Rate(8_000, 4_000)), vm.ui.value.history)
    }

    @Test
    fun history_keeps_only_the_last_sixty_samples() = runTest(dispatcher) {
        addProfile()
        val vm = start()
        runCurrent()
        repeat(70) { i ->
            counters(10_000L * (i + 1), 0)
            advanceTimeBy(2_000)
            runCurrent()
        }
        assertEquals(60, vm.ui.value.history.size)
    }

    @Test
    fun response_time_is_the_time_one_poll_takes() = runTest(dispatcher) {
        addProfile()
        counters(1_000, 500)
        router.delayMs = 23
        val vm = start()
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(23L, vm.ui.value.responseMs)
    }

    @Test
    fun response_time_comes_from_one_command_without_uplink() = runTest(dispatcher) {
        router.respond(CMD_IFACES, Fixtures.read("iface_dump_no_wan.json"))
        router.respond(CMD_LEASES, "1000\n")
        addProfile()
        router.delayMs = 17
        val vm = start()
        advanceTimeBy(11_000)
        runCurrent()
        assertEquals(17L, vm.ui.value.responseMs)
    }

    @Test
    fun response_time_never_includes_waiting_behind_other_commands() = runTest(dispatcher) {
        addProfile()
        counters(1_000, 500)
        router.serialize = true
        // The 10 s refresh is slow enough that a speed poll lands while it holds the connection.
        router.delayOf = { if (it == CMD_ETH1) 23 else 700 }
        val vm = start()
        val seen = mutableSetOf<Long>()
        repeat(120) {
            advanceTimeBy(250)
            runCurrent()
            vm.ui.value.responseMs?.let { seen += it }
        }
        assertEquals(setOf(23L), seen)
    }

    @Test
    fun access_point_without_dhcp_still_gets_a_response_time() = runTest(dispatcher) {
        router.respond(CMD_IFACES, Fixtures.read("iface_dump_no_wan.json"))
        router.respond(CMD_LEASES, "", exitCode = 1)
        addProfile()
        router.delayMs = 17
        val vm = start()
        advanceTimeBy(11_000)
        runCurrent()
        assertEquals(17L, vm.ui.value.responseMs)
    }

    @Test
    fun response_time_hidden_when_not_online() = runTest(dispatcher) {
        addProfile()
        counters(1_000, 500)
        val vm = start()
        advanceTimeBy(2_500)
        runCurrent()
        router.failWith = SshError.Disconnected()
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(vm.ui.value.responseMs)
    }

    @Test
    fun device_without_uplink_skips_counters_but_still_loads_wifi() = runTest(dispatcher) {
        router.respond(CMD_IFACES, Fixtures.read("iface_dump_no_wan.json"))
        addProfile()
        val vm = start()
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertFalse(vm.ui.value.uplinkPresent)
        assertNull(vm.ui.value.rate)
        assertEquals(0, countOf(CMD_ETH1))
        assertTrue(vm.ui.value.wifi.isNotEmpty())
    }

    @Test
    fun dropped_connection_goes_offline_keeps_data_and_stops_polling() = runTest(dispatcher) {
        addProfile()
        val vm = start()
        runCurrent()
        val wifiBefore = vm.ui.value.wifi
        router.failWith = SshError.Disconnected()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(vm.ui.value.status is HomeStatus.Offline)
        assertEquals(wifiBefore, vm.ui.value.wifi)
        assertEquals("one connection, no reconnect yet", 1, connects)
        val commandsWhenOffline = router.commands.size
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(commandsWhenOffline, router.commands.size)
    }

    @Test
    fun polling_resumes_after_reconnect_without_a_bogus_spike() = runTest(dispatcher) {
        counters(1_000, 500)
        addProfile()
        val vm = start()
        runCurrent()
        router.failWith = SshError.Disconnected()
        advanceTimeBy(2_000)
        runCurrent()
        router.failWith = null
        counters(900_000_000, 900_000_000)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(HomeStatus.Online, vm.ui.value.status)
        assertNull("first sample after reconnect has no rate", vm.ui.value.rate)
        assertTrue(vm.ui.value.history.isEmpty())
    }

    @Test
    fun switching_device_clears_history_and_rate() = runTest(dispatcher) {
        counters(1_000, 500)
        addProfile("A")
        val vm = start()
        runCurrent()
        counters(3_000, 1_500)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, vm.ui.value.history.size)
        addProfile("B")
        runCurrent()
        assertEquals("B", vm.ui.value.deviceName)
        assertTrue(vm.ui.value.history.isEmpty())
        assertNull(vm.ui.value.rate)
    }

    @Test
    fun client_total_falls_back_to_wifi_clients_without_dhcp() = runTest(dispatcher) {
        router.respond(CMD_LEASES, "", exitCode = 1)
        addProfile()
        val vm = start()
        runCurrent()
        assertEquals(2, vm.ui.value.clientTotal)
    }

    @Test
    fun slow_data_refreshes_every_ten_seconds() = runTest(dispatcher) {
        addProfile()
        val vm = start()
        runCurrent()
        router.respond(CMD_CLIENTS_AP0, """{"clients":{"a":{},"b":{},"c":{},"d":{}}}""")
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(2, vm.ui.value.wifi.first().clients)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(4, vm.ui.value.wifi.first().clients)
    }

    @Test
    fun refresh_reloads_slow_data_immediately() = runTest(dispatcher) {
        addProfile()
        val vm = start()
        runCurrent()
        router.respond(CMD_CLIENTS_AP0, """{"clients":{"a":{}}}""")
        vm.refresh()
        runCurrent()
        assertEquals(1, vm.ui.value.wifi.first().clients)
    }

    @Test
    fun same_ssid_on_two_bands_merges_into_one_row() {
        val rows = mergeWifi(
            listOf(
                WifiNetwork("phy0-ap0", "Home", Band.GHZ_2_4, "sae-mixed", up = true, clientCount = 2),
                WifiNetwork("phy0-ap1", "IoT", Band.GHZ_2_4, "psk2", up = true, clientCount = 7),
                WifiNetwork("phy1-ap0", "Home", Band.GHZ_5, "sae-mixed", up = true, clientCount = 10),
                WifiNetwork(null, "Guest", Band.GHZ_5, "none", up = false, clientCount = 0),
            ),
        )
        assertEquals(listOf("Home", "IoT", "Guest"), rows.map { it.ssid })
        assertEquals(listOf(Band.GHZ_2_4, Band.GHZ_5), rows[0].bands)
        assertEquals(12, rows[0].clients)
        assertTrue(rows[0].encrypted)
        assertFalse(rows[2].encrypted)
        assertFalse(rows[2].up)
    }

    @Test
    fun client_total_is_never_below_connected_wifi_clients() = runTest(dispatcher) {
        // An access point can have an empty lease file while clients are connected over Wi-Fi.
        router.respond(CMD_LEASES, "1000\n")
        addProfile()
        val vm = start()
        runCurrent()
        assertEquals(2, vm.ui.value.clientTotal)
    }

    @Test
    fun unexpected_exception_while_polling_goes_offline_instead_of_crashing() = runTest(dispatcher) {
        addProfile()
        val vm = start()
        runCurrent()
        router.crashWith = IllegalStateException("unexpected")
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(vm.ui.value.status is HomeStatus.Offline)
    }

    @Test
    fun client_total_is_unknown_when_nothing_could_be_read() = runTest(dispatcher) {
        router.respond(CMD_LEASES, "", exitCode = 1)
        router.respond("ubus call network.wireless status", "", exitCode = 4, stderr = "Command failed")
        addProfile()
        val vm = start()
        runCurrent()
        assertNull(vm.ui.value.clientTotal)
    }

    @Test
    fun one_failed_uplink_read_keeps_the_graph() = runTest(dispatcher) {
        counters(1_000, 500)
        addProfile()
        val vm = start()
        runCurrent()
        counters(3_000, 1_500)
        advanceTimeBy(2_000)
        runCurrent()
        router.respond(CMD_IFACES, "<garbage>")
        advanceTimeBy(8_000)
        runCurrent()
        assertTrue(vm.ui.value.uplinkPresent)
        assertTrue(vm.ui.value.history.isNotEmpty())
    }

    @Test
    fun offline_messages_distinguish_the_cause() {
        val unreachable = offlineMessage(SshError.Unreachable())
        val titles = listOf(
            SshError.AuthFailed(),
            SshError.HostKeyChanged("a", "b"),
            SshError.Timeout(),
            dev.openwrtmanager.app.router.RouterError.NotOpenWrt(),
            dev.openwrtmanager.app.router.RouterError.BadResponse("x"),
            dev.openwrtmanager.app.router.RouterError.CommandFailed("x", "denied"),
        ).map { offlineMessage(it).title }
        assertEquals("each cause has its own title", titles.size, titles.toSet().size)
        assertFalse(unreachable.title in titles)
        assertEquals(offlineMessage(SshError.Disconnected()).title, unreachable.title)
    }

    @Test
    fun qr_code_is_loaded_on_request_and_forgotten_on_dismiss() = runTest(dispatcher) {
        addProfile()
        val vm = start()
        runCurrent()
        assertNull(vm.qr.value)
        vm.showQr("Home")
        runCurrent()
        assertEquals("Home", vm.qr.value!!.ssid)
        assertEquals("WIFI:T:WPA;S:Home;P:hunter22pass;;", vm.qr.value!!.payload)
        assertFalse(vm.qr.value.toString().contains("hunter22pass"))
        assertFalse(vm.ui.value.toString().contains("hunter22pass"))
        vm.dismissQr()
        assertNull(vm.qr.value)
    }

    @Test
    fun qr_request_for_an_unknown_network_does_nothing() = runTest(dispatcher) {
        addProfile()
        val vm = start()
        runCurrent()
        vm.showQr("Nope")
        runCurrent()
        assertNull(vm.qr.value)
    }
}
