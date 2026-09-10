package dev.bybee.heeler.ssh

import dev.bybee.heeler.herdr.TerminalAttachRequest
import dev.bybee.heeler.herdr.TerminalAttachTarget
import dev.bybee.heeler.herdr.TransportError
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachCommandTest {
    // The literal the iOS transport produces (Sources/Heeler/Transport/HeelerSSHTransport.swift,
    // pinned in TerminalAttachTests.execUsesTheSocketScopeAndTakeoverFlag). Spelled out here rather
    // than rebuilt from the Kotlin constants so drift between the two ports fails this test.
    private val pathExport = "export PATH=\"\$PATH:\$HOME/.local/bin:" +
        "\${MISE_DATA_DIR:-\${XDG_DATA_HOME:-\$HOME/.local/share}/mise}/shims:" +
        "\$HOME/.linuxbrew/bin:\$HOME/.cargo/bin:\$HOME/.bun/bin:" +
        "/opt/homebrew/bin:/usr/local/bin:/home/linuxbrew/.linuxbrew/bin\""

    @Test
    fun `agent attach with takeover matches the iOS command byte for byte`() {
        val command = RemoteCommands.attach(
            TerminalAttachRequest(TerminalAttachTarget.AgentPane("w1:p1"), cols = 80, rows = 24, takeover = true),
            "/home/u/.config/herdr/sessions/dev/herdr.sock",
        )
        assertEquals(
            "/bin/sh -c '$pathExport; " +
                "export HERDR_SOCKET_PATH=\"\$2\"; " +
                "printf \"\\033_heeler-attach\\033\\134\"; " +
                "exec herdr agent attach \"\$1\" --takeover' attach " +
                "'w1:p1' '/home/u/.config/herdr/sessions/dev/herdr.sock'",
            command,
        )
        assertFalse(command.endsWith("\n"))
    }

    @Test
    fun `terminal target selects terminal attach without takeover`() {
        val command = RemoteCommands.attach(
            TerminalAttachRequest(TerminalAttachTarget.Terminal("tA"), cols = 80, rows = 24),
            "/tmp/fake.sock",
        )
        assertTrue(command.contains("exec herdr terminal attach \"\$1\"' attach 'tA' '/tmp/fake.sock'"))
        assertFalse(command.contains("--takeover"))
    }

    @Test
    fun `unquotable targets and socket paths are refused`() {
        for (target in listOf("", "a'b", "a\\b", "a\nb", "a\u007Fb")) {
            assertFailsWith<TransportError.ChannelFailed>(target) {
                RemoteCommands.attach(TerminalAttachRequest(TerminalAttachTarget.AgentPane(target), 80, 24), "/tmp/x.sock")
            }
        }
        assertFailsWith<TransportError.ChannelFailed> {
            RemoteCommands.attach(TerminalAttachRequest(TerminalAttachTarget.AgentPane("w1:p1"), 80, 24), "relative.sock")
        }
        assertFailsWith<TransportError.ChannelFailed> { RemoteCommands.socketProbe("/tmp/it's.sock") }
        assertEquals("test -S '/tmp/a b.sock'", RemoteCommands.socketProbe("/tmp/a b.sock"))
    }

    @Test
    fun `home command prints the marker under POSIX sh`() {
        assertEquals("/bin/sh -c 'printf \"__HEELER_HOME__=%s\\n\" \"\$HOME\"'", RemoteCommands.HOME)
    }

    @Test
    fun `gate withholds everything before the marker and passes the rest through`() {
        val gate = AttachBootstrapGate()
        assertContentEquals(ByteArray(0), gate.admit("rc chatter\r\n".toByteArray()))
        assertFalse(gate.isOpen)
        // Marker split across two reads, with session bytes trailing the second.
        val marker = AttachBootstrapHandshake.marker
        assertContentEquals(ByteArray(0), gate.admit(marker.copyOfRange(0, 5)))
        assertContentEquals("TTY".toByteArray(), gate.admit(marker.copyOfRange(5, marker.size) + "TTY".toByteArray()))
        assertTrue(gate.isOpen)
        assertContentEquals("-OK".toByteArray(), gate.admit("-OK".toByteArray()))
        assertContentEquals(ByteArray(0), gate.flush())
    }

    @Test
    fun `gate hands back withheld noise on flush and caps it at 8 KiB`() {
        val gate = AttachBootstrapGate()
        gate.admit("exec: herdr: not found\r\n".toByteArray())
        assertContentEquals("exec: herdr: not found\r\n".toByteArray(), gate.flush())
        assertContentEquals(ByteArray(0), gate.flush())

        val capped = AttachBootstrapGate()
        capped.admit(ByteArray(10_000) { 'a'.code.toByte() })
        capped.admit("TAIL".toByteArray())
        val kept = capped.flush()
        assertEquals(AttachBootstrapGate.MAXIMUM_WITHHELD_BYTES, kept.size)
        assertContentEquals("TAIL".toByteArray(), kept.copyOfRange(kept.size - 4, kept.size))
    }

    @Test
    fun `printf format spells the marker in octal`() {
        // \033 = ESC, \134 = backslash: the string the remote printf must emit.
        assertEquals("\\033_heeler-attach\\033\\134", AttachBootstrapHandshake.MARKER_PRINTF_FORMAT)
        assertContentEquals(byteArrayOf(0x1B) + "_heeler-attach".toByteArray() + byteArrayOf(0x1B, 0x5C), AttachBootstrapHandshake.marker)
    }
}
