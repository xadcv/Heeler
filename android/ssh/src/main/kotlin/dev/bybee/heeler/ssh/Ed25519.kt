package dev.bybee.heeler.ssh

import com.jcraft.jsch.Identity
import dev.bybee.heeler.herdr.HostKeyFingerprint
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64

/** SSH wire-format helpers (RFC 4251 `string`). */
public object SshWire {
    public fun string(bytes: ByteArray): ByteArray {
        val length = bytes.size
        return byteArrayOf(
            (length ushr 24).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte(),
        ) + bytes
    }

    public fun string(text: String): ByteArray = string(text.toByteArray())

    /** `string "ssh-ed25519" || string <32-byte key>`: the public-key blob OpenSSH stores and fingerprints. */
    public fun ed25519PublicKeyBlob(publicKey: ByteArray): ByteArray {
        require(publicKey.size == 32) { "Ed25519 public keys are 32 bytes" }
        return string(Ed25519.ALGORITHM) + string(publicKey)
    }
}

/**
 * Ed25519 as the Device Key algorithm (matching the iOS app's CryptoKit
 * keys). BouncyCastle rather than the platform JCA because Android runtimes do
 * not ship an Ed25519 provider; the seed is what the app wraps with the
 * Android Keystore, and signing never needs the seed to leave this class.
 */
public class Ed25519(seed: ByteArray) {
    init {
        require(seed.size == SEED_BYTES) { "Ed25519 seeds are $SEED_BYTES bytes" }
    }

    private val privateKey = Ed25519PrivateKeyParameters(seed, 0)

    /** The raw 32-byte public key. */
    public val publicKey: ByteArray get() = privateKey.generatePublicKey().encoded

    /** The SSH public-key blob (`string "ssh-ed25519" || string key`). */
    public val publicKeyBlob: ByteArray get() = SshWire.ed25519PublicKeyBlob(publicKey)

    /** One `authorized_keys` line for this key. */
    public fun authorizedKeysLine(comment: String): String =
        "$ALGORITHM ${Base64.getEncoder().encodeToString(publicKeyBlob)} $comment"

    /** The raw 64-byte signature over `data`. */
    public fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    public companion object {
        public const val ALGORITHM: String = "ssh-ed25519"
        public const val SEED_BYTES: Int = 32

        /** A fresh random seed from the platform CSPRNG. */
        public fun generateSeed(random: SecureRandom = SecureRandom()): ByteArray =
            ByteArray(SEED_BYTES).also(random::nextBytes)

        /** Verifies a raw 64-byte signature against a raw 32-byte public key. */
        public fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(data, 0, data.size)
            return verifier.verifySignature(signature)
        }
    }
}

/**
 * A JSch [Identity] whose private half is a signing callback. The SSH layer
 * sees only the public blob and finished signatures, so the seed can stay
 * behind the Android Keystore wrapper (or, in tests, inside [Ed25519]).
 *
 * JSch expects [getSignature] to return the framed SSH signature blob
 * (`string alg || string sig`), exactly as its own `KeyPairEdDSA` does.
 */
public class Ed25519Identity(
    private val name: String,
    publicKey: ByteArray,
    private val signer: (ByteArray) -> ByteArray,
) : Identity {
    private val blob = SshWire.ed25519PublicKeyBlob(publicKey)

    /** The fingerprint an `authorized_keys` audit would show for this identity. */
    public val fingerprint: HostKeyFingerprint get() = HostKeyFingerprint.ofPublicKeyBlob(blob)

    public constructor(name: String, key: Ed25519) : this(name, key.publicKey, key::sign)

    override fun setPassphrase(passphrase: ByteArray?): Boolean = true
    override fun getPublicKeyBlob(): ByteArray = blob.copyOf()
    override fun getSignature(data: ByteArray): ByteArray = getSignature(data, Ed25519.ALGORITHM)
    override fun getSignature(data: ByteArray, alg: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SshWire.string(alg))
        out.write(SshWire.string(signer(data)))
        return out.toByteArray()
    }
    override fun getAlgName(): String = Ed25519.ALGORITHM
    override fun getName(): String = name
    override fun isEncrypted(): Boolean = false
    override fun clear() {}
}
