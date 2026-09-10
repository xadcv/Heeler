package dev.bybee.heeler.herdr

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class HostKeyFingerprintTest {
    /** `string "ssh-ed25519" || string <32 zero bytes>`, the OpenSSH public-key blob layout. */
    private val ed25519Blob: ByteArray = run {
        val algorithm = "ssh-ed25519".toByteArray()
        val key = ByteArray(32)
        byteArrayOf(0, 0, 0, algorithm.size.toByte()) + algorithm + byteArrayOf(0, 0, 0, 32) + key
    }

    @Test
    fun `fingerprints the whole blob and reads the algorithm`() {
        val fingerprint = HostKeyFingerprint.ofPublicKeyBlob(ed25519Blob)
        val expected = MessageDigest.getInstance("SHA-256").digest(ed25519Blob)
        assertEquals("ssh-ed25519", fingerprint.algorithm)
        assertEquals("SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(expected), fingerprint.displayString)
        assertEquals(43, fingerprint.displayString.removePrefix("SHA256:").length)
    }

    @Test
    fun `equality ignores the algorithm and uses the digest`() {
        val digest = ByteArray(32) { it.toByte() }
        assertEquals(HostKeyFingerprint(digest, "ssh-ed25519"), HostKeyFingerprint(digest))
        assertEquals(HostKeyFingerprint(digest).hashCode(), HostKeyFingerprint(digest, "ssh-rsa").hashCode())
        assertNotEquals(HostKeyFingerprint(digest), HostKeyFingerprint(ByteArray(32)))
    }

    @Test
    fun `parses the OpenSSH presentation and round-trips`() {
        val original = HostKeyFingerprint.ofPublicKeyBlob(ed25519Blob)
        assertEquals(original, HostKeyFingerprint.parse(original.displayString))
        assertNull(HostKeyFingerprint.parse("MD5:aa:bb"))
        assertNull(HostKeyFingerprint.parse("SHA256:" + "A".repeat(42)))
        assertNull(HostKeyFingerprint.parse("SHA256:" + "A".repeat(44)))
        assertNull(HostKeyFingerprint.parse("SHA256:" + "-".repeat(43)))
    }

    @Test
    fun `a blob without a readable algorithm falls back to the unknown marker`() {
        assertEquals(HostKeyFingerprint.UNKNOWN_ALGORITHM, HostKeyFingerprint.ofPublicKeyBlob(byteArrayOf(1, 2)).algorithm)
        assertEquals(
            HostKeyFingerprint.UNKNOWN_ALGORITHM,
            HostKeyFingerprint.ofPublicKeyBlob(byteArrayOf(0, 0, 0, 9) + "abc".toByteArray()).algorithm,
        )
    }
}
