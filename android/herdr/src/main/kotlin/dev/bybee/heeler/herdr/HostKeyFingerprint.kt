package dev.bybee.heeler.herdr

import java.security.MessageDigest
import java.util.Base64

/**
 * The SHA-256 fingerprint of an SSH host key's public-key blob, as OpenSSH
 * prints it (`SHA256:<base64 without padding>`). Equality and hashing use
 * the digest only: the algorithm is presentation metadata, and entries stored
 * before it was recorded carry [UNKNOWN_ALGORITHM].
 */
public class HostKeyFingerprint(digest: ByteArray, public val algorithm: String = UNKNOWN_ALGORITHM) {
    /** The 32-byte SHA-256 digest (defensively copied on both sides). */
    public val digest: ByteArray = digest.copyOf()
        get() = field.copyOf()

    private val digestForEquality: ByteArray = digest.copyOf()

    /** The presentation OpenSSH prints: `SHA256:<base64 without padding>`. */
    public val displayString: String
        get() = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digestForEquality)

    override fun equals(other: Any?): Boolean =
        other is HostKeyFingerprint && digestForEquality.contentEquals(other.digestForEquality)

    override fun hashCode(): Int = digestForEquality.contentHashCode()

    override fun toString(): String = "HostKeyFingerprint($displayString, $algorithm)"

    public companion object {
        /** Marker for legacy entries that predate algorithm-aware persistence. */
        public const val UNKNOWN_ALGORITHM: String = "*"

        /** Fingerprints an SSH public-key blob (`string algorithm, ...` wire encoding). */
        public fun ofPublicKeyBlob(blob: ByteArray): HostKeyFingerprint {
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return HostKeyFingerprint(digest, readAlgorithm(blob) ?: UNKNOWN_ALGORITHM)
        }

        /**
         * Parses the OpenSSH presentation `SHA256:` + 43 characters of unpadded
         * standard base64 (a 32-byte digest). Anything else is null.
         */
        public fun parse(text: String): HostKeyFingerprint? {
            if (!PRESENTATION.matches(text)) return null
            val digest = try {
                Base64.getDecoder().decode(text.removePrefix("SHA256:") + "=")
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (digest.size != 32) return null
            return HostKeyFingerprint(digest)
        }

        private val PRESENTATION = Regex("SHA256:[A-Za-z0-9+/]{43}")

        private fun readAlgorithm(blob: ByteArray): String? {
            if (blob.size < 4) return null
            val length = blob.take(4).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
            if (length <= 0 || length > 256 || blob.size < 4 + length) return null
            val algorithm = blob.copyOfRange(4, 4 + length.toInt()).decodeToString()
            val ascii = algorithm.all { it.code in 0x21..0x7E }
            return if (ascii) algorithm else null
        }
    }
}
