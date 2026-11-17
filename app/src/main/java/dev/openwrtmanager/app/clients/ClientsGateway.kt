package dev.openwrtmanager.app.clients

import dev.openwrtmanager.app.router.RouterClient
import dev.openwrtmanager.app.ssh.SshExecutor
import dev.openwrtmanager.app.ssh.shellQuote
import dev.openwrtmanager.app.uci.UciClient

/** What the Clients tab reads from, or asks of, the current device. Config changes go through safe apply instead. */
interface ClientsGateway {
    suspend fun readings(): ClientReadings

    suspend fun config(): DeviceConfig

    /** Drops a Wi-Fi client once; it may rejoin straight away. Not a config change. */
    suspend fun reconnect(ifname: String, mac: String): Boolean

    /** Makes sure the fixed block include file is in place. */
    suspend fun ensureBlockFile(): Boolean

    /** The address this app's SSH connection comes from, as the router sees it. */
    suspend fun myAddress(): String?

    suspend fun interfaces(): List<dev.openwrtmanager.app.router.InterfaceStatus>
}

fun interface ClientsGatewayProvider {
    /** Null when no device is connected. */
    fun current(): ClientsGateway?
}

/** OpenWrt 25.x and snapshots treat a DHCP pool as off unless it says `dhcpv4 'server'`. */
fun needsDhcpv4Server(firmware: String): Boolean {
    if ("SNAPSHOT" in firmware.uppercase()) return true
    val major = Regex("""\b(\d{2})\.\d{1,2}""").find(firmware)?.groupValues?.get(1)?.toIntOrNull() ?: return false
    return major >= 25
}

class SshClientsGateway(private val ssh: SshExecutor) : ClientsGateway {
    private val router = RouterClient(ssh)
    private val uci = UciClient(ssh)

    override suspend fun readings(): ClientReadings = ClientSources(ssh).read()

    override suspend fun config(): DeviceConfig = DeviceConfig(
        network = uci.load("network"),
        dhcp = uci.load("dhcp"),
        firewall = uci.load("firewall"),
        l3Devices = router.l3Devices(),
        firewallEnabled = ssh.exec("/etc/init.d/firewall enabled").exitCode == 0,
        dhcpv4Required = needsDhcpv4Server(router.systemInfo().firmware),
    )

    override suspend fun reconnect(ifname: String, mac: String): Boolean {
        val command = reconnectCommand(ifname, mac) ?: return false
        return ssh.exec(command).exitCode == 0
    }

    override suspend fun ensureBlockFile(): Boolean = ssh.exec(BlockPlanner.ensureFileCommand()).exitCode == 0

    override suspend fun interfaces(): List<dev.openwrtmanager.app.router.InterfaceStatus> = router.interfaces()

    override suspend fun myAddress(): String? =
        ssh.exec("echo \$SSH_CLIENT").stdout.trim().substringBefore(' ').takeIf { ipv4ToLong(it) != null }

    companion object {
        private val SAFE_IFNAME = Regex("[A-Za-z0-9_.-]+")

        fun reconnectCommand(ifname: String, mac: String): String? {
            if (!SAFE_IFNAME.matches(ifname) || !isMac(mac)) return null
            val args = """{"addr":"${mac.lowercase()}","reason":5,"deauth":true,"ban_time":0}"""
            return "ubus call hostapd.$ifname del_client ${shellQuote(args)}"
        }
    }
}
