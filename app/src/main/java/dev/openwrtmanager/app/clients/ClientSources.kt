package dev.openwrtmanager.app.clients

import dev.openwrtmanager.app.domain.WifiNetwork
import dev.openwrtmanager.app.router.RouterClient
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.ssh.SshExecutor
import dev.openwrtmanager.app.ssh.shellQuote
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** An unexpired DHCP lease. [name] is null when the device sent none (`*`). [expiresAt] 0 means never. */
data class Lease(val mac: String, val ip: String, val name: String?, val expiresAt: Long)

data class Association(val mac: String, val ifname: String, val signal: Int?, val connectedSeconds: Long?)

/** A device the router exchanged packets with recently, on [device]. */
data class Neighbour(val ip: String, val device: String, val mac: String)

/** Everything read about clients in one refresh. MACs are upper case. */
data class ClientReadings(
    val leases: List<Lease>,
    val associations: List<Association>,
    val neighbours: List<Neighbour>,
    val hintNames: Map<String, String>,
    val wifi: List<WifiNetwork>,
    /** Sources that failed, in words: "leases", "Wi-Fi details", "neighbours". */
    val missing: List<String>,
)

private val json = Json { ignoreUnknownKeys = true }
private val MAC_TEXT = Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")

/** First line is the router's clock in epoch seconds, then dnsmasq's lease file. */
fun parseLeases(output: String): List<Lease> {
    val lines = output.lines()
    val now = lines.firstOrNull()?.trim()?.toLongOrNull() ?: return emptyList()
    return lines.drop(1).mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 4) return@mapNotNull null
        val expiry = fields[0].toLongOrNull() ?: return@mapNotNull null
        if (expiry != 0L && expiry <= now) return@mapNotNull null
        if (!MAC_TEXT.matches(fields[1])) return@mapNotNull null
        Lease(normalMac(fields[1]), fields[2], fields[3].takeIf { it != "*" }, expiry)
    }
}

fun parseAssoclist(text: String, ifname: String): List<Association> = try {
    json.parseToJsonElement(text).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { element ->
        val station = element as? JsonObject ?: return@mapNotNull null
        val mac = (station["mac"] as? JsonPrimitive)?.content?.takeIf { MAC_TEXT.matches(it) } ?: return@mapNotNull null
        Association(
            normalMac(mac),
            ifname,
            (station["signal"] as? JsonPrimitive)?.intOrNull,
            (station["connected_time"] as? JsonPrimitive)?.longOrNull,
        )
    }
} catch (e: IllegalArgumentException) {
    emptyList()
}

private val NEIGHBOUR = Regex("""^(\d{1,3}(?:\.\d{1,3}){3}) dev (\S+) lladdr ([0-9A-Fa-f:]{17})(?: router)? (REACHABLE|STALE|DELAY|PROBE)\b""")

/** IPv4 neighbours with a known MAC in a usable state. */
fun parseNeighbours(output: String): List<Neighbour> = output.lines().mapNotNull { line ->
    val match = NEIGHBOUR.find(line.trim()) ?: return@mapNotNull null
    val (ip, device, mac) = match.destructured
    if (!MAC_TEXT.matches(mac)) return@mapNotNull null
    Neighbour(ip, device, normalMac(mac))
}

/** LuCI's host hints: MAC to name, with the local domain cut off ("Dexel.lan" becomes "Dexel"). */
fun parseHostHints(text: String): Map<String, String> = try {
    json.parseToJsonElement(text).jsonObject.mapNotNull { (mac, value) ->
        if (!MAC_TEXT.matches(mac)) return@mapNotNull null
        val name = ((value as? JsonObject)?.get("name") as? JsonPrimitive)?.content?.substringBefore('.')?.takeIf { it.isNotBlank() }
        name?.let { normalMac(mac) to it }
    }.toMap()
} catch (e: IllegalArgumentException) {
    emptyMap()
}

/** Reads every client source; one failing source never stops the others. Read only. */
class ClientSources(private val ssh: SshExecutor) {

    suspend fun read(): ClientReadings {
        val missing = linkedSetOf<String>()
        val wifi = try {
            RouterClient(ssh).wifiNetworks()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is SshError) throw e
            missing += WIFI
            emptyList()
        }
        val leases = run(CMD_LEASES)?.let(::parseLeases) ?: emptyList<Lease>().also { missing += "leases" }
        val associations = wifi.mapNotNull { it.ifname }.distinct().flatMap { ifname ->
            val command = assoclistCommand(ifname) ?: return@flatMap emptyList()
            run(command)?.let { parseAssoclist(it, ifname) } ?: emptyList<Association>().also { missing += WIFI }
        }
        val neighbours = run(CMD_NEIGH)?.let(::parseNeighbours) ?: emptyList<Neighbour>().also { missing += "neighbours" }
        // Only there when LuCI is installed; its absence is normal.
        val hints = run(CMD_HINTS)?.let(::parseHostHints).orEmpty()
        return ClientReadings(leases, associations, neighbours, hints, wifi, missing.toList())
    }

    private suspend fun run(command: String): String? {
        val result = ssh.exec(command)
        return result.stdout.takeIf { result.exitCode == 0 }
    }

    companion object {
        private const val WIFI = "Wi-Fi details"
        private val SAFE_IFNAME = Regex("[A-Za-z0-9_.-]+")

        const val CMD_LEASES = "date +%s; cat /tmp/dhcp.leases 2>/dev/null"
        const val CMD_NEIGH = "ip -4 neigh show"
        const val CMD_HINTS = "ubus call luci-rpc getHostHints"

        fun assoclistCommand(ifname: String): String? =
            ifname.takeIf { SAFE_IFNAME.matches(it) }?.let { "ubus call iwinfo assoclist ${shellQuote("{\"device\":\"$it\"}")}" }
    }
}
