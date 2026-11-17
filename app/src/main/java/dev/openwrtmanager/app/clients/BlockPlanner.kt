package dev.openwrtmanager.app.clients

import dev.openwrtmanager.app.apply.ChangeSet
import dev.openwrtmanager.app.uci.UciBatch
import dev.openwrtmanager.app.uci.UciConfig

private val MAC = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

fun isMac(value: String): Boolean = MAC.matches(value)

/** Upper case, the way the app shows and stores MACs. */
fun normalMac(value: String): String = value.uppercase()

/**
 * Blocks devices immediately. fw4 accepts established flows in its forward chain before any rule
 * runs, so a plain rule would let open connections carry on. Instead a MAC set ([SET]) holds the
 * blocked devices, and a fixed nftables line ([FILE_CONTENT]) included at the very start of the
 * forward chain drops everything they send. All state is in uci, so a rollback restores exactly the
 * previous blocks; the file never changes and does nothing on its own.
 */
object BlockPlanner {
    const val SET = "owm_blocked"
    const val INCLUDE = "owm_blocked_drop"
    const val FILE_PATH = "/etc/owm/blocked.nft"
    const val FILE_CONTENT = "ether saddr @owm_blocked counter drop\n"

    private const val F = "firewall"

    fun blockedMacs(firewall: UciConfig): List<String> =
        firewall[SET]?.takeIf { it.type == "ipset" }?.list("entry").orEmpty().filter(::isMac).map(::normalMac)

    fun block(firewall: UciConfig, mac: String, deviceName: String): ChangeSet {
        require(isMac(mac)) { "not a MAC address" }
        val target = normalMac(mac)
        val batch = UciBatch()
        val blocked = blockedMacs(firewall)
        if (target !in blocked) {
            if (firewall[SET] == null) {
                batch.section(F, SET, "ipset")
                batch.set(F, SET, "name", SET)
                // `set`, not `add_list`: a second src_mac would turn the set into a concatenated type and break fw4.
                batch.set(F, SET, "match", "src_mac")
            }
            batch.addList(F, SET, "entry", target)
            if (firewall[INCLUDE] == null) {
                batch.section(F, INCLUDE, "include")
                batch.set(F, INCLUDE, "type", "nftables")
                batch.set(F, INCLUDE, "path", FILE_PATH)
                batch.set(F, INCLUDE, "position", "chain-prepend")
                batch.set(F, INCLUDE, "chain", "forward")
            }
        }
        return ChangeSet.of(
            "Block device",
            listOf("Block $deviceName ($target)", "It cannot reach the internet or your other networks, starting now"),
            batch,
        )
    }

    fun unblock(firewall: UciConfig, mac: String, deviceName: String): ChangeSet {
        require(isMac(mac)) { "not a MAC address" }
        val target = normalMac(mac)
        val batch = UciBatch()
        val entries = firewall[SET]?.list("entry").orEmpty()
        val stored = entries.filter { normalMac(it) == target }
        if (stored.isNotEmpty()) {
            if (stored.size == entries.size) {
                // The last one: take the whole mechanism away so fw4 never sees an empty set.
                if (firewall[INCLUDE] != null) batch.delete(F, INCLUDE)
                batch.delete(F, SET)
            } else {
                stored.forEach { batch.delList(F, SET, "entry", it) }
            }
        }
        return ChangeSet.of("Unblock device", listOf("Unblock $deviceName ($target)"), batch)
    }

    /**
     * Writes [FILE_PATH] only when it is missing or different, through a temp file, and lists
     * /etc/owm/ in sysupgrade.conf so an upgrade that keeps settings keeps the file too. fw4 skips an
     * include whose file is missing, so without it blocks would silently stop working.
     */
    fun ensureFileCommand(): String {
        val tmp = "/etc/owm/.blocked.nft.tmp"
        return "mkdir -p /etc/owm && printf '%s\\n' '${FILE_CONTENT.trimEnd()}' > $tmp && " +
            "{ if cmp -s $tmp $FILE_PATH; then rm -f $tmp; else mv $tmp $FILE_PATH; fi; } && " +
            "{ grep -qx '/etc/owm/' /etc/sysupgrade.conf || echo '/etc/owm/' >> /etc/sysupgrade.conf; }"
    }

    fun offloadingOn(firewall: UciConfig): Boolean = firewall.ofType("defaults").any { it.flag("flow_offloading") }
}
