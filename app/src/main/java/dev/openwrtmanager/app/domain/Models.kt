package dev.openwrtmanager.app.domain

data class SystemInfo(
    val hostname: String,
    val model: String,
    val firmware: String,
    val uptimeSeconds: Long,
    val load1: Double,
    val memTotalBytes: Long,
    val memAvailableBytes: Long,
)

data class Counters(val rxBytes: Long, val txBytes: Long)

enum class Band { GHZ_2_4, GHZ_5, GHZ_6, UNKNOWN }

data class WifiNetwork(
    val ifname: String?,
    val ssid: String,
    val band: Band,
    val encryption: String,
    val up: Boolean,
    val clientCount: Int,
)
