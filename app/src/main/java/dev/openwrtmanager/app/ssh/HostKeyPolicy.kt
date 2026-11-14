package dev.openwrtmanager.app.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * Trust on first use. Accepts only the fingerprint the user already trusted; otherwise records
 * what the server presented and refuses, leaving the reason in [rejection].
 */
internal class HostKeyPolicy(private val trusted: String?) : HostKeyVerifier {
    @Volatile
    var seen: String? = null
        private set

    @Volatile
    var rejection: SshError? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val actual = fingerprintOf(key)
        seen = actual
        return when (trusted) {
            null -> {
                rejection = SshError.HostKeyUnknown(actual)
                false
            }
            actual -> true
            else -> {
                rejection = SshError.HostKeyChanged(expected = trusted, actual = actual)
                false
            }
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}

/** OpenSSH style fingerprint: "SHA256:" followed by unpadded base64 of the SHA-256 of the key blob. */
internal fun fingerprintOf(key: PublicKey): String {
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = MessageDigest.getInstance("SHA-256").digest(blob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}
