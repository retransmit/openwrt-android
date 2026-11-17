package dev.openwrtmanager.app.ui.wifi

import dev.openwrtmanager.app.data.AuthType
import dev.openwrtmanager.app.data.Profile
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.testing.FakeProfileRepository
import dev.openwrtmanager.app.testing.FakeSshExecutor
import dev.openwrtmanager.app.testing.FakeWifiGateway
import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.testing.MainDispatcherRule
import dev.openwrtmanager.app.testing.accessPointSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MirrorViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val profiles = FakeProfileRepository()
    private val gateway = FakeWifiGateway(accessPointSnapshot())
    private val router = FakeSshExecutor()
    private var routerReachable = true

    private val guestWifi = """
        {"values":{"guest_radio0":{".anonymous":false,".type":"wifi-iface",".name":"guest_radio0",".index":5,
        "device":"radio0","mode":"ap","network":"guest","ssid":"Home-Guest","encryption":"sae-mixed","key":"guest secret 1"}}}
    """.trimIndent()

    private fun network(tagged: Boolean): String {
        val base = Fixtures.read("uci_network_vlans.json")
        return if (tagged) base else base.replace("\"lan4:t\"", "\"lan9:t\"")
    }

    private suspend fun setUp(tagged: Boolean = true): Pair<Long, Long> {
        val ap = profiles.save(Profile(name = "Archer", host = "192.168.1.2", authType = AuthType.KEY))
        val main = profiles.save(Profile(name = "Main router", host = "192.168.1.1", authType = AuthType.KEY))
        profiles.setActive(ap)
        router.respond("""ubus call uci get '{"config":"network"}'""", network(tagged))
        router.respond("""ubus call uci get '{"config":"wireless"}'""", guestWifi)
        return ap to main
    }

    private fun viewModel() = MirrorViewModel(profiles, { if (routerReachable) router else throw SshError.Unreachable() }, { gateway })

    @Test
    fun lists_other_profiles_as_routers() = runTest {
        val (_, main) = setUp()
        val vm = viewModel().also { it.start() }
        assertEquals(listOf(main), vm.ui.value.routers.map { it.id })
        assertEquals(listOf("lan1", "lan2", "lan3", "lan4", "wan"), vm.ui.value.ports)
    }

    @Test
    fun reads_networks_from_the_picked_router_and_closes_that_connection() = runTest {
        val (_, main) = setUp()
        val vm = viewModel().also { it.start() }
        vm.pickRouter(main)
        val ui = vm.ui.value
        assertEquals(listOf("guest"), ui.sources.map { it.name })
        assertEquals(setOf("guest"), ui.chosen)
        assertTrue(router.closed)
        assertEquals(emptyList<String>(), ui.warnings)
        val change = vm.review()!!
        assertTrue("set wireless.guest_radio0.ssid='Home-Guest'" in change.batch.script())
        assertTrue("add_list network.vlan_guest.ports='wan:t'" in change.batch.script())
    }

    @Test
    fun uplink_defaults_to_wan_when_it_is_a_bridge_port() = runTest {
        setUp()
        val vm = viewModel().also { it.start() }
        assertEquals("wan", vm.ui.value.uplink)
        vm.onUplink("lan2")
        assertEquals("lan2", vm.ui.value.uplink)
    }

    @Test
    fun warns_when_no_router_port_is_tagged_for_the_vlan() = runTest {
        val (_, main) = setUp(tagged = false)
        val vm = viewModel().also { it.start() }
        vm.pickRouter(main)
        assertEquals(
            listOf(
                "No port on Main router carries guest tagged yet. On Main router, open the Network tab and set the port this device " +
                    "is plugged into as an AP link for guest.",
            ),
            vm.ui.value.warnings,
        )
    }

    @Test
    fun unreachable_router_is_a_message_not_a_crash() = runTest {
        val (_, main) = setUp()
        routerReachable = false
        val vm = viewModel().also { it.start() }
        vm.pickRouter(main)
        assertEquals("Could not read the networks on Main router.", vm.ui.value.error)
        assertNull(vm.review())
    }

    @Test
    fun nothing_chosen_cannot_be_reviewed() = runTest {
        val (_, main) = setUp()
        val vm = viewModel().also { it.start() }
        vm.pickRouter(main)
        vm.toggle("guest")
        assertNull(vm.review())
        assertNotNull(vm.ui.value.blocked)
    }

    @Test
    fun state_never_prints_the_key() = runTest {
        val (_, main) = setUp()
        val vm = viewModel().also { it.start() }
        vm.pickRouter(main)
        assertTrue("guest secret 1" !in vm.ui.value.toString())
    }
}
