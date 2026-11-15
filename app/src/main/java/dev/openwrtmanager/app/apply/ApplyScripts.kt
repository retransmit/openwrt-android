package dev.openwrtmanager.app.apply

/** Shell text that runs on the router. Every interpolated value is validated, so nothing here needs quoting. */
object ApplyScripts {
    private val CONFIG = Regex("[a-z0-9_]+")
    private val TOKEN = Regex("[a-f0-9]{12}")
    private val PATH = Regex("[A-Za-z0-9_./-]+")

    fun workDir(token: String): String {
        require(TOKEN.matches(token)) { "bad token" }
        return "/tmp/owm-apply-$token"
    }

    /**
     * One command that backs up the affected config files, starts a detached timer that restores
     * them unless a `confirmed` marker appears, and only then commits and reloads. It is a single
     * command so a dropped connection can never leave a commit without its timer.
     * busybox on OpenWrt has setsid but not nohup.
     */
    fun armAndCommit(
        configs: Set<String>,
        token: String,
        seconds: Int,
        configDir: String = "/etc/config",
        uci: String = "uci",
        reload: String = "reload_config",
    ): String {
        require(configs.isNotEmpty()) { "nothing to commit" }
        configs.forEach { require(CONFIG.matches(it)) { "bad config name: $it" } }
        require(PATH.matches(configDir)) { "bad config dir" }
        require(seconds in 1..600) { "bad timer" }
        val dir = workDir(token)
        val lines = mutableListOf<String>()
        lines += "d=$dir; mkdir -p \$d || exit 1"
        for (config in configs) {
            lines += "[ -e $configDir/$config ] || touch $configDir/$config"
            lines += "cp $configDir/$config \$d/$config || exit 1"
        }
        lines += "cat > \$d/rollback.sh <<'OWM_RB'"
        lines += "sleep $seconds"
        lines += "[ -e $dir/confirmed ] && { rm -rf $dir; exit 0; }"
        for (config in configs) lines += "cp $dir/$config $configDir/$config"
        for (config in configs) lines += "$uci revert $config"
        lines += reload
        lines += "touch $dir/reverted"
        lines += "OWM_RB"
        lines += "setsid sh \$d/rollback.sh >/dev/null 2>&1 </dev/null &"
        lines += configs.joinToString(" && ") { "$uci commit $it" } + " && $reload"
        return lines.joinToString("\n")
    }

    fun stage(batchScript: String, uci: String = "uci"): String = "$uci -q batch <<'OWM_EOF'\n$batchScript\nOWM_EOF"

    fun confirm(token: String): String = "touch ${workDir(token)}/confirmed && echo OWM_CONFIRMED"

    fun checkReverted(token: String): String = "[ -e ${workDir(token)}/reverted ] && echo OWM_REVERTED"
}
