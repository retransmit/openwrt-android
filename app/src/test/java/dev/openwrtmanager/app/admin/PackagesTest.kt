package dev.openwrtmanager.app.admin

import dev.openwrtmanager.app.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagesTest {
    @Test
    fun names_with_hyphens_split_at_the_version() {
        val packages = parseApkList(Fixtures.read("apk_installed.txt"))
        assertTrue(packages.isNotEmpty())
        assertTrue(packages.all { it.installed })
        assertEquals(Package("apk-mbedtls", "3.0.5-r4", true), packages.first { it.name == "apk-mbedtls" })
        assertEquals(Package("ath11k-firmware-ipq9574", "2024.10.14~15f05012-r2", true), packages.first { it.name == "ath11k-firmware-ipq9574" })
        assertEquals(Package("base-files", "1711~fdb162fb81", true), packages.first { it.name == "base-files" })
        assertEquals(Package("libsqlite3-0", "3.47.2-r1", false), parseApkList("libsqlite3-0-3.47.2-r1 aarch64 {x} (blessing)").single())
    }

    @Test
    fun warnings_and_noise_are_skipped() {
        val text = "WARNING: opening from cache https://x/packages.adb: No such file or directory\n\n" +
            "dnsmasq-2.93-r3 aarch64_cortex-a53 {feeds/base/network/services/dnsmasq} (GPL-2.0) [installed]\n"
        assertEquals(listOf(Package("dnsmasq", "2.93-r3", true)), parseApkList(text))
    }

    @Test
    fun opkg_output_is_parsed() {
        val packages = parseOpkgList(Fixtures.read("opkg_installed.txt"), installed = true)
        assertEquals(6, packages.size)
        assertEquals(Package("libsqlite3-0", "3460000-1", true), packages.first { it.name == "libsqlite3-0" })
        assertEquals(Package("luci-app-firewall", "git-24.086.45142-09d5a38", true), packages.last())
        assertEquals(
            listOf(Package("tcpdump", "4.99.4-1", false)),
            parseOpkgList("tcpdump - 4.99.4-1 - Network monitoring and data acquisition tool\n", installed = false),
        )
    }

    @Test
    fun commands_follow_the_manager() {
        assertEquals("apk list --installed", packageCommand(PackageTool.APK, PackageAction.LIST_INSTALLED, null))
        assertEquals("apk update", packageCommand(PackageTool.APK, PackageAction.UPDATE, null))
        assertEquals("apk list --available 'tcpdump'", packageCommand(PackageTool.APK, PackageAction.SEARCH, "tcpdump"))
        assertEquals("apk add 'tcpdump'", packageCommand(PackageTool.APK, PackageAction.INSTALL, "tcpdump"))
        assertEquals("apk del 'tcpdump'", packageCommand(PackageTool.APK, PackageAction.REMOVE, "tcpdump"))
        assertEquals("opkg list-installed", packageCommand(PackageTool.OPKG, PackageAction.LIST_INSTALLED, null))
        assertEquals("opkg update", packageCommand(PackageTool.OPKG, PackageAction.UPDATE, null))
        assertEquals("opkg list '*tcpdump*'", packageCommand(PackageTool.OPKG, PackageAction.SEARCH, "tcpdump"))
        assertEquals("opkg install 'tcpdump'", packageCommand(PackageTool.OPKG, PackageAction.INSTALL, "tcpdump"))
        assertEquals("opkg remove 'tcpdump'", packageCommand(PackageTool.OPKG, PackageAction.REMOVE, "tcpdump"))
        assertEquals("command -v apk >/dev/null && echo apk || { command -v opkg >/dev/null && echo opkg; }", DETECT_TOOL_COMMAND)
        assertEquals(PackageTool.APK, parseTool("apk\n"))
        assertEquals(PackageTool.OPKG, parseTool("opkg"))
        assertNull(parseTool(""))
    }

    @Test
    fun bad_names_are_refused() {
        for (bad in listOf("", "-x", "a b", "a;reboot", "a'b", "*")) {
            assertNull(bad, packageCommand(PackageTool.APK, PackageAction.INSTALL, bad))
            assertNull(bad, packageCommand(PackageTool.OPKG, PackageAction.SEARCH, bad))
        }
        assertNull("an action that needs a name", packageCommand(PackageTool.APK, PackageAction.REMOVE, null))
        assertTrue(isPackageName("luci-i18n-base-en"))
        assertTrue(isPackageName("libstdcpp6"))
        assertTrue(isPackageName("kmod-nft-offload"))
        assertTrue(isPackageName("perl-www+x"))
    }
}
