package dev.bybee.heeler.ssh

import dev.bybee.heeler.herdr.RemoteShellPath
import dev.bybee.heeler.herdr.TerminalAttachRequest
import dev.bybee.heeler.herdr.TerminalAttachTarget
import dev.bybee.heeler.herdr.TransportError

/**
 * Where `herdr` actually lives on Hosts that install it via Homebrew,
 * linuxbrew, cargo, mise, or a user-local prefix. SSH exec is not a login
 * shell, so a Host that runs `herdr` interactively can still answer
 * `exec: herdr: not found` (exit 127) on attach (#206). Extra prefixes are
 * appended after the session PATH so an existing `herdr` keeps priority; the
 * expansions are evaluated by the remote `/bin/sh`.
 */
public object HerdrHostPath {
    public const val MISE_SHIMS: String = "\${MISE_DATA_DIR:-\${XDG_DATA_HOME:-\$HOME/.local/share}/mise}/shims"

    public const val EXTRA_PATH: String =
        "\$HOME/.local/bin:$MISE_SHIMS:\$HOME/.linuxbrew/bin:\$HOME/.cargo/bin:\$HOME/.bun/bin:" +
            "/opt/homebrew/bin:/usr/local/bin:/home/linuxbrew/.linuxbrew/bin"

    public const val PATH_EXPORT: String = "export PATH=\"\$PATH:$EXTRA_PATH\""
}

/**
 * The handshake that separates an attach channel's pre-attach noise from the
 * attach session itself. OpenSSH hands every exec request to the account
 * shell as `shell -c command`, which may emit rc chatter before `herdr`
 * starts; the exec command prints this marker (an APC sequence, which
 * terminals must ignore) immediately before `exec`, and everything up to it is
 * withheld from the terminal.
 */
public object AttachBootstrapHandshake {
    public val marker: ByteArray = "\u001B_heeler-attach\u001B\\".toByteArray()

    /** [marker] as a `printf` format, in octal so it survives fish and `/bin/sh`. */
    public const val MARKER_PRINTF_FORMAT: String = "\\033_heeler-attach\\033\\134"
}

/**
 * Holds an attach channel's output back until the bootstrap marker lands.
 * Withheld bytes are buffered rather than dropped: a channel that dies before
 * the handshake has said everything it will ever say in that diagnostic, so
 * [flush] hands it back.
 */
public class AttachBootstrapGate {
    public var isOpen: Boolean = false
        private set

    private var withheld = ByteArray(0)

    /** The bytes the terminal should paint: nothing until the marker arrives, everything after it. */
    public fun admit(bytes: ByteArray): ByteArray {
        if (isOpen) return bytes
        withheld += bytes
        val at = indexOf(withheld, AttachBootstrapHandshake.marker)
        if (at < 0) {
            if (withheld.size > MAXIMUM_WITHHELD_BYTES) {
                withheld = withheld.copyOfRange(withheld.size - MAXIMUM_WITHHELD_BYTES, withheld.size)
            }
            return ByteArray(0)
        }
        isOpen = true
        val session = withheld.copyOfRange(at + AttachBootstrapHandshake.marker.size, withheld.size)
        withheld = ByteArray(0)
        return session
    }

    /** The withheld noise, for a channel that ended before it handshook. Empty once open. */
    public fun flush(): ByteArray {
        if (isOpen) return ByteArray(0)
        return withheld.also { withheld = ByteArray(0) }
    }

    public companion object {
        /** A ceiling for a channel that never handshakes; the tail carries the failure. */
        public const val MAXIMUM_WITHHELD_BYTES: Int = 8 * 1024

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || haystack.size < needle.size) return -1
            outer@ for (start in 0..haystack.size - needle.size) {
                for (offset in needle.indices) {
                    if (haystack[start + offset] != needle[offset]) continue@outer
                }
                return start
            }
            return -1
        }
    }
}

/** Builds the remote exec commands the transport runs; pure so tests can pin the exact strings. */
public object RemoteCommands {
    public const val AGENT_ATTACH: String = "herdr agent attach"
    public const val TERMINAL_ATTACH: String = "herdr terminal attach"
    public const val HOME_MARKER: String = "__HEELER_HOME__="

    /** Prints the remote home under POSIX sh (login shells disagree on `$HOME` expansion, #275). */
    public const val HOME: String = "/bin/sh -c 'printf \"$HOME_MARKER%s\\n\" \"\$HOME\"'"

    /** `test -S`: exit 0 when the socket file exists; the stream-local open diagnostic. */
    public fun socketProbe(socketPath: String): String {
        val quoted = RemoteShellPath.quotedAbsolute(socketPath)
            ?: throw TransportError.ChannelFailed("The remote socket path cannot be quoted safely.")
        return "test -S $quoted"
    }

    /**
     * The attach exec command: extends PATH, exports `HERDR_SOCKET_PATH` so the
     * client socket derives from the selected session, prints the bootstrap
     * marker, and `exec`s the attach command with the target as `$1`.
     */
    public fun attach(request: TerminalAttachRequest, socketPath: String): String {
        val command = when (request.target) {
            is TerminalAttachTarget.AgentPane -> AGENT_ATTACH
            is TerminalAttachTarget.Terminal -> TERMINAL_ATTACH
        }
        val target = request.target.identifier
        val unquotable = target.isEmpty() || target.any { it == '\'' || it == '\\' || it.code < 0x20 || it.code == 0x7F }
        if (unquotable) throw TransportError.ChannelFailed("attach target cannot be quoted for the remote command")
        val quotedSocketPath = RemoteShellPath.quotedAbsolute(socketPath)
            ?: throw TransportError.ChannelFailed("The remote socket path cannot be quoted safely.")
        val takeover = if (request.takeover) " --takeover" else ""
        return "/bin/sh -c '${HerdrHostPath.PATH_EXPORT}; " +
            "export HERDR_SOCKET_PATH=\"\$2\"; " +
            "printf \"${AttachBootstrapHandshake.MARKER_PRINTF_FORMAT}\"; " +
            "exec $command \"\$1\"$takeover' attach " +
            "'$target' $quotedSocketPath"
    }
}
