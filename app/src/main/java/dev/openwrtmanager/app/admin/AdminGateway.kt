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

    /** Which package manager the device has; null when neither apk nor opkg is there. */
    suspend fun packageTool(): PackageTool?

    /** Runs a package command and returns what it printed. Null for a refused name. */
    suspend fun runPackage(tool: PackageTool, action: PackageAction, name: String?): CommandOutput?

    /** The device's configuration as a sysupgrade archive; null when it could not be made. */
    suspend fun downloadBackup(): ByteArray?

    /** Uploads and checks an archive; returns the token to apply or discard it, or null when refused. */
    suspend fun uploadRestore(bytes: ByteArray): String?

    /** Restores the uploaded archive and reboots. Cannot be undone. */
    suspend fun applyRestore(token: String): Boolean

    suspend fun discardRestore(token: String)

    /** The device name inside an uploaded backup, to tell which device it came from. */
    suspend fun restoreHostname(token: String): String?

    /** Every uci config file on the device, sorted. */
    suspend fun configs(): List<String>

    suspend fun loadConfig(name: String): UciConfig
}

fun interface AdminGatewayProvider {
    /** Null when no device is connected. */
    fun current(): AdminGateway?
}

class SshAdminGateway(
    private val ssh: SshExecutor,
    private val tokens: () -> String = {
        val bytes = ByteArray(6).also { java.security.SecureRandom().nextBytes(it) }
        bytes.joinToString("") { "%02x".format(it) }
    },
) : AdminGateway {
    override suspend fun downloadBackup(): ByteArray? {
        val result = ssh.exec(backupCommand(tokens()), timeoutMs = 60_000)
        return if (result.exitCode == 0) decodeBackup(result.stdout) else null
    }

    override suspend fun uploadRestore(bytes: ByteArray): String? {
        if (bytes.size > MAX_BACKUP_BYTES || !isGzip(bytes)) return null
        val token = tokens()
        val result = ssh.exec(restoreUploadCommand(token), timeoutMs = 60_000, stdin = encodeBackup(bytes) + "\n")
        return token.takeIf { result.exitCode == 0 && "OWM_RESTORE_OK" in result.stdout }
    }

    override suspend fun applyRestore(token: String): Boolean = ssh.exec(restoreApplyCommand(token), timeoutMs = 60_000).exitCode == 0

    override suspend fun discardRestore(token: String) {
        ssh.exec(discardRestoreCommand(token))
    }

    override suspend fun restoreHostname(token: String): String? = parseBackupHostname(ssh.exec(restoreInfoCommand(token)).stdout)

    override suspend fun configs(): List<String> {
        val result = ssh.exec("ubus call uci configs")
        if (result.exitCode != 0) throw IllegalStateException("uci configs failed")
        return dev.openwrtmanager.app.editor.parseConfigs(result.stdout)
    }

    override suspend fun loadConfig(name: String): UciConfig = dev.openwrtmanager.app.uci.UciClient(ssh).load(name)

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

    override suspend fun packageTool(): PackageTool? = parseTool(ssh.exec(DETECT_TOOL_COMMAND).stdout)

    override suspend fun runPackage(tool: PackageTool, action: PackageAction, name: String?): CommandOutput? {
        val command = packageCommand(tool, action, name) ?: return null
        // Installing and updating fetch from the internet and can take a while.
        val slow = action == PackageAction.UPDATE || action == PackageAction.INSTALL || action == PackageAction.REMOVE
        val result = ssh.exec("$command 2>&1", timeoutMs = if (slow) 180_000 else 30_000)
        return CommandOutput(result.exitCode, result.stdout.trim())
    }
}
