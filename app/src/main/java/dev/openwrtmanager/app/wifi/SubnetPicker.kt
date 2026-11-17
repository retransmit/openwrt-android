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
 * [preferred] is returned instead when it is valid and free.
 */
fun pickSubnet(used: List<String>, preferred: String? = null): String? {
    val taken = used.mapNotNull(::parseCidr)
    preferred?.let(::parseCidr)?.let { range -> if (taken.none { it.overlaps(range) }) return preferred }
    for (n in 10..250) {
        val candidate = "192.168.$n.0/24"
        val range = parseCidr(candidate)!!
        if (taken.none { it.overlaps(range) }) return candidate
    }
    return null
}

private fun maskBits(netmask: String): Int? {
    val value = parseIpv4(netmask) ?: return null
    val bits = java.lang.Long.bitCount(value)
    // A valid mask is a run of ones followed by zeros.
    return bits.takeIf { value == (0xFFFFFFFFL shl (32 - bits) and 0xFFFFFFFFL) }
}

/**
 * Address ranges written in the network config: interface addresses and static routes. These count
 * as used even when the interface is down right now, such as a VPN that is not connected.
 */
fun configuredSubnets(network: dev.openwrtmanager.app.uci.UciConfig): List<String> {
    val found = mutableListOf<String>()
    fun add(address: String, netmask: String?) {
        val host = address.substringBefore('/')
        val bits = if ('/' in address) address.substringAfter('/').toIntOrNull() else netmask?.let(::maskBits)
        networkOf(host, bits ?: 32)?.let { found += it }
    }
    for (iface in network.ofType("interface")) iface.list("ipaddr").forEach { add(it, iface.opt("netmask")) }
    for (route in network.ofType("route")) route.opt("target")?.let { add(it, route.opt("netmask")) }
    return found.distinct()
}
