package dev.openwrtmanager.app.ssh

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.StringWriter
import java.security.SecureRandom
import java.security.Security
import java.util.Base64

/** [privatePem] is an OpenSSH private key; [publicOpenSsh] is one authorized_keys line. */
data class GeneratedKey(val privatePem: String, val publicOpenSsh: String) {
    override fun toString(): String = "GeneratedKey(${publicOpenSsh.take(24)}...)"
}

object KeyGenerator {
    private const val COMMENT = "openwrt-manager"

    fun generateEd25519(): GeneratedKey {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = generator.generateKeyPair()

        val pem = StringWriter()
        PemWriter(pem).use {
            it.writeObject(PemObject("OPENSSH PRIVATE KEY", OpenSSHPrivateKeyUtil.encodePrivateKey(pair.private)))
        }
        val publicBlob = Base64.getEncoder().encodeToString(OpenSSHPublicKeyUtil.encodePublicKey(pair.public))
        return GeneratedKey(privatePem = pem.toString(), publicOpenSsh = "ssh-ed25519 $publicBlob $COMMENT")
    }
}

object SshSecurity {
    /**
     * Android ships a cut-down provider under the name "BC" that lacks the curves SSH needs.
     * Replace it with the full BouncyCastle bundled in the app. Call once at process start.
     */
    fun install() {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
    }
}
