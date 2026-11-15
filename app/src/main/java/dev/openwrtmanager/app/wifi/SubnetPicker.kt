package dev.openwrtmanager.app.wifi

private class Range(val start: Long, val end: Long) {
    fun overlaps(other: Range) = start <= other.end && other.start <= end
}

private fun parseIpv4(address: String): Long? {
    val parts = address.split('.')
    if (parts.size != 4) return null
    var value = 0L
    for (part in parts) {
        val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        value = value * 256 + octet
    }
    return value
}

private fun parseCidr(cidr: String): Range? {
    val address = parseIpv4(cidr.substringBefore('/')) ?: return null
    val bits = cidr.substringAfter('/', "32").toIntOrNull()?.takeIf { it in 0..32 } ?: return null
    val size = 1L shl (32 - bits)
    val start = address / size * size
    return Range(start, start + size - 1)
}

/** The network a host address belongs to, as a CIDR string. Null for anything that is not IPv4. */
fun networkOf(address: String, maskBits: Int): String? {
    val range = parseCidr("$address/$maskBits") ?: return null
    val s = range.start
    return "${s shr 24 and 255}.${s shr 16 and 255}.${s shr 8 and 255}.${s and 255}/$maskBits"
}

/**
 * First free 192.168.N.0/24, N from 10 to 250, that overlaps none of the ranges in [used].
 * Entries that are not IPv4 CIDR are ignored. Null when every candidate is taken.
 */
fun pickSubnet(used: List<String>): String? {
    val taken = used.mapNotNull(::parseCidr)
    for (n in 10..250) {
        val candidate = "192.168.$n.0/24"
        val range = parseCidr(candidate)!!
        if (taken.none { it.overlaps(range) }) return candidate
    }
    return null
}
