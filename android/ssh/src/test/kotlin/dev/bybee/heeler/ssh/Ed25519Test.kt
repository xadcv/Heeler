package dev.bybee.heeler.ssh

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ed25519Test {
    // RFC 8032 §7.1 test vector 1: expected values come from the RFC, not from this code.
    private val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val publicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val signatureOfEmpty = hex(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
    )

    @Test
    fun `derives the RFC 8032 public key and signature`() {
        val key = Ed25519(seed)
        assertContentEquals(publicKey, key.publicKey)
        assertContentEquals(signatureOfEmpty, key.sign(ByteArray(0)))
        assertTrue(Ed25519.verify(publicKey, ByteArray(0), signatureOfEmpty))
        assertFalse(Ed25519.verify(publicKey, byteArrayOf(1), signatureOfEmpty))
    }

    @Test
    fun `public key blob has the OpenSSH layout`() {
        val blob = Ed25519(seed).publicKeyBlob
        assertContentEquals(hex("0000000b") + "ssh-ed25519".toByteArray() + hex("00000020") + publicKey, blob)
        // Base64 and fingerprint computed independently (python: base64 + hashlib).
        assertEquals(
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINdamAGCsQq31Uv+08lkBzoO4XLz2qYjJa8CGmj3B1Ea heeler",
            Ed25519(seed).authorizedKeysLine("heeler"),
        )
        assertEquals(
            "SHA256:bbXpuKG6zhzdmnxq256TlqzFBzRl2f6OOg722cYNbU8",
            Ed25519Identity("test", Ed25519(seed)).fingerprint.displayString,
        )
    }

    @Test
    fun `identity frames the signature the way JSch expects`() {
        val identity = Ed25519Identity("test", Ed25519(seed))
        val framed = identity.getSignature(ByteArray(0))
        assertContentEquals(
            hex("0000000b") + "ssh-ed25519".toByteArray() + hex("00000040") + signatureOfEmpty,
            framed,
        )
        assertEquals("ssh-ed25519", identity.algName)
        assertContentEquals(Ed25519(seed).publicKeyBlob, identity.publicKeyBlob)
    }

    @Test
    fun `generated seeds are 32 bytes and distinct`() {
        val a = Ed25519.generateSeed()
        val b = Ed25519.generateSeed()
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(b))
        val message = "hello".toByteArray()
        assertTrue(Ed25519.verify(Ed25519(a).publicKey, message, Ed25519(a).sign(message)))
        assertFalse(Ed25519.verify(Ed25519(b).publicKey, message, Ed25519(a).sign(message)))
    }

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
