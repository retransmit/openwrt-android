package dev.openwrtmanager.app.ui.admin

import dev.openwrtmanager.app.admin.CommandOutput
import dev.openwrtmanager.app.admin.PackageAction
import dev.openwrtmanager.app.testing.Fixtures
import dev.openwrtmanager.app.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    fun picked_files_are_read_up_to_the_limit() {
        assertArrayEquals(ByteArray(10) { it.toByte() }, readAtMost(ByteArray(10) { it.toByte() }.inputStream(), 10))
        assertNull(readAtMost(ByteArray(11).inputStream(), 10))
        assertEquals(20_000, readAtMost(ByteArray(20_000).inputStream(), 5 * 1024 * 1024)!!.size)
    }

    @Test
    fun backup_file_name() {
        assertEquals("backup-OpenWrt-2026-11-18.tar.gz", backupFileName("OpenWrt", LocalDate.of(2026, 11, 18)))
        assertEquals("backup-device-2026-01-02.tar.gz", backupFileName("", LocalDate.of(2026, 1, 2)))
    }

    private fun backup() = BackupViewModel { gateway }.also { it.load() }

    @Test
    fun download_returns_the_archive() = runTest {
        val vm = backup()
        assertArrayEquals(gateway.backup, vm.download())
        gateway.backup = null
        assertNull(vm.download())
        assertEquals("The device could not make a backup.", vm.ui.value.message)
    }

    @Test
    fun restore_needs_the_typed_name() = runTest {
        val vm = backup()
        assertEquals(false, vm.restore(byteArrayOf(0x1f, 0x8b.toByte()), "openwrt"))
        assertTrue(gateway.uploads.isEmpty())
        assertEquals("Type the device name exactly to confirm.", vm.ui.value.message)
    }

    @Test
    fun restore_reports_a_bad_archive() = runTest {
        gateway.uploadToken = null
        val vm = backup()
        assertEquals(false, vm.restore(byteArrayOf(0x1f, 0x8b.toByte()), "OpenWrt"))
        assertTrue(gateway.applied.isEmpty())
        assertEquals("This file is not a backup this device can restore. Nothing was changed.", vm.ui.value.message)
    }

    @Test
    fun restore_applies_after_a_good_upload() = runTest {
        val vm = backup()
        assertEquals(true, vm.restore(byteArrayOf(0x1f, 0x8b.toByte()), "OpenWrt"))
        assertEquals(listOf("0123456789ab"), gateway.applied)
        assertEquals("Restored. The device is rebooting; the app reconnects when it is back.", vm.ui.value.message)
    }
}
