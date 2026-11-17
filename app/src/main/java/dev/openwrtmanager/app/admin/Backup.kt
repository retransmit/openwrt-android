package dev.openwrtmanager.app.admin

import java.util.Base64

/** Backups are a few tens of KB; anything bigger than this is not one. */
const val MAX_BACKUP_BYTES = 5 * 1024 * 1024

private val TOKEN = Regex("[0-9a-f]{12}")

private fun checked(token: String): String {
    require(TOKEN.matches(token)) { "bad token" }
    return token
}

private fun restoreFile(token: String) = "/tmp/owm-restore-${checked(token)}.tar.gz"

/**
 * Makes a `sysupgrade -b` archive in a private temp dir and prints it as base64. The router has no
 * base64 tool, but ucode (there since 22.03, fw4 needs it) has b64enc. The dir is removed on exit.
 */
fun backupCommand(token: String): String {
    val dir = "/tmp/owm-backup-${checked(token)}"
    return "trap 'rm -rf $dir' EXIT; mkdir $dir && sysupgrade -b $dir/b.tar.gz >/dev/null 2>&1 && " +
        "ucode -l fs -e 'print(b64enc(fs.readfile(\"$dir/b.tar.gz\")))'"
}

/**
 * Reads base64 from standard input into a temp file and checks it is a configuration archive
 * (it lists etc/config/). Prints OWM_RESTORE_OK, or removes the file and fails.
 */
fun restoreUploadCommand(token: String): String {
    val file = restoreFile(token)
    return "ucode -l fs -e 'let b = b64dec(trim(fs.stdin.read(\"all\"))); if (!b) exit(3); fs.writefile(\"$file\", b);' && " +
        // tar's own status must count: a truncated archive still lists etc/config/ before it fails.
        "L=\$(tar -tzf $file 2>/dev/null) && echo \"\$L\" | grep -q '^etc/config/' && echo OWM_RESTORE_OK || { rm -f $file; exit 4; }"
}

/** Restores the uploaded archive, removes it, and reboots in a detached process so the reply gets back first. */
fun restoreApplyCommand(token: String): String {
    val file = restoreFile(token)
    return "[ -f $file ] || exit 5; sysupgrade -r $file; rc=\$?; rm -f $file; [ \$rc -eq 0 ] || exit \$rc; " +
        "setsid sh -c 'sleep 2; reboot' >/dev/null 2>&1 </dev/null &"
}

fun discardRestoreCommand(token: String): String = "rm -f ${restoreFile(token)}"

fun encodeBackup(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/** Null unless the text is base64 of a gzip archive. */
fun decodeBackup(text: String): ByteArray? {
    val bytes = try {
        Base64.getMimeDecoder().decode(text.trim())
    } catch (e: IllegalArgumentException) {
        return null
    }
    return bytes.takeIf { isGzip(it) }
}

fun isGzip(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
