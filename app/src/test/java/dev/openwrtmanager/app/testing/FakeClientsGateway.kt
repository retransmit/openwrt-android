package dev.openwrtmanager.app.testing

import dev.openwrtmanager.app.clients.ClientReadings
import dev.openwrtmanager.app.clients.ClientsGateway
import dev.openwrtmanager.app.clients.DeviceConfig
import dev.openwrtmanager.app.clients.parseAssoclist
import dev.openwrtmanager.app.clients.parseLeases
import dev.openwrtmanager.app.clients.parseNeighbours
import dev.openwrtmanager.app.domain.Band
import dev.openwrtmanager.app.domain.WifiNetwork
import dev.openwrtmanager.app.uci.parseUciConfig

fun routerClientReadings() = ClientReadings(
    leases = parseLeases(Fixtures.read("leases.txt")),
    associations = parseAssoclist(Fixtures.read("assoclist_phy1-ap0.json"), "phy1-ap0"),
    neighbours = parseNeighbours(Fixtures.read("ip_neigh.txt")),
    hintNames = emptyMap(),
    wifi = listOf(WifiNetwork("phy1-ap0", "Home", Band.GHZ_5, "psk2", true, 2)),
    missing = emptyList(),
)

fun routerDeviceConfig() = DeviceConfig(
    network = parseUciConfig("network", Fixtures.read("uci_network.json")),
    dhcp = parseUciConfig("dhcp", Fixtures.read("uci_dhcp.json")),
    firewall = parseUciConfig("firewall", Fixtures.read("uci_firewall.json")),
    l3Devices = mapOf("lan" to "br-lan", "wan" to "wan"),
    firewallEnabled = true,
    dhcpv4Required = true,
)

class FakeClientsGateway(
    var readings: ClientReadings = routerClientReadings(),
    var config: DeviceConfig = routerDeviceConfig(),
) : ClientsGateway {
    var failure: Exception? = null
    var reconnectWorks = true
    var fileWorks = true
    var address: String? = "192.168.1.126"
    val reconnected = mutableListOf<Pair<String, String>>()
    var fileWrites = 0
    var reads = 0

    override suspend fun readings(): ClientReadings {
        failure?.let { throw it }
        reads++
        return readings
    }

    override suspend fun config(): DeviceConfig {
        failure?.let { throw it }
        return config
    }

    override suspend fun reconnect(ifname: String, mac: String): Boolean {
        reconnected += ifname to mac
        return reconnectWorks
    }

    override suspend fun ensureBlockFile(): Boolean {
        fileWrites++
        return fileWorks
    }

    override suspend fun myAddress(): String? = address

    var interfaces: List<dev.openwrtmanager.app.router.InterfaceStatus> = emptyList()

    override suspend fun interfaces(): List<dev.openwrtmanager.app.router.InterfaceStatus> {
        failure?.let { throw it }
        return interfaces
    }
}
