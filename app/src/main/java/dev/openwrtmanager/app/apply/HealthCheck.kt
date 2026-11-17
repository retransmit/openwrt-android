package dev.openwrtmanager.app.apply

import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.ssh.SshExecutor
import kotlinx.coroutines.delay

sealed interface HealthProblem {
    val iface: String

    data class Down(override val iface: String) : HealthProblem
    data class NoDhcp(override val iface: String) : HealthProblem
}

fun problemMessage(problem: HealthProblem): String = when (problem) {
    is HealthProblem.Down -> "${problem.iface} is not up. Open the Network tab to check its ports."
    is HealthProblem.NoDhcp -> "${problem.iface} has no DHCP, so devices cannot get an address."
}

/**
 * Looks at networks a change just created: is the interface up, and is dnsmasq handing out
 * addresses on it. It only reports; by now the change is confirmed and nothing is undone.
 */
class HealthCheck(private val ssh: SshExecutor, private val retryDelayMs: Long = 3_000) {

    suspend fun check(interfaces: List<String>): List<HealthProblem> = try {
        interfaces.filter { NAME.matches(it) }.mapNotNull { name ->
            // netifd and dnsmasq may still be starting, so a problem has to be seen twice.
            probe(name) ?: return@mapNotNull null
            delay(retryDelayMs)
            probe(name)
        }
    } catch (e: SshError) {
        emptyList()
    }

    private suspend fun probe(name: String): HealthProblem? {
        val lines = ssh.exec(command(name), timeoutMs = 10_000).stdout.lines().map { it.trim() }
        return when {
            lines.firstOrNull() != "true" -> HealthProblem.Down(name)
            "dhcp" !in lines -> HealthProblem.NoDhcp(name)
            else -> null
        }
    }

    companion object {
        private val NAME = Regex("[A-Za-z0-9_]+")

        fun command(name: String): String =
            "ubus call network.interface.$name status | jsonfilter -e @.up; " +
                "grep -qs 'dhcp-range=set:$name,' /var/etc/dnsmasq.conf.* && echo dhcp"
    }
}
