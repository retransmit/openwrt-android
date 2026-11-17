package dev.openwrtmanager.app.router

import dev.openwrtmanager.app.domain.Counters
import dev.openwrtmanager.app.domain.SystemInfo
import dev.openwrtmanager.app.domain.WifiNetwork
import dev.openwrtmanager.app.ssh.SshExecutor
import dev.openwrtmanager.app.ssh.shellQuote
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

sealed class RouterError(message: String) : Exception(message) {
    class NotOpenWrt : RouterError("ubus not available")
    class CommandFailed(val command: String, val stderr: String) : RouterError("command failed: $command")
    class BadResponse(val command: String) : RouterError("unparseable response: $command")
}

/** One network interface as the Network tab shows it. Traffic is null while it is down. */
data class InterfaceStatus(
    val name: String,
    val up: Boolean,
    val proto: String,
    val ipv4: String?,
    val uptimeSeconds: Long?,
    val rxBytes: Long?,
    val txBytes: Long?,
)

class RouterClient(private val ssh: SshExecutor) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    suspend fun systemInfo(): SystemInfo {
        val board = ubus("ubus call system board", BoardDto.serializer())
        val info = ubus("ubus call system info", InfoDto.serializer())
        return toSystemInfo(board, info)
    }

    /** Device of the internet-facing interface; null when there is none (WAN down, or a plain access point). */
    suspend fun uplinkDevice(): String? {
        val dump = ubus("ubus call network.interface dump", InterfaceDumpDto.serializer())
        val clientNetworks = try {
            radios().clientNetworks()
        } catch (_: RouterError) {
            emptySet()
        }
        return dump.uplinkDevice(clientNetworks)
    }

    /** Interface name to the device it currently runs on (for example wan to pppoe-wan). Interfaces that are down are left out. */
    suspend fun l3Devices(): Map<String, String> =
        ubus("ubus call network.interface dump", InterfaceDumpDto.serializer()).interfaces
            .mapNotNull { iface -> iface.l3Device?.takeIf { it.isNotEmpty() }?.let { iface.name to it } }
            .toMap()

    /** Every interface except loopback, with traffic from its current device when it is up. */
    suspend fun interfaces(): List<InterfaceStatus> =
        ubus("ubus call network.interface dump", InterfaceDumpDto.serializer()).interfaces
            .filter { it.name != "loopback" && it.l3Device != "lo" }
            .map { iface ->
                val device = iface.l3Device?.takeIf { iface.up && it.isNotEmpty() }
                val traffic = device?.let { runCatching { counters(it) }.getOrNull() }
                InterfaceStatus(
                    name = iface.name,
                    up = iface.up,
                    proto = iface.proto,
                    ipv4 = iface.ipv4.firstOrNull()?.let { "${it.address}/${it.mask}" },
                    uptimeSeconds = iface.uptime?.takeIf { iface.up },
                    rxBytes = traffic?.rxBytes,
                    txBytes = traffic?.txBytes,
                )
            }

    /** IPv4 ranges already in use on this device, as CIDR strings, one per configured address. */
    suspend fun usedSubnets(): List<String> =
        ubus("ubus call network.interface dump", InterfaceDumpDto.serializer()).interfaces
            .flatMap { it.ipv4 }
            .mapNotNull { dev.openwrtmanager.app.wifi.networkOf(it.address, it.mask) }

    private suspend fun radios(): Map<String, RadioDto> =
        ubus("ubus call network.wireless status", MapSerializer(String.serializer(), RadioDto.serializer()))

    suspend fun counters(device: String): Counters {
        val arg = buildJsonObject { put("name", JsonPrimitive(device)) }.toString()
        val status = ubus("ubus call network.device status ${shellQuote(arg)}", DeviceStatusDto.serializer())
        return Counters(status.statistics.rxBytes, status.statistics.txBytes)
    }

    suspend fun wifiNetworks(): List<WifiNetwork> {
        return radios().values.flatMap { radio ->
            radio.interfaces.filter { it.config.mode == "ap" }.map { iface ->
                WifiNetwork(
                    ifname = iface.ifname,
                    ssid = iface.config.ssid,
                    band = radio.config.toBand(),
                    encryption = iface.config.encryption,
                    up = radio.up && iface.ifname != null,
                    clientCount = iface.ifname?.let { clientCount(it) } ?: 0,
                )
            }
        }
    }

    /** Unexpired DHCP leases; null when the device runs no DHCP server. */
    suspend fun leaseCount(): Int? {
        // The router's own clock comes first: lease expiry times are in its time, which may not match the phone's.
        val result = ssh.exec("date +%s; cat /tmp/dhcp.leases 2>/dev/null")
        if (result.exitCode != 0) return null
        return countActiveLeases(result.stdout)
    }

    /** A missing or failing hostapd object must not break the Wi-Fi list, so this never throws RouterError. */
    private suspend fun clientCount(ifname: String): Int {
        if (!SAFE_IFNAME.matches(ifname)) return 0
        val result = ssh.exec("ubus call hostapd.$ifname get_clients")
        if (result.exitCode != 0) return 0
        return try {
            json.decodeFromString(HostapdClientsDto.serializer(), result.stdout).clients.size
        } catch (_: SerializationException) {
            0
        } catch (_: IllegalArgumentException) {
            0
        }
    }

    private suspend fun <T> ubus(command: String, serializer: DeserializationStrategy<T>): T {
        val result = ssh.exec(command)
        if (result.exitCode != 0) {
            if (result.exitCode == 127 || result.stderr.contains("not found")) throw RouterError.NotOpenWrt()
            throw RouterError.CommandFailed(command, result.stderr.trim())
        }
        return try {
            json.decodeFromString(serializer, result.stdout)
        } catch (_: SerializationException) {
            throw RouterError.BadResponse(command)
        } catch (_: IllegalArgumentException) {
            throw RouterError.BadResponse(command)
        }
    }

    private companion object {
        val SAFE_IFNAME = Regex("[A-Za-z0-9_.-]+")
    }
}
