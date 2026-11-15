package dev.openwrtmanager.app.router

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class BoardDto(
    val hostname: String = "",
    val model: String = "",
    val release: ReleaseDto = ReleaseDto(),
)

@Serializable
internal data class ReleaseDto(val version: String = "", val description: String = "")

@Serializable
internal data class InfoDto(
    val uptime: Long = 0,
    val load: List<Long> = emptyList(),
    val memory: MemoryDto = MemoryDto(),
)

@Serializable
internal data class MemoryDto(
    val total: Long = 0,
    val free: Long = 0,
    val buffered: Long = 0,
    val available: Long? = null,
)

@Serializable
internal data class InterfaceDumpDto(@SerialName("interface") val interfaces: List<InterfaceDto> = emptyList())

@Serializable
internal data class InterfaceDto(
    @SerialName("interface") val name: String = "",
    val up: Boolean = false,
    @SerialName("l3_device") val l3Device: String? = null,
    val route: List<RouteDto> = emptyList(),
)

@Serializable
internal data class RouteDto(val target: String = "", val mask: Int = -1)

@Serializable
internal data class DeviceStatusDto(val statistics: StatisticsDto = StatisticsDto())

@Serializable
internal data class StatisticsDto(
    @SerialName("rx_bytes") val rxBytes: Long = 0,
    @SerialName("tx_bytes") val txBytes: Long = 0,
)

@Serializable
internal data class RadioDto(
    val up: Boolean = false,
    val config: RadioConfigDto = RadioConfigDto(),
    val interfaces: List<WifiIfaceDto> = emptyList(),
)

@Serializable
internal data class RadioConfigDto(val band: String? = null, val hwmode: String? = null)

@Serializable
internal data class WifiIfaceDto(val ifname: String? = null, val config: WifiIfaceConfigDto = WifiIfaceConfigDto())

@Serializable
internal data class WifiIfaceConfigDto(
    val mode: String = "",
    val ssid: String = "",
    val encryption: String = "none",
    val network: List<String> = emptyList(),
)

@Serializable
internal data class HostapdClientsDto(val clients: JsonObject = JsonObject(emptyMap()))
