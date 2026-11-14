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

internal fun InterfaceDumpDto.uplinkDevice(): String? =
    interfaces.firstOrNull { iface ->
        iface.up && iface.route.any { it.target == "0.0.0.0" && it.mask == 0 }
    }?.l3Device

internal fun RadioConfigDto.toBand(): Band = when {
    band == "2g" -> Band.GHZ_2_4
    band == "5g" -> Band.GHZ_5
    band == "6g" -> Band.GHZ_6
    band != null -> Band.UNKNOWN
    hwmode == null -> Band.UNKNOWN
    hwmode.contains('a') -> Band.GHZ_5
    else -> Band.GHZ_2_4
}

/** Counts unexpired leases in dnsmasq's lease file. The first field is the expiry epoch; 0 means never. */
internal fun countActiveLeases(leaseFile: String, nowSeconds: Long): Int =
    leaseFile.lineSequence().count { line ->
        val expiry = line.trim().substringBefore(' ').toLongOrNull() ?: return@count false
        expiry == 0L || expiry > nowSeconds
    }
