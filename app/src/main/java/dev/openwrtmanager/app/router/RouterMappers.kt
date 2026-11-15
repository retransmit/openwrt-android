package dev.openwrtmanager.app.router

import dev.openwrtmanager.app.domain.Band
import dev.openwrtmanager.app.domain.SystemInfo

internal fun toSystemInfo(board: BoardDto, info: InfoDto): SystemInfo = SystemInfo(
    hostname = board.hostname,
    model = board.model,
    firmware = board.release.description.ifBlank { board.release.version },
    uptimeSeconds = info.uptime,
    load1 = (info.load.firstOrNull() ?: 0L) / 65536.0,
    memTotalBytes = info.memory.total,
    memAvailableBytes = info.memory.available ?: (info.memory.free + info.memory.buffered),
)

/**
 * The device carrying the IPv4 default route, unless that route sits on a network Wi-Fi clients join.
 * A plain access point reaches the internet through its LAN; its bridge counters only show the
 * access point's own traffic, so that is not an internet link worth graphing.
 */
internal fun InterfaceDumpDto.uplinkDevice(clientNetworks: Set<String>): String? =
    interfaces.firstOrNull { iface ->
        iface.up && iface.name !in clientNetworks && iface.route.any { it.target == "0.0.0.0" && it.mask == 0 }
    }?.l3Device

/** Names of the networks that access point interfaces are attached to. */
internal fun Map<String, RadioDto>.clientNetworks(): Set<String> =
    values.flatMap { radio -> radio.interfaces.filter { it.config.mode == "ap" }.flatMap { it.config.network } }.toSet()

internal fun RadioConfigDto.toBand(): Band = when {
    band == "2g" -> Band.GHZ_2_4
    band == "5g" -> Band.GHZ_5
    band == "6g" -> Band.GHZ_6
    band != null -> Band.UNKNOWN
    hwmode == null -> Band.UNKNOWN
    hwmode.contains('a') -> Band.GHZ_5
    else -> Band.GHZ_2_4
}

/**
 * Counts unexpired leases. [output] is the router's own clock in epoch seconds on the first line,
 * then dnsmasq's lease file, whose first field per line is the expiry (0 means never).
 * Returns null when the clock line is unreadable.
 */
internal fun countActiveLeases(output: String): Int? {
    val lines = output.lineSequence().iterator()
    val now = (if (lines.hasNext()) lines.next() else "").trim().toLongOrNull() ?: return null
    var count = 0
    for (line in lines) {
        val expiry = line.trim().substringBefore(' ').toLongOrNull() ?: continue
        if (expiry == 0L || expiry > now) count++
    }
    return count
}
