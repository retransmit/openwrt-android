package dev.openwrtmanager.app.ssh

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the parts of SSH that depend on Android's crypto providers, which JVM tests cannot cover. */
@RunWith(AndroidJUnit4::class)
class OnDeviceSshTest {
    @Before
    fun installProvider() = SshSecurity.install()

    @Test
    fun ed25519_key_can_be_generated_on_android() {
        val key = KeyGenerator.generateEd25519()
        assertTrue(key.publicOpenSsh.startsWith("ssh-ed25519 "))
        assertTrue(key.privatePem.contains("OPENSSH PRIVATE KEY"))
    }

    /**
     * Key exchange with a real SSH server, without logging in. Skipped unless a host is passed:
     *   -Pandroid.testInstrumentationRunnerArguments.liveHost=192.168.1.1
     */
    @Test
    fun key_exchange_with_a_real_router_works_on_android() = runBlocking<Unit> {
        val host = InstrumentationRegistry.getArguments().getString("liveHost").orEmpty()
        assumeTrue("no liveHost argument", host.isNotEmpty())
        val fingerprint = SshjExecutor.probeFingerprint(host, 22)
        assertTrue(fingerprint, fingerprint.startsWith("SHA256:"))
    }
}
