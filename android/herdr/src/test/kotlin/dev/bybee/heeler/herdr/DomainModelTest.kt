package dev.bybee.heeler.herdr

import dev.bybee.heeler.herdr.generated.AgentInfo
import dev.bybee.heeler.herdr.generated.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainModelTest {
    private val wire = AgentInfo(
        agentStatus = AgentStatus.working,
        focused = false,
        paneID = "w1:pT",
        revision = 3,
        tabID = "w1:t1",
        terminalID = "term-1",
        workspaceID = "w1",
        agent = "claude",
        terminalTitle = "⠋ Fixing tests",
        name = "",
        displayAgent = null,
    )

    @Test
    fun `agent resolves wire optionality without dropping the agent`() {
        val agent = Agent.fromWire(wire)
        assertEquals("Fixing tests", agent.title)
        assertEquals("claude", agent.displayName, "empty name falls back to kind")
        assertNull(agent.name)
        assertEquals("", agent.cwd)
        assertEquals(AgentStatus.working, agent.status)

        val named = Agent.fromWire(wire.copy(displayAgent = "reviewer", agent = null))
        assertEquals("reviewer", named.displayName)
        assertEquals("unknown", named.kind)
    }

    @Test
    fun `title glyph stripping removes only leading status glyphs`() {
        assertEquals("build", TerminalTitleGlyphs.strip("✶ ◐ build"))
        assertEquals("", TerminalTitleGlyphs.strip("⠋"))
        assertEquals("a ✶ b", TerminalTitleGlyphs.strip("a ✶ b"))
    }

    @Test
    fun `socket locations resolve against the remote home`() {
        assertEquals("/home/ada/.config/herdr/herdr.sock", HerdrSocketLocation.DefaultSession.path("/home/ada/"))
        assertEquals("/home/ada/.config/herdr/sessions/work/herdr.sock", HerdrSocketLocation.NamedSession("work").path("/home/ada"))
        assertEquals("/tmp/h.sock", HerdrSocketLocation.AbsolutePath("/tmp/h.sock").path("/home/ada"))
    }

    @Test
    fun `session names follow herdr's grammar`() {
        assertTrue(HerdrSessionName.isValid("work-1.dev_x"))
        assertFalse(HerdrSessionName.isValid(""))
        assertFalse(HerdrSessionName.isValid(".."))
        assertFalse(HerdrSessionName.isValid("a b"))
        assertFalse(HerdrSessionName.isValid("é"))
        assertFalse(HerdrSessionName.isValid("a".repeat(65)))
    }

    @Test
    fun `remote shell paths quote conservatively`() {
        assertEquals("'/home/a b/herdr.sock'", RemoteShellPath.quotedAbsolute("/home/a b/herdr.sock"))
        assertNull(RemoteShellPath.quotedAbsolute("relative/path"))
        assertNull(RemoteShellPath.quotedAbsolute("/it's"))
        assertNull(RemoteShellPath.quotedAbsolute("/back\\slash"))
        assertNull(RemoteShellPath.quotedAbsolute("/new\nline"))
    }

    @Test
    fun `retryability splits recoverable from configuration failures`() {
        assertTrue(TransportError.SshUnreachable("refused").isRetryable)
        assertTrue(TransportError.ApiRejected("pane_not_found", "").isRetryable)
        assertFalse(TransportError.StreamLocalOpenFailed("/x").isRetryable)
        assertFalse(TransportError.ProtocolVersionMismatch(16, 17).isRetryable)
        assertFalse(TransportError.AuthenticationFailed.isRetryable)
    }

    @Test
    fun `event kinds table covers every declared kind in both spellings`() {
        for (kind in GlobalEventKind.entries) {
            assertEquals(kind.kind, HerdrEventKind.fromWireName(kind.wireName.replace('.', '_')))
            assertEquals(kind.kind, HerdrEventKind.fromWireName(kind.wireName))
        }
        assertEquals(HerdrEventKind.paneOutputMatched, HerdrEventKind.fromWireName("pane_output_matched"))
        assertFalse(HerdrEventKind.eventsDropped in HerdrEventKind.known)
        assertFalse(GlobalEventKind.PANE_UPDATED in GlobalEventKind.membership)
    }
}
