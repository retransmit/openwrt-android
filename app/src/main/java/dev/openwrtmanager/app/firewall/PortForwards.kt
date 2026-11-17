package dev.openwrtmanager.app.firewall

import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.clients.Client
import dev.openwrtmanager.app.clients.DeviceConfig
import dev.openwrtmanager.app.clients.Planned
import dev.openwrtmanager.app.clients.Reservations
import dev.openwrtmanager.app.clients.cidrRange
import dev.openwrtmanager.app.clients.ipv4ToLong
import dev.openwrtmanager.app.clients.reservationsOf
import dev.openwrtmanager.app.uci.UciBatch
import dev.openwrtmanager.app.uci.UciConfig
import dev.openwrtmanager.app.wifi.configuredSubnets

/** What the user asked for. [insidePort] null means the same as [outsidePort]. */
data class ForwardInput(val name: String, val protocols: Set<String>, val outsidePort: String, val insidePort: String?, val target: Client)

enum class ForwardProblem {
    BAD_NAME, BAD_PORT, RANGE_MISMATCH, CLASHES_WITH_FORWARD, CLASHES_WITH_ROUTER,
    NO_TARGET_ADDRESS, TARGET_OUTSIDE_LAN, NO_PROTOCOL, CANNOT_RESERVE,
}

/** Port forwards (DNAT redirects from the WAN zone). A target without a fixed address gets one in the same change. */
object PortForwards {
    private const val F = "firewall"
    private val PROTOCOL_ORDER = listOf("tcp", "udp")

    fun add(config: DeviceConfig, input: ForwardInput): Planned<ForwardProblem> = plan(config, input, existing = null)

    fun edit(config: DeviceConfig, existing: PortForward, input: ForwardInput): Planned<ForwardProblem> = plan(config, input, existing)

    fun remove(existing: PortForward): ChangeSet =
        ChangeSet.of("Remove port forward", listOf("Remove the port forward ${existing.name}"), UciBatch().delete(F, existing.section))

    fun setEnabled(existing: PortForward, enabled: Boolean): ChangeSet {
        val batch = UciBatch()
        if (enabled) batch.delete(F, existing.section, "enabled") else batch.set(F, existing.section, "enabled", "0")
        val word = if (enabled) "Turn on" else "Turn off"
        return ChangeSet.of("$word port forward", listOf("$word the port forward ${existing.name}"), batch)
    }

    /** "80" or "6000-6010", inside 1 to 65535; null when not a valid port or range. */
    fun parsePorts(text: String): IntRange? {
        val parts = text.split('-')
        if (parts.size > 2 || parts.any { it.isEmpty() || it.length > 5 || it.any { c -> c !in '0'..'9' } }) return null
        val first = parts[0].toInt()
        val last = parts.getOrNull(1)?.toInt() ?: first
        if (first !in 1..65535 || last !in 1..65535) return null
        if (parts.size == 2 && last <= first) return null
        return first..last
    }

