package dev.openwrtmanager.app.admin

import dev.openwrtmanager.app.ssh.ExecResult
import dev.openwrtmanager.app.ssh.SshExecutor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackupTest {
    private val token = "0123456789ab"

    private fun gzipLike(size: Int) = byteArrayOf(0x1f, 0x8b.toByte()) + Random(7).nextBytes(size)

    @Test
    fun backup_round_trips_bytes() {
        val bytes = gzipLike(20_000)
        assertArrayEquals(bytes, decodeBackup(encodeBackup(bytes)))
        assertArrayEquals("line breaks from the router are fine", bytes, decodeBackup(encodeBackup(bytes).chunked(76).joinToString("\n") + "\n"))
        assertNull("not base64", decodeBackup("!!!"))
        assertNull("not a gzip archive", decodeBackup(encodeBackup("hello".toByteArray())))
    }

    private class Recorder(private val reply: (String) -> ExecResult) : SshExecutor {
        val sent = mutableListOf<Pair<String, String?>>()
        override suspend fun exec(command: String, timeoutMs: Long, stdin: String?): ExecResult {
            sent += command to stdin
            return reply(command)
        }

        override suspend fun close() = Unit
    }

    @Test
    fun download_decodes_what_the_router_prints() = runTest {
        val bytes = gzipLike(500)
        val ssh = Recorder { ExecResult(0, encodeBackup(bytes) + "\n", "") }
        assertArrayEquals(bytes, SshAdminGateway(ssh, tokens = { token }).downloadBackup())
        assertEquals(backupCommand(token), ssh.sent.single().first)
        assertNull(SshAdminGateway(Recorder { ExecResult(1, "", "no space") }, tokens = { token }).downloadBackup())
    }

    @Test
    fun restore_refuses_a_bad_archive() = runTest {
        val bad = Recorder { ExecResult(4, "", "") }
        assertNull(SshAdminGateway(bad, tokens = { token }).uploadRestore(gzipLike(100)))
        val good = Recorder { ExecResult(0, "OWM_RESTORE_OK\n", "") }
        val gateway = SshAdminGateway(good, tokens = { token })
        assertEquals(token, gateway.uploadRestore(gzipLike(100)))
        val (command, stdin) = good.sent.single()
        assertEquals(restoreUploadCommand(token), command)
        assertTrue(stdin!!.isNotEmpty())
    }

    @Test
    fun oversized_restore_is_refused_before_upload() = runTest {
        val ssh = Recorder { ExecResult(0, "OWM_RESTORE_OK", "") }
        assertNull(SshAdminGateway(ssh, tokens = { token }).uploadRestore(ByteArray(MAX_BACKUP_BYTES + 1)))
        assertNull("not an archive at all", SshAdminGateway(ssh, tokens = { token }).uploadRestore("text".toByteArray()))
        assertTrue(ssh.sent.isEmpty())
    }

    @Test
    fun commands_clean_up() {
        assertTrue(backupCommand(token).contains("trap 'rm -rf /tmp/owm-backup-$token' EXIT"))
        val upload = restoreUploadCommand(token)
        assertTrue("removes a bad upload", upload.contains("rm -f /tmp/owm-restore-$token.tar.gz"))
        assertTrue("checks the archive", upload.contains("tar -tzf /tmp/owm-restore-$token.tar.gz"))
        assertTrue(upload.contains("OWM_RESTORE_OK"))
        val apply = restoreApplyCommand(token)
        assertTrue(apply.contains("sysupgrade -r /tmp/owm-restore-$token.tar.gz"))
        assertTrue(apply.contains("rm -f /tmp/owm-restore-$token.tar.gz"))
        assertTrue("reboot outlives the session", apply.contains("setsid"))
    }

    @Test
    fun a_truncated_archive_fails_on_tars_own_status() {
        // Piping tar into grep would take grep's status: a truncated archive still lists etc/config/ first.
        val upload = restoreUploadCommand(token)
        assertTrue(upload.contains("L=\$(tar -tzf /tmp/owm-restore-$token.tar.gz 2>/dev/null) && "))
        assertFalse(upload.contains("tar -tzf /tmp/owm-restore-$token.tar.gz 2>/dev/null | grep"))
    }

    @Test
    fun the_backups_own_device_name_is_read_from_the_archive() {
        assertTrue(restoreInfoCommand(token).contains("tar -xzOf /tmp/owm-restore-$token.tar.gz etc/config/system"))
        assertEquals("OpenWrt", parseBackupHostname("config system\n\toption hostname 'OpenWrt'\n\toption timezone 'UTC'\n"))
        assertEquals("home-router", parseBackupHostname("\toption hostname home-router\n"))
        assertNull(parseBackupHostname(""))
    }

    @Test
    fun tokens_are_hex_only() {
        for (bad in listOf("", "0123", "0123456789aG", "../../etc/x1")) {
            assertThrows(bad, IllegalArgumentException::class.java) { backupCommand(bad) }
            assertThrows(bad, IllegalArgumentException::class.java) { restoreApplyCommand(bad) }
        }
    }
}
