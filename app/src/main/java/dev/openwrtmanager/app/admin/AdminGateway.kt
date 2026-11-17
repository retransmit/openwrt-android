package dev.openwrtmanager.app.admin

import dev.openwrtmanager.app.ssh.SshExecutor

/** Reads and the few non-config actions of the admin screens. Config changes go through safe apply. */
interface AdminGateway {
    suspend fun services(): List<Service>

    /** True when the router accepted the action. */
    suspend fun runService(name: String, action: ServiceAction): Boolean

    suspend fun log(kind: LogKind): List<String>
}

fun interface AdminGatewayProvider {
    /** Null when no device is connected. */
    fun current(): AdminGateway?
}

class SshAdminGateway(private val ssh: SshExecutor) : AdminGateway {
    override suspend fun services(): List<Service> {
        val result = ssh.exec("ubus call rc list")
        return if (result.exitCode == 0) parseServices(result.stdout) else throw IllegalStateException("rc list failed")
    }

    override suspend fun runService(name: String, action: ServiceAction): Boolean {
        if (!actionAllowed(name, action)) return false
        val command = serviceCommand(name, action) ?: return false
        return ssh.exec(command, timeoutMs = 30_000).exitCode == 0
    }

    override suspend fun log(kind: LogKind): List<String> {
        val result = ssh.exec(logCommand(kind))
        if (result.exitCode != 0) throw IllegalStateException("log read failed")
        return result.stdout.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
    }
}
