package dev.openwrtmanager.app.apply

/**
 * Shell text that runs on the router. Every interpolated value is validated, and staged values
 * arrive already shell-quoted from UciBatch, so nothing here needs further quoting.
 *
 * Exit codes of [apply]: 0 committed, 10 could not prepare, 11 another tool has unsaved changes
 * (listed on stdout), 12 a line was refused (named on stderr), 13 another apply is still pending,
 * 20 the commit or reload failed after the timer was armed.
 */
object ApplyScripts {
    const val EXIT_PREPARE = 10
    const val EXIT_FOREIGN = 11
    const val EXIT_REFUSED = 12
    const val EXIT_BUSY = 13
    const val EXIT_COMMIT = 20

    private val CONFIG = Regex("[a-z0-9_]+")
    private val TOKEN = Regex("[a-f0-9]{12}")
    private val PATH = Regex("[A-Za-z0-9_./-]+")
    private val LINE = Regex("(set|add_list|del_list|delete) [A-Za-z0-9_]+\\.[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)?(=.*)?")

    const val EXIT_UPLOAD = 14
    const val END_MARKER = "# OWM_END"

    /**
     * The command that receives a script on its input and runs it. Scripts are uploaded rather than
     * put on the command line because dropbear drops the connection for a command over about 9000
     * bytes. The script is saved first and only run if its last line is the end marker, so an
     * upload that was cut short is never executed in part.
     */
    fun runner(token: String): String {
        require(TOKEN.matches(token)) { "bad token" }
        val file = "/tmp/owm-run-$token.sh"
        return "trap '' HUP; cat > $file; [ \"\$(tail -n 1 $file)\" = '$END_MARKER' ] || { rm -f $file; exit $EXIT_UPLOAD; }; " +
            "sh $file; rc=\$?; rm -f $file; exit \$rc"
    }

    fun workDir(token: String): String {
        require(TOKEN.matches(token)) { "bad token" }
        return "/tmp/owm-apply-$token"
    }

    /**
     * One script that does the whole change on the router, so a dropped connection can never leave
     * it half done: refuse if another tool has unsaved changes, back up the affected files, stage
     * each line as its own checked `uci` command, start a detached timer that restores the backup
     * unless the change is confirmed, and only then commit and reload.
     *
     * `uci batch` is deliberately not used: it reports success even when a line is refused.
     * busybox on OpenWrt has setsid but not nohup.
     */
    fun apply(
        lines: List<String>,
        configs: Set<String>,
        token: String,
        seconds: Int,
        configDir: String = "/etc/config",
        uci: String = "uci",
        reload: String = "reload_config",
    ): String {
        check(lines, configs)
        require(PATH.matches(configDir)) { "bad config dir" }
        require(seconds in 1..600) { "bad timer" }
        val dir = workDir(token)
        val revertAll = configs.joinToString("; ") { "$uci revert $it" }
        val out = mutableListOf<String>()
        // The ssh session may die when the network reloads; this script must still run to its end.
        out += "trap '' HUP"
        // No -p: if this command were ever sent twice, the second run must stop here.
        out += "mkdir $dir 2>/dev/null || exit $EXIT_PREPARE"
        out += "for o in /tmp/owm-apply-*; do [ \"\$o\" != $dir ] && [ -d \"\$o\" ] && [ ! -d \"\$o/lock\" ] && { rmdir $dir; exit $EXIT_BUSY; }; done"
        out += "p=\"\$($uci changes)\"; [ -z \"\$p\" ] || { rmdir $dir; echo \"\$p\"; exit $EXIT_FOREIGN; }"
        for (config in configs) {
            out += "[ -e $configDir/$config ] || touch $configDir/$config"
            out += "cp $configDir/$config $dir/$config || { rm -rf $dir; exit $EXIT_PREPARE; }"
        }
        out += "fail() { echo \"\$1\" >&2; $revertAll; rm -rf $dir; exit $EXIT_REFUSED; }"
        out += stage(lines, uci)
        out += "cat > $dir/rollback.sh <<'OWM_RB'"
        out += "sleep $seconds"
        // Whoever creates the lock directory first wins: the confirm, or this timer. Never both.
        out += "mkdir $dir/lock 2>/dev/null || { rm -rf $dir; exit 0; }"
        for (config in configs) out += "cp $dir/$config $configDir/$config"
        out += revertAll
        out += reload
        out += "rm -f " + configs.joinToString(" ") { "$dir/$it" }
        out += "touch $dir/reverted"
        out += "OWM_RB"
        out += "setsid sh $dir/rollback.sh >/dev/null 2>&1 </dev/null &"
        out += "touch $dir/armed"
        out += configs.joinToString(" && ") { "$uci commit $it" } + " && $reload || exit $EXIT_COMMIT"
        out += END_MARKER
        return out.joinToString("\n")
    }

    /** Stages the change, prints `uci changes`, and un-stages again, all in one script. Never commits. */
    fun preview(lines: List<String>, configs: Set<String>, uci: String = "uci"): String {
        check(lines, configs)
        val revertAll = configs.joinToString("; ") { "$uci revert $it" }
        val out = mutableListOf<String>()
        out += "trap '' HUP"
        out += "p=\"\$($uci changes)\"; [ -z \"\$p\" ] || { echo \"\$p\"; exit $EXIT_FOREIGN; }"
        out += "fail() { echo \"\$1\" >&2; $revertAll; exit $EXIT_REFUSED; }"
        out += stage(lines, uci)
        out += "$uci changes"
        out += revertAll
        out += END_MARKER
        return out.joinToString("\n")
    }

    /**
     * Asks the router what became of an apply. With [confirm] it also tries to keep the change:
     * creating the lock directory is atomic, so the answer is the truth even if it arrives late.
     */
    fun resolve(token: String, confirm: Boolean): String {
        val dir = workDir(token)
        val take = if (confirm) "if mkdir \$d/lock 2>/dev/null; then touch \$d/confirmed; echo OWM_CONFIRMED; el" else ""
        return "d=$dir; [ -d \$d ] || { echo OWM_ABSENT; exit 0; }; [ -e \$d/armed ] || { echo OWM_NOT_ARMED; exit 0; }; " +
            "${take}if [ -e \$d/confirmed ]; then echo OWM_CONFIRMED; elif [ -e \$d/reverted ]; then echo OWM_REVERTED; else echo OWM_BUSY; fi"
    }

    private fun stage(lines: List<String>, uci: String): List<String> = lines.map { line ->
        // The label names what was refused without repeating the value, which may be a password.
        "$uci $line || fail '${line.substringBefore('=')}'"
    }

    private fun check(lines: List<String>, configs: Set<String>) {
        require(configs.isNotEmpty()) { "nothing to commit" }
        configs.forEach { require(CONFIG.matches(it)) { "bad config name: $it" } }
        lines.forEach { require(LINE.matches(it)) { "not a uci batch line" } }
    }
}
