package dev.openwrtmanager.app.uci

import dev.openwrtmanager.app.router.RouterError
import dev.openwrtmanager.app.ssh.SshExecutor
import dev.openwrtmanager.app.ssh.shellQuote

/** Reads UCI config from the router. It never writes. */
class UciClient(private val ssh: SshExecutor) {

    suspend fun load(config: String): UciConfig {
        require(CONFIG_NAME.matches(config)) { "bad config name: $config" }
        val command = "ubus call uci get ${shellQuote("{\"config\":\"$config\"}")}"
        val result = ssh.exec(command)
        return when {
            result.exitCode == 0 -> parseUciConfig(config, result.stdout)
            result.exitCode == 127 || result.stderr.contains("not found") -> throw RouterError.NotOpenWrt()
            // ubus status 4 is "not found": the config file does not exist, which reads as empty.
            result.exitCode == 4 -> UciConfig(config, emptyList())
            else -> throw RouterError.CommandFailed(command, result.stderr.trim())
        }
    }

    /** Staged but uncommitted changes, in `uci changes` format. Empty when there are none. */
    suspend fun pendingChanges(): String {
        val result = ssh.exec("uci changes")
        if (result.exitCode != 0) throw RouterError.CommandFailed("uci changes", result.stderr.trim())
        return result.stdout.trim()
    }

    private companion object {
        val CONFIG_NAME = Regex("[A-Za-z0-9_]+")
    }
}
