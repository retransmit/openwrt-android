package dev.openwrtmanager.app.ui.admin

import dev.openwrtmanager.app.admin.CommandOutput
import dev.openwrtmanager.app.admin.PackageAction
import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class PackagesBackupViewModelsTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val gateway = FakeAdminGateway().apply {
        packageOutputs[PackageAction.LIST_INSTALLED] = CommandOutput(0, Fixtures.read("apk_installed.txt"))
    }

    private fun packages() = PackagesViewModel { gateway }.also { it.load() }

    private fun ready(vm: PackagesViewModel) = vm.ui.value

    @Test
    fun installed_list_and_filter() {
        val vm = packages()
        assertEquals(40, ready(vm).installed.size)
        vm.setFilter("ATH11K")
        assertTrue(ready(vm).visible.isNotEmpty())
        assertTrue(ready(vm).visible.all { it.name.contains("ath11k") })
    }

    @Test
    fun install_shows_output_and_refreshes() = runTest {
        gateway.packageOutputs[PackageAction.INSTALL] = CommandOutput(0, "(1/1) Installing tcpdump (4.99.5-r1)\nOK: 30 MiB in 310 packages")
        val vm = packages()
        val listsBefore = gateway.packageRuns.count { it.first == PackageAction.LIST_INSTALLED }
        vm.install("tcpdump")
        assertEquals("(1/1) Installing tcpdump (4.99.5-r1)\nOK: 30 MiB in 310 packages", ready(vm).output)
        assertTrue(gateway.packageRuns.count { it.first == PackageAction.LIST_INSTALLED } > listsBefore)
        gateway.packageOutputs[PackageAction.REMOVE] = CommandOutput(1, "ERROR: tcpdump: not installed")
        vm.remove("tcpdump")
        assertEquals("ERROR: tcpdump: not installed", ready(vm).output)
        assertEquals("The package manager reported a problem. Its output is below.", ready(vm).message)
        vm.clearOutput()
        assertNull(ready(vm).output)
    }

    @Test
    fun busy_is_cleared_when_a_command_fails() = runTest {
        val vm = packages()
        gateway.packageFailure = IllegalStateException("link dropped")
        vm.install("tcpdump")
        assertFalse(ready(vm).busy)
    }

    @Test
    fun essentials_are_never_removed() = runTest {
        val vm = packages()
        vm.remove("dropbear")
        assertTrue(gateway.packageRuns.none { it.first == PackageAction.REMOVE })
        assertEquals("Removing dropbear would cut this app off from the device.", ready(vm).message)
    }

    @Test
    fun the_same_package_from_two_feeds_is_listed_once() = runTest {
        gateway.packageOutputs[PackageAction.SEARCH] = CommandOutput(0, "tcpdump-4.99.5-r1 a {x} (BSD)\ntcpdump-4.99.5-r1 a {y} (BSD)\n")
        val vm = packages()
        vm.search("tcp")
        assertEquals(listOf("tcpdump"), ready(vm).results.map { it.name })
    }

    @Test
    fun search_needs_a_query_and_updates_the_index_once() = runTest {
        val vm = packages()
        vm.search("  ")
        assertEquals("Type part of a package name.", ready(vm).message)
        gateway.packageOutputs[PackageAction.SEARCH] = CommandOutput(0, "tcpdump-4.99.5-r1 aarch64 {x} (BSD-3-Clause)\n")
        vm.search("tcpdump")
        vm.search("tcpdump")
        assertEquals(1, gateway.packageRuns.count { it.first == PackageAction.UPDATE })
        assertEquals(listOf("tcpdump"), ready(vm).results.map { it.name })
    }

    @Test
    fun no_package_manager_is_a_message() {
        gateway.tool = null
        assertEquals("This device has neither apk nor opkg.", ready(packages()).message)
    }

    @Test
    fun backup_file_name() {
        assertEquals("backup-OpenWrt-2026-11-18.tar.gz", backupFileName("OpenWrt", LocalDate.of(2026, 11, 18)))
        assertEquals("backup-device-2026-01-02.tar.gz", backupFileName("", LocalDate.of(2026, 1, 2)))
    }

    @Test
    fun picked_files_are_read_up_to_the_limit() {
        assertArrayEquals(ByteArray(10) { it.toByte() }, readAtMost(ByteArray(10) { it.toByte() }.inputStream(), 10))
        assertNull(readAtMost(ByteArray(11).inputStream(), 10))
        assertEquals(20_000, readAtMost(ByteArray(20_000).inputStream(), 5 * 1024 * 1024)!!.size)
    }

    private fun backup() = BackupViewModel { gateway }.also { it.load() }

    @Test
    fun the_downloaded_backup_waits_in_the_view_model_until_saved() = runTest {
        val vm = backup()
        vm.download()
        assertTrue(vm.ui.value.saveRequested)
        vm.saveRequestHandled()
        assertArrayEquals(gateway.backup, vm.takePendingBackup())
        assertNull("handed over once", vm.takePendingBackup())
        gateway.backup = null
        vm.download()
        assertFalse(vm.ui.value.saveRequested)
        assertEquals("The device could not make a backup.", vm.ui.value.message)
    }

    @Test
    fun a_restore_is_checked_before_it_is_confirmed() = runTest {
        val vm = backup()
        vm.prepareRestore(byteArrayOf(0x1f, 0x8b.toByte()))
        val pending = vm.ui.value.pendingRestore!!
        assertEquals("OpenWrt", pending.backupHostname)
        assertEquals("OpenWrt", pending.deviceHostname)
        assertFalse(pending.otherDevice)
        assertTrue(gateway.applied.isEmpty())
    }

    @Test
    fun a_backup_from_another_device_is_flagged() = runTest {
        gateway.restoreHostname = "Archer"
        val vm = backup()
        vm.prepareRestore(byteArrayOf(0x1f, 0x8b.toByte()))
        assertTrue(vm.ui.value.pendingRestore!!.otherDevice)
    }

    @Test
    fun restore_needs_the_typed_name() = runTest {
        val vm = backup()
        vm.prepareRestore(byteArrayOf(0x1f, 0x8b.toByte()))
        vm.confirmRestore("openwrt")
        assertTrue(gateway.applied.isEmpty())
        assertEquals("Type the device name exactly to confirm.", vm.ui.value.message)
    }

    @Test
    fun restore_reports_a_bad_archive() = runTest {
        gateway.uploadToken = null
        val vm = backup()
        vm.prepareRestore(byteArrayOf(0x1f, 0x8b.toByte()))
        assertNull(vm.ui.value.pendingRestore)
        assertEquals("This file is not a backup this device can restore. Nothing was changed.", vm.ui.value.message)
    }

    @Test
    fun restore_applies_after_confirmation() = runTest {
        val vm = backup()
        vm.prepareRestore(byteArrayOf(0x1f, 0x8b.toByte()))
        vm.confirmRestore("OpenWrt")
        assertEquals(listOf("0123456789ab"), gateway.applied)
        assertNull(vm.ui.value.pendingRestore)
        assertEquals("Restored. The device is rebooting; the app reconnects when it is back.", vm.ui.value.message)
    }

    @Test
    fun cancelling_discards_the_upload() = runTest {
        val vm = backup()
        vm.prepareRestore(byteArrayOf(0x1f, 0x8b.toByte()))
        vm.cancelRestore()
        assertEquals(listOf("0123456789ab"), gateway.discarded)
        assertNull(vm.ui.value.pendingRestore)
    }
}
