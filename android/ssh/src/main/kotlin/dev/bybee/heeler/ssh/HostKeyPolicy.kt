package dev.bybee.heeler.ssh

import dev.bybee.heeler.herdr.HostKeyFingerprint
import dev.bybee.heeler.herdr.TransportError

/** Where an SSH connection goes and as whom. */
public data class SshEndpoint(val host: String, val port: Int, val username: String)

/**
 * The trusted host keys the app has recorded, keyed by endpoint. The app
 * persists this; tests use an in-memory map.
 */
public interface KnownHostsStore {
    public fun trusted(endpoint: SshEndpoint): HostKeyFingerprint?
    public fun trust(endpoint: SshEndpoint, fingerprint: HostKeyFingerprint)
}

public class InMemoryKnownHostsStore : KnownHostsStore {
    private val entries = java.util.concurrent.ConcurrentHashMap<Pair<String, Int>, HostKeyFingerprint>()
    override fun trusted(endpoint: SshEndpoint): HostKeyFingerprint? = entries[endpoint.host to endpoint.port]
    override fun trust(endpoint: SshEndpoint, fingerprint: HostKeyFingerprint) {
        entries[endpoint.host to endpoint.port] = fingerprint
    }
}

/**
 * Decides whether a presented host key may be used. Implementations throw
 * [TransportError.HostKeyRejected] or [TransportError.HostKeyMismatch]; a
 * normal return means the connection may proceed.
 */
public fun interface HostKeyVerifier {
    public suspend fun verify(endpoint: SshEndpoint, presented: HostKeyFingerprint)
}

/**
 * Trust on first use with fingerprint confirmation: an unknown Host asks the
 * user through [confirm] and is recorded on approval; a known Host must
 * present the recorded key, and a mismatch is a hard failure that leaves the
 * stored fingerprint untouched. A pinned fingerprint (from a Pairing Code)
 * skips the prompt.
 */
public class TofuHostKeyVerifier(
    private val store: KnownHostsStore,
    private val pinned: HostKeyFingerprint? = null,
    private val confirm: suspend (SshEndpoint, HostKeyFingerprint) -> Boolean,
) : HostKeyVerifier {
    override suspend fun verify(endpoint: SshEndpoint, presented: HostKeyFingerprint) {
        val known = store.trusted(endpoint) ?: pinned
        if (known != null) {
            if (known != presented) throw TransportError.HostKeyMismatch(known, presented)
            if (store.trusted(endpoint) == null) store.trust(endpoint, presented)
            return
        }
        if (!confirm(endpoint, presented)) throw TransportError.HostKeyRejected(presented)
        store.trust(endpoint, presented)
    }
}