    private fun plan(config: DeviceConfig, input: ForwardInput, existing: PortForward?): Planned<ForwardProblem> {
        val name = input.name.trim()
        if (name.isEmpty() || name.length > 64 || name.any { it.code < 0x20 || it.code == 0x7f }) return Planned.Problem(ForwardProblem.BAD_NAME)
        val protocols = PROTOCOL_ORDER.filter { it in input.protocols }
        if (protocols.isEmpty()) return Planned.Problem(ForwardProblem.NO_PROTOCOL)
        val outside = parsePorts(input.outsidePort) ?: return Planned.Problem(ForwardProblem.BAD_PORT)
        val insideText = input.insidePort?.takeIf { it.isNotEmpty() } ?: input.outsidePort
        val inside = parsePorts(insideText) ?: return Planned.Problem(ForwardProblem.BAD_PORT)
        if (outside.last - outside.first != inside.last - inside.first) return Planned.Problem(ForwardProblem.RANGE_MISMATCH)

        val view = firewallViewOf(config.firewall)
        val clashesWithForward = view.forwards.any { other ->
            other.enabled && other.section != existing?.section && other.protocols.any { it in protocols } &&
                parsePorts(other.outsidePort)?.let { overlaps(it, outside) } == true
        }
        if (clashesWithForward) return Planned.Problem(ForwardProblem.CLASHES_WITH_FORWARD)
        if (routerOpenPorts(config.firewall, view.wanZone ?: "wan").any { (proto, range) -> proto in protocols && overlaps(range, outside) }) {
            return Planned.Problem(ForwardProblem.CLASHES_WITH_ROUTER)
        }

        val mac = input.target.mac
        val reservation = reservationsOf(config.dhcp).firstOrNull { it.mac == mac }
        val ip = reservation?.ip ?: input.target.ip ?: return Planned.Problem(ForwardProblem.NO_TARGET_ADDRESS)
        val iface = interfaceHolding(ip, config.network) ?: return Planned.Problem(ForwardProblem.TARGET_OUTSIDE_LAN)
        if (config.network[iface]?.list("ipaddr").orEmpty().any { it.substringBefore('/') == ip }) return Planned.Problem(ForwardProblem.TARGET_OUTSIDE_LAN)
        val destZone = view.zones.firstOrNull { iface in it.networks }?.name ?: return Planned.Problem(ForwardProblem.TARGET_OUTSIDE_LAN)

        val batch = UciBatch()
        val summary = mutableListOf<String>()
        val label = input.target.name ?: mac
        if (reservation == null) {
            when (val reserved = Reservations.add(config.dhcp, config.network, mac, ip, Reservations.proposeName(input.target.name, mac))) {
                is Planned.Ready -> {
                    batch.append(reserved.change.batch)
                    summary += reserved.change.summary
                }
                is Planned.Problem -> return Planned.Problem(ForwardProblem.CANNOT_RESERVE)
            }
        }
        val section = existing?.section ?: freeSection(config.firewall)
        if (existing == null) batch.section(F, section, "redirect")
        batch.set(F, section, "name", name)
        batch.set(F, section, "src", view.wanZone ?: "wan")
        batch.set(F, section, "src_dport", input.outsidePort)
        batch.set(F, section, "dest", destZone)
        batch.set(F, section, "dest_ip", ip)
        batch.set(F, section, "dest_port", insideText)
        batch.set(F, section, "proto", protocols.joinToString(" "))
        batch.set(F, section, "target", "DNAT")

        val protoLabel = protocols.joinToString(" and ") { it.uppercase() }
        val outsideWord = if (outside.first == outside.last) "port" else "ports"
        val insideWord = if (inside.first == inside.last) "port" else "ports"
        summary.add(0, "Forward $protoLabel $outsideWord ${input.outsidePort} from the internet to $label ($ip) $insideWord $insideText")
        val title = if (existing == null) "Add port forward" else "Change port forward"
        return Planned.Ready(ChangeSet.of(title, summary, batch))
    }

    private fun overlaps(a: IntRange, b: IntRange) = a.first <= b.last && b.first <= a.last

    /** Ports the router itself accepts from the WAN zone, by protocol. */
    private fun routerOpenPorts(firewall: UciConfig, wanZone: String): List<Pair<String, IntRange>> =
        firewall.ofType("rule").filter { rule ->
            rule.opt("enabled") != "0" && rule.opt("src") == wanZone && rule.opt("dest") == null && rule.opt("target")?.uppercase() == "ACCEPT"
        }.flatMap { rule ->
            val protocols = rule.words("proto").map { it.lowercase() }.ifEmpty { PROTOCOL_ORDER }
            rule.words("dest_port").flatMap { port ->
                val range = parsePorts(port) ?: return@flatMap emptyList()
                protocols.map { it to range }
            }
        }

    private fun interfaceHolding(ip: String, network: UciConfig): String? {
        val address = ipv4ToLong(ip) ?: return null
        return network.ofType("interface").firstOrNull { section ->
            configuredSubnets(UciConfig("network", listOf(section))).any { cidr ->
                val (base, size) = cidrRange(cidr) ?: return@any false
                size > 1 && address in base until base + size
            }
        }?.name
    }

    private fun freeSection(firewall: UciConfig): String {
        var n = 1
        while (firewall["owm_fwd_$n"] != null) n++
        return "owm_fwd_$n"
    }
}
