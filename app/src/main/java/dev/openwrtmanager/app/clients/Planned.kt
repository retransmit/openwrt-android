package dev.openwrtmanager.app.clients

import dev.openwrtmanager.app.apply.ChangeSet

/** A planned change, or the reason it cannot be made. */
sealed interface Planned<out P> {
    data class Ready(val change: ChangeSet) : Planned<Nothing>
    data class Problem<P>(val problem: P) : Planned<P>
}

/** Dotted IPv4 in canonical form only: ASCII digits, no leading zeros, as dnsmasq and musl read it. */
internal fun ipv4ToLong(address: String): Long? {
    val parts = address.split('.')
    if (parts.size != 4) return null
    var value = 0L
    for (part in parts) {
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
        if (part.length > 1 && part[0] == '0') return null
        val octet = part.toInt().takeIf { it in 0..255 } ?: return null
        value = value * 256 + octet
    }
    return value
}

internal fun longToIpv4(value: Long): String = "${value shr 24 and 255}.${value shr 16 and 255}.${value shr 8 and 255}.${value and 255}"

/** Network base address and size of a CIDR string such as 192.168.1.0/24. */
internal fun cidrRange(cidr: String): Pair<Long, Long>? {
    val base = ipv4ToLong(cidr.substringBefore('/')) ?: return null
    val bits = cidr.substringAfter('/', "").toIntOrNull()?.takeIf { it in 0..32 } ?: return null
    val size = 1L shl (32 - bits)
    return (base / size * size) to size
}
