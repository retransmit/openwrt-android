package dev.openwrtmanager.app.ui.devices

import dev.openwrtmanager.app.data.AuthType
import dev.openwrtmanager.app.data.Profile
import dev.openwrtmanager.app.ssh.GeneratedKey
import dev.openwrtmanager.app.ssh.SshError
import dev.openwrtmanager.app.ssh.SshTarget
import dev.openwrtmanager.app.testing.FakeProfileRepository
import dev.openwrtmanager.app.testing.FakeSshExecutor
import dev.openwrtmanager.app.testing.InMemorySecretStore
import dev.openwrtmanager.app.testing.MainDispatcherRule
import dev.openwrtmanager.app.testing.healthyRouter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EditDeviceViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val secrets = InMemorySecretStore()
    private val profiles = FakeProfileRepository(secrets)
    private val targets = mutableListOf<SshTarget>()
    private val executors = mutableListOf<FakeSshExecutor>()
    private var probeCalls = 0

    private var serverFingerprint = "SHA256:server"
    private var probeError: SshError? = null
    private var goodPassword = "hunter2"
    private var keyLoginWorks = true
    private var isOpenWrt = true
    private var crash: RuntimeException? = null
    private val generated = GeneratedKey("-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n", "ssh-ed25519 AAAAC3 openwrt-manager")

    private val installCommand =
        "mkdir -p /etc/dropbear && echo 'ssh-ed25519 AAAAC3 openwrt-manager' >> /etc/dropbear/authorized_keys" +
            " && chmod 600 /etc/dropbear/authorized_keys"

    private fun viewModel() = EditDeviceViewModel(
        profiles = profiles,
        secrets = secrets,
        executorFactory = { target ->
            targets += target
            val executor = if (isOpenWrt) healthyRouter() else FakeSshExecutor()
            executor.respond(installCommand, "")
            val authOk = when (target.authType) {
                AuthType.PASSWORD -> target.secret == goodPassword
                AuthType.KEY -> keyLoginWorks && target.secret == generated.privatePem
            }
            if (!authOk) executor.failWith = SshError.AuthFailed()
            if (target.trustedFingerprint != serverFingerprint) {
                executor.failWith = SshError.HostKeyChanged(target.trustedFingerprint ?: "", serverFingerprint)
            }
            executor.crashWith = crash
            executors += executor
            executor
        },
        fingerprintProbe = { _, _ ->
            probeCalls++
            probeError?.let { throw it }
            serverFingerprint
        },
        keySource = { generated },
    )

    private fun EditDeviceViewModel.fill(
        name: String = "Home",
        host: String = "192.168.1.1",
        port: String = "22",
        secret: String = "hunter2",
    ) {
        onName(name); onHost(host); onPort(port); onSecret(secret)
    }

    private val EditDeviceViewModel.step get() = ui.value.step

    private suspend fun savedProfiles() = profiles.observeAll().first()

    @Test
    fun blank_name_and_host_block_with_field_errors() = runTest {
        val vm = viewModel()
        vm.load(null)
        vm.fill(name = " ", host = "")
        vm.testAndSave()
        assertTrue(vm.ui.value.nameError)
        assertTrue(vm.ui.value.hostError)
        assertEquals(Step.Editing, vm.step)
        assertEquals(0, probeCalls)
    }

    @Test
    fun port_must_be_a_number_between_1_and_65535() = runTest {
        val vm = viewModel()
        vm.load(null)
        for (bad in listOf("", "0", "65536", "ssh", "-1")) {
            vm.fill(port = bad)
            vm.testAndSave()
            assertTrue("port '$bad'", vm.ui.value.portError)
        }
        assertEquals(0, probeCalls)
    }

    @Test
    fun new_device_password_is_required() = runTest {
        val vm = viewModel()
        vm.load(null)
        vm.fill(secret = "")
        vm.testAndSave()
        assertTrue(vm.ui.value.secretError)
        assertEquals(0, probeCalls)
    }

    @Test
    fun new_device_waits_for_host_key_confirmation_and_saves_nothing() = runTest {
        val vm = viewModel()
        vm.load(null)
        vm.fill()
        vm.testAndSave()
        assertEquals(Step.ConfirmHostKey("SHA256:server", changed = false), vm.step)
        assertTrue(savedProfiles().isEmpty())
        assertTrue(targets.isEmpty())
    }

    @Test
    fun rejecting_host_key_returns_to_editing() = runTest {
        val vm = viewModel()
        vm.load(null)
        vm.fill()
        vm.testAndSave()
        vm.rejectHostKey()
        assertEquals(Step.Editing, vm.step)
        assertTrue(savedProfiles().isEmpty())
    }

    @Test
    fun accepting_host_key_saves_profile_secret_fingerprint_and_activates() = runTest {
        val vm = viewModel()
        vm.load(null)
        vm.fill(host = "  192.168.1.1 ", port = "2222")
        vm.testAndSave()
        vm.acceptHostKey()
        val saved = savedProfiles().single()
        assertEquals("Home", saved.name)
        assertEquals("192.168.1.1", saved.host)
        assertEquals(2222, saved.port)
        assertEquals(AuthType.PASSWORD, saved.authType)
        assertEquals("SHA256:server", saved.hostKeyFingerprint)
        assertEquals("hunter2", secrets.get(saved.id))
        assertEquals(saved.id, profiles.observeActiveId().first())
        assertEquals(Step.OfferKeySetup, vm.step)
        assertTrue(executors.all { it.closed })
    }

    @Test
    fun wrong_password_fails_and_saves_nothing() = runTest {
        val vm = viewModel()
        vm.load(null)
        vm.fill(secret = "wrong")
        vm.testAndSave()
        vm.acceptHostKey()
        assertEquals(Step.Failed(FailReason.AUTH), vm.step)
        assertTrue(savedProfiles().isEmpty())
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun unreachable_host_fails_at_probe() = runTest {
        probeError = SshError.Unreachable()
        val vm = viewModel()
        vm.load(null)
        vm.fill()
        vm.testAndSave()
        assertEquals(Step.Failed(FailReason.UNREACHABLE), vm.step)
    }

    @Test
    fun non_openwrt_host_fails_with_its_own_reason() = runTest {
        isOpenWrt = false
        val vm = viewModel()
        vm.load(null)
        vm.fill()
        vm.testAndSave()
        vm.acceptHostKey()
        assertEquals(Step.Failed(FailReason.NOT_OPENWRT), vm.step)
        assertTrue(savedProfiles().isEmpty())
    }

    @Test
    fun editing_after_failure_clears_the_failure() = runTest {
        probeError = SshError.Unreachable()
        val vm = viewModel()
        vm.load(null)
        vm.fill()
        vm.testAndSave()
        vm.onHost("192.168.1.2")
        assertEquals(Step.Editing, vm.step)
    }

    private suspend fun existingProfile(): Long {
        val id = profiles.save(
            Profile(name = "Home", host = "192.168.1.1", authType = AuthType.PASSWORD, hostKeyFingerprint = "SHA256:server"),
        )
        secrets.put(id, "hunter2")
        return id
    }

    @Test
    fun editing_loads_fields_but_never_the_secret() = runTest {
        val id = existingProfile()
        val vm = viewModel()
        vm.load(id)
        assertEquals("Home", vm.ui.value.name)
        assertEquals("192.168.1.1", vm.ui.value.host)
        assertEquals("22", vm.ui.value.port)
        assertEquals("", vm.ui.value.secret)
        assertFalse(vm.ui.value.isNew)
    }

    @Test
    fun editing_with_same_host_key_and_blank_secret_keeps_stored_secret() = runTest {
        val id = existingProfile()
        val vm = viewModel()
        vm.load(id)
        vm.onName("Renamed")
        vm.testAndSave()
        assertEquals(Step.Saved(keyInstalled = false, keySetupFailed = false), vm.step)
        assertEquals("hunter2", targets.single().secret)
        assertEquals("Renamed", profiles.get(id)!!.name)
        assertEquals("hunter2", secrets.get(id))
        assertEquals(1, savedProfiles().size)
    }

    @Test
    fun editing_with_changed_host_key_warns_before_anything_else() = runTest {
        val id = existingProfile()
        serverFingerprint = "SHA256:different"
        val vm = viewModel()
        vm.load(id)
        vm.testAndSave()
        assertEquals(Step.ConfirmHostKey("SHA256:different", changed = true), vm.step)
        assertTrue(targets.isEmpty())
        assertEquals("SHA256:server", profiles.get(id)!!.hostKeyFingerprint)
        vm.acceptHostKey()
        assertEquals("SHA256:different", profiles.get(id)!!.hostKeyFingerprint)
    }

    @Test
    fun key_setup_installs_key_verifies_and_switches_to_key_auth() = runTest {
        val vm = viewModel()
        vm.load(null)
        vm.fill()
        vm.testAndSave()
        vm.acceptHostKey()
        vm.setUpKey()
        val saved = savedProfiles().single()
        assertEquals(Step.Saved(keyInstalled = true, keySetupFailed = false), vm.step)
        assertEquals(AuthType.KEY, saved.authType)
        assertEquals(generated.privatePem, secrets.get(saved.id))
        assertTrue(executors.any { installCommand in it.commands })
        assertEquals(AuthType.KEY, targets.last().authType)
        assertTrue(executors.all { it.closed })
    }

    @Test
    fun failed_key_verification_keeps_password_auth() = runTest {
        keyLoginWorks = false
        val vm = viewModel()
        vm.load(null)
        vm.fill()
        vm.testAndSave()
        vm.acceptHostKey()
        vm.setUpKey()
        val saved = savedProfiles().single()
        assertEquals(Step.Saved(keyInstalled = false, keySetupFailed = true), vm.step)
        assertEquals(AuthType.PASSWORD, saved.authType)
        assertEquals("hunter2", secrets.get(saved.id))
    }

    @Test
    fun skipping_key_setup_finishes() = runTest {
        val vm = viewModel()
        vm.load(null)
        vm.fill()
        vm.testAndSave()
        vm.acceptHostKey()
        vm.skipKeySetup()
        assertEquals(Step.Saved(keyInstalled = false, keySetupFailed = false), vm.step)
        assertNull(secrets.get(99))
    }

    @Test
    fun unexpected_exception_during_the_check_fails_cleanly() = runTest {
        crash = IllegalStateException("unexpected")
        val vm = viewModel()
        vm.load(null)
        vm.fill()
        vm.testAndSave()
        vm.acceptHostKey()
        assertEquals(Step.Failed(FailReason.OTHER), vm.step)
        assertTrue(savedProfiles().isEmpty())
        assertTrue(executors.all { it.closed })
    }
}
