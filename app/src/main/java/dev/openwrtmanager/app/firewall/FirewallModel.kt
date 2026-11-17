package dev.openwrtmanager.app.firewall

import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.uci.UciSection

data class Zone(
    val name: String,
    val networks: List<String>,
    val input: String,
    val output: String,
    val forward: String,
    val masq: Boolean,
    /** Zones this one may start connections to. */
    val forwardsTo: List<String>,
)

/** A `redirect` with target DNAT: traffic arriving on [outsidePort] goes to [insideIp]. */
data class PortForward(
    val section: String,
    val name: String,
    val protocols: Set<String>,
    val outsidePort: String,
    val insideIp: String?,
    /** Null means the same port as outside. */
    val insidePort: String?,
    val destZone: String,
    val enabled: Boolean,
)

data class TrafficRule(
    val section: String,
    val index: Int,
    val name: String,
    val description: String,
    val enabled: Boolean,
    /** One of the rules OpenWrt ships with. */
    val standard: Boolean,
)

data class FirewallView(val zones: List<Zone>, val forwards: List<PortForward>, val rules: List<TrafficRule>, val wanZone: String?)

/** A list option, also when old configs wrote it as one space-separated string. */
internal fun UciSection.words(key: String): List<String> = list(key).flatMap { it.split(' ', '\t') }.filter { it.isNotEmpty() }

/** fw4 reads 0, false, no and off as disabled. */
private fun UciSection.enabled(): Boolean = opt("enabled")?.lowercase() !in setOf("0", "false", "no", "off")

fun firewallViewOf(firewall: UciConfig): FirewallView {
    val forwardings = firewall.ofType("forwarding").filter { it.enabled() }
    val zones = firewall.ofType("zone").mapNotNull { zone ->
        val name = zone.opt("name") ?: return@mapNotNull null
        Zone(
            name = name,
            networks = zone.words("network"),
            input = zone.opt("input") ?: "",
            output = zone.opt("output") ?: "",
            forward = zone.opt("forward") ?: "",
            masq = zone.flag("masq"),
            forwardsTo = forwardings.filter { it.opt("src") == name }.mapNotNull { it.opt("dest") },
        )
    }
    val wanZone = zones.firstOrNull { "wan" in it.networks }?.name
    // Only redirects from the internet are port forwards; a LAN-side DNS redirect is something else.
    val forwards = firewall.ofType("redirect").filter {
        (it.opt("target") ?: "DNAT") == "DNAT" && it.opt("src") == (wanZone ?: "wan")
    }.map { r ->
        PortForward(
            section = r.name,
            name = r.opt("name") ?: r.name,
            protocols = r.words("proto").ifEmpty { listOf("tcp", "udp") }.map { it.lowercase() }.toSet(),
            outsidePort = r.opt("src_dport") ?: "",
            insideIp = r.opt("dest_ip"),
            insidePort = r.opt("dest_port"),
            destZone = r.opt("dest") ?: "lan",
            enabled = r.enabled(),
        )
    }
    val rules = firewall.ofType("rule").map { rule ->
        val name = rule.opt("name") ?: "Unnamed rule"
        TrafficRule(rule.name, rule.index, name, describeRule(rule), rule.enabled(), standard = name.startsWith("Allow-"))
    }
    return FirewallView(zones, forwards, rules, wanZone)
}

/** A rule in one sentence, such as "Allows UDP port 68 from wan to the router". */
fun describeRule(rule: UciSection): String {
    val verb = when (rule.opt("target")?.uppercase()) {
        "ACCEPT" -> "Allows"
        "REJECT" -> "Blocks"
        "DROP" -> "Drops"
        null -> "Matches"
        else -> rule.opt("target")!!.lowercase().replaceFirstChar { it.uppercase() } + "s"
    }
    val protocols = rule.words("proto").map { it.lowercase() }.filter { it != "all" }
    val ports = rule.words("dest_port")
    val icmpTypes = rule.words("icmp_type")
    val what = when {
        protocols == listOf("icmp") && icmpTypes == listOf("echo-request") -> "ping"
        ports.isNotEmpty() -> {
            val proto = protocolLabel(protocols.ifEmpty { listOf("tcp", "udp") })
            val many = ports.size > 1 || ports.single().contains('-')
            "$proto ${if (many) "ports" else "port"} ${ports.joinToString(", ")}"
        }
        protocols.isNotEmpty() -> protocolLabel(protocols)
        else -> "traffic"
    }
    return "$verb $what from ${place(rule.opt("src"))} to ${place(rule.opt("dest"))}"
}

private fun protocolLabel(protocols: List<String>): String = protocols.map { it.uppercase() }.let {
    if (it.size <= 1) it.joinToString() else it.dropLast(1).joinToString(", ") + " and " + it.last()
}

private fun place(zone: String?): String = when (zone) {
    null, "" -> "the router"
    "*" -> "any network"
    else -> zone
}

/** RFC 1918 and carrier-grade NAT (100.64.0.0/10) addresses: a WAN address like this sits behind another router. */
fun isPrivateIpv4(address: String): Boolean {
    val octets = address.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    val (a, b) = octets
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)
}
