package dev.openwrtmanager.app.testing

import dev.openwrtmanager.app.uci.parseUciConfig
import dev.openwrtmanager.app.wifi.DeviceSnapshot
import dev.openwrtmanager.app.wifi.UsteerStatus
import dev.openwrtmanager.app.wifi.WifiConfig
import dev.openwrtmanager.app.wifi.WifiGateway
import dev.openwrtmanager.app.wifi.wifiConfigOf

fun routerSnapshot() = DeviceSnapshot(
    wireless = parseUciConfig("wireless", Fixtures.read("uci_wireless.json")),
    network = parseUciConfig("network", Fixtures.read("uci_network.json")),
    firewall = parseUciConfig("firewall", Fixtures.read("uci_firewall.json")),
    dhcp = parseUciConfig("dhcp", Fixtures.read("uci_dhcp.json")),
    usedSubnets = listOf("192.168.1.0/24", "127.0.0.0/8", "192.168.0.0/24"),
    hasUplink = true,
)

fun accessPointSnapshot() = DeviceSnapshot(
    wireless = parseUciConfig("wireless", Fixtures.read("uci_wireless_ap.json")),
    network = parseUciConfig("network", Fixtures.read("uci_network_ap.json")),
    firewall = parseUciConfig("firewall", Fixtures.read("uci_firewall_ap.json")),
    dhcp = parseUciConfig("dhcp", Fixtures.read("uci_dhcp_ap.json")),
    usedSubnets = listOf("192.168.0.0/24"),
    hasUplink = false,
)

class FakeWifiGateway(var snapshot: DeviceSnapshot = routerSnapshot()) : WifiGateway {
    var failure: Exception? = null
    var usteer = UsteerStatus(installed = true, running = false)
    var started = 0
    var installed = 0
    var loads = 0

    override suspend fun wifi(): WifiConfig {
        failure?.let { throw it }
        loads++
        return wifiConfigOf(snapshot.wireless)
    }

    override suspend fun snapshot(): DeviceSnapshot {
        failure?.let { throw it }
        loads++
        return snapshot
    }

    override suspend fun usteerStatus(): UsteerStatus = usteer

    override suspend fun startUsteer(): Boolean {
        started++
        usteer = usteer.copy(running = true)
        return true
    }

    override suspend fun installUsteer(): Boolean {
        installed++
        usteer = usteer.copy(installed = true)
        return true
    }
}
