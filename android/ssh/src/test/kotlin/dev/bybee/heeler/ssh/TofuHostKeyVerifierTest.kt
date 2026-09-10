package dev.bybee.heeler.ssh

import dev.bybee.heeler.herdr.HostKeyFingerprint
import dev.bybee.heeler.herdr.TransportError
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TofuHostKeyVerifierTest {
    private val endpoint = SshEndpoint("host.example", 22, "u")
    private val keyA = HostKeyFingerprint(ByteArray(32) { 0xA })
    private val keyB = HostKeyFingerprint(ByteArray(32) { 0xB })

    @Test
    fun `unknown host is recorded only when the user approves`() = runTest {
        val store = InMemoryKnownHostsStore()
        var asked = 0
        val verifier = TofuHostKeyVerifier(store, confirm = { _, presented -> asked++; presented == keyA })

        verifier.verify(endpoint, keyA)
        assertEquals(keyA, store.trusted(endpoint))
        assertEquals(1, asked)

        // Known host: no second prompt.
        verifier.verify(endpoint, keyA)
        assertEquals(1, asked)

        val other = SshEndpoint("other.example", 22, "u")
        val rejected = assertFailsWith<TransportError.HostKeyRejected> { verifier.verify(other, keyB) }
        assertEquals(keyB, rejected.presented)
        assertNull(store.trusted(other))
    }

    @Test
    fun `mismatch is fatal and leaves the recorded key untouched`() = runTest {
        val store = InMemoryKnownHostsStore().apply { trust(endpoint, keyA) }
        val verifier = TofuHostKeyVerifier(store, confirm = { _, _ -> error("must not prompt on a known host") })
        val mismatch = assertFailsWith<TransportError.HostKeyMismatch> { verifier.verify(endpoint, keyB) }
        assertEquals(keyA, mismatch.known)
        assertEquals(keyB, mismatch.presented)
        assertEquals(keyA, store.trusted(endpoint))
    }

    @Test
    fun `a pinned fingerprint skips the prompt and is recorded on match`() = runTest {
        val store = InMemoryKnownHostsStore()
        val verifier = TofuHostKeyVerifier(store, pinned = keyA, confirm = { _, _ -> error("pinned must not prompt") })
        verifier.verify(endpoint, keyA)
        assertEquals(keyA, store.trusted(endpoint))

        val fresh = InMemoryKnownHostsStore()
        val wrong = TofuHostKeyVerifier(fresh, pinned = keyA, confirm = { _, _ -> true })
        assertFailsWith<TransportError.HostKeyMismatch> { wrong.verify(endpoint, keyB) }
        assertNull(fresh.trusted(endpoint))
    }

    @Test
    fun `store entries are keyed by host and port, not user`() {
        val store = InMemoryKnownHostsStore()
        store.trust(endpoint, keyA)
        assertEquals(keyA, store.trusted(endpoint.copy(username = "someone-else")))
        assertNull(store.trusted(endpoint.copy(port = 2222)))
    }
}
