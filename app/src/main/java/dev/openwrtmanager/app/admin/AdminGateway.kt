package dev.openwrtmanager.app.admin

import dev.openwrtmanager.app.ssh.SshExecutor
import dev.openwrtmanager.app.uci.UciConfig

/** Reads and the few non-config actions of the admin screens. Config changes go through safe apply. */
interface AdminGateway {
    suspend fun services(): List<Service>

    /** True when the router accepted the action. */
    suspend fun runService(name: String, action: ServiceAction): Boolean

    suspend fun log(kind: LogKind): List<String>

    suspend fun systemConfig(): UciConfig

    /** Sends the password on standard input only. False when it is not valid or passwd failed. */
    suspend fun setRootPassword(password: String): PasswordResult

    suspend fun reboot(): Boolean
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

    override suspend fun systemConfig(): UciConfig = dev.openwrtmanager.app.uci.UciClient(ssh).load("system")

    /** Only a reply from the router decides; a dropped link means it may or may not have changed. */
    override suspend fun setRootPassword(password: String): PasswordResult {
        if (!isValidPassword(password)) return PasswordResult.REFUSED
        val result = try {
            ssh.exec(PASSWORD_COMMAND, timeoutMs = 20_000, stdin = password + "\n")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return PasswordResult.UNKNOWN
        }
        return when (result.exitCode) {
            0 -> PasswordResult.CHANGED
            -1 -> PasswordResult.UNKNOWN
            else -> PasswordResult.REFUSED
        }
    }

    override suspend fun reboot(): Boolean = ssh.exec("ubus call system reboot").exitCode == 0
}
