package dev.openwrtmanager.app.testing

object Fixtures {
    fun read(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) { "missing fixture $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
}

const val CMD_BOARD = "ubus call system board"
const val CMD_INFO = "ubus call system info"
const val CMD_IFACES = "ubus call network.interface dump"
const val CMD_WIRELESS = "ubus call network.wireless status"
const val CMD_LEASES = "date +%s; cat /tmp/dhcp.leases 2>/dev/null"
const val CMD_ETH1 = """ubus call network.device status '{"name":"eth1"}'"""
const val CMD_CLIENTS_AP0 = "ubus call hostapd.phy0-ap0 get_clients"

/** A fake loaded with a healthy router: board, info, interfaces, eth1 counters, wireless, one client list. */
fun healthyRouter(): FakeSshExecutor = FakeSshExecutor().apply {
    respond(CMD_BOARD, Fixtures.read("board.json"))
    respond(CMD_INFO, Fixtures.read("info.json"))
    respond(CMD_IFACES, Fixtures.read("iface_dump.json"))
    respond(CMD_ETH1, Fixtures.read("device_eth1.json"))
    respond(CMD_WIRELESS, Fixtures.read("wireless.json"))
    respond(CMD_CLIENTS_AP0, Fixtures.read("clients_phy0-ap0.json"))
}
