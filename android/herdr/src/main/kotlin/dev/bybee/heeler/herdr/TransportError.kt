package dev.bybee.heeler.herdr

/**
 * Transport-level failures: a closed taxonomy so every screen maps errors to
 * user guidance consistently instead of string-matching. Mirrors the Swift
 * `TransportError` case for case; the SSH module maps its library's failures
 * onto these and nothing above the transport ever sees an SSH exception.
 */
public sealed class TransportError(message: String) : Exception(message) {
    /** The SSH server could not be reached: refused, no route, or died before authentication. */
    public data class SshUnreachable(val detail: String) : TransportError("SSH unreachable: $detail")

    /** The Host rejected our credentials. */
    public data object AuthenticationFailed : TransportError("authentication failed")

    /** The stored Ed25519 Device Key cannot be decoded; the user must replace it. */
    public data object DeviceKeyCorrupt : TransportError("device key corrupt")

    /** First connect to an unknown Host and the user declined its fingerprint; nothing was stored. */
    public data class HostKeyRejected(val presented: HostKeyFingerprint) :
        TransportError("host key rejected: ${presented.displayString}")

    /** The Host presented a key that differs from the trusted one. Hard failure. */
    public data class HostKeyMismatch(
        val known: HostKeyFingerprint,
        val presented: HostKeyFingerprint,
    ) : TransportError("host key mismatch: known ${known.displayString}, presented ${presented.displayString}")

    /** The herdr API socket path does not exist on the Host. */
    public data class SocketNotFound(val path: String) : TransportError("herdr socket not found at $path")

    /** `herdr` is not on the exec PATH nor in the well-known prefixes; the socket may still work (#206). */
    public data object HerdrBinaryNotFound : TransportError("herdr binary not found")

    /**
     * The direct-streamlocal open failed and the socket file exists. The SSH
     * library cannot distinguish a listening socket refused by sshd policy from
     * a stale socket file; naming one cause would be fabricated precision.
     */
    public data class StreamLocalOpenFailed(val path: String) :
        TransportError("stream-local open failed for $path")

    /** The server speaks an older herdr protocol than this build's floor. */
    public data class ProtocolVersionMismatch(val server: Long, val supported: Long) :
        TransportError("herdr protocol $server is older than the supported floor $supported")

    /** The remote home directory could not be resolved, so a home-relative socket has no path. */
    public data class HomeDirectoryUnresolvable(val detail: String) :
        TransportError("home directory unresolvable: $detail")

    /** A second events channel was requested while one is live (ADR 0011 headroom). */
    public data object EventsChannelAlreadyOpen : TransportError("events channel already open")

    /** A second interactive terminal was requested while one is live. */
    public data object TerminalChannelAlreadyOpen : TransportError("terminal channel already open")

    /** The request exceeded its deadline; the channel it held was closed. */
    public data object TimedOut : TransportError("timed out")

    /** The request was cancelled before completing; any channel it held was closed. */
    public data object Cancelled : TransportError("cancelled")

    /** The channel produced bytes that do not decode as a herdr response. */
    public data class MalformedResponse(val detail: String) : TransportError("malformed response: $detail")

    /** herdr answered with an error envelope: the request arrived intact and was rejected on its own terms. */
    public data class ApiRejected(val code: String, val apiMessage: String) :
        TransportError("herdr rejected the request ($code): $apiMessage")

    /** The channel failed outside the known shapes; carries the underlying description. */
    public data class ChannelFailed(val detail: String) : TransportError("channel failed: $detail")

    /**
     * Whether reconnecting without user intervention can plausibly recover.
     * Configuration, trust, authentication, and protocol failures stop so the
     * UI can explain the required action. A rejection is retryable because
     * herdr's error codes are open-ended and most describe a target that
     * moved, not a broken setup. `StreamLocalOpenFailed` is configuration
     * class: neither cause it cannot tell apart resolves without the user
     * acting on the Host (ADR 0011).
     */
    public val isRetryable: Boolean
        get() = when (this) {
            is SshUnreachable, TimedOut, Cancelled, is ChannelFailed, is ApiRejected -> true
            AuthenticationFailed, DeviceKeyCorrupt, is HostKeyRejected, is HostKeyMismatch,
            is SocketNotFound, HerdrBinaryNotFound, is StreamLocalOpenFailed,
            is ProtocolVersionMismatch, is HomeDirectoryUnresolvable,
            EventsChannelAlreadyOpen, TerminalChannelAlreadyOpen, is MalformedResponse -> false
        }
}
