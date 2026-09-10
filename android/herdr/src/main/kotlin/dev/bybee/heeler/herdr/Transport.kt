package dev.bybee.heeler.herdr

import dev.bybee.heeler.herdr.generated.AgentInfo
import dev.bybee.heeler.herdr.generated.AgentPromptParams
import dev.bybee.heeler.herdr.generated.AgentReadParams
import dev.bybee.heeler.herdr.generated.AgentRenameParams
import dev.bybee.heeler.herdr.generated.AgentSendKeysParams
import dev.bybee.heeler.herdr.generated.AgentStatus
import dev.bybee.heeler.herdr.generated.PaneReadParams
import dev.bybee.heeler.herdr.generated.PaneReadResult
import dev.bybee.heeler.herdr.generated.PaneTarget
import dev.bybee.heeler.herdr.generated.SessionSnapshot
import dev.bybee.heeler.herdr.generated.WorkspaceRenameParams
import kotlinx.coroutines.flow.Flow

/**
 * The app-side abstraction that executes herdr API requests over SSH. UI code
 * talks to Transport, never to SSH primitives (ADR 0011). This is the first
 * Android slice of the iOS `Transport` protocol: the Console and Agent Detail
 * surface. The launch, worktree, staging, notification-plugin, and skills
 * methods are catalogued in ADR 0017 and land with their screens.
 */
public interface Transport {
    /**
     * Verifies the server speaks a protocol version at or above this build's
     * floor and returns its identity. Must be the first herdr API call on
     * every new connection path.
     */
    public suspend fun ping(): ServerInfo

    /** Lists the Agents herdr has detected across all workspaces. */
    public suspend fun listAgents(): List<Agent>

    /** The full session tree in one call: the Console's snapshot source. */
    public suspend fun sessionSnapshot(): SessionSnapshot

    /** Reads a Pane's recent terminal output (visible screen for alternate-screen agents). */
    public suspend fun readPane(params: PaneReadParams): PaneReadResult

    /**
     * Reads an Agent's terminal output. Unlike `pane.read`, history-capable
     * sources fail honestly (`agent_not_idle`) while the Agent is working
     * instead of silently degrading to the visible screen.
     */
    public suspend fun readAgent(params: AgentReadParams): PaneReadResult

    /**
     * Delivers one complete local draft through `agent.prompt`, which types the
     * text and Enter. Omit `wait`: the response acknowledges delivery, and
     * Agent Status events report subsequent work.
     */
    public suspend fun promptAgent(params: AgentPromptParams): Agent

    /** Sends control keys to an Agent (`agent.send_keys`), in herdr's own key spellings. */
    public suspend fun sendAgentKeys(params: AgentSendKeysParams)

    /** Closes a Pane (`pane.close`). */
    public suspend fun closePane(params: PaneTarget)

    /** Renames an Agent; herdr enforces `^[a-z][a-z0-9_-]{0,31}$` and a null name clears it. */
    public suspend fun renameAgent(params: AgentRenameParams)

    /** Relabels a Workspace; herdr accepts any label. */
    public suspend fun renameWorkspace(params: WorkspaceRenameParams)

    /**
     * Opens the Host's single dedicated events channel and subscribes. The
     * request is all-or-nothing on the server: a pane-scoped entry naming a
     * dead pane fails the whole call with `pane_not_found`.
     */
    public suspend fun subscribeToEvents(subscriptions: List<EventSubscription>): HerdrEventStream

    /**
     * Opens the Host's single interactive terminal: a PTY exec of
     * `herdr agent attach` / `herdr terminal attach` whose raw bytes feed a
     * terminal emulator directly.
     */
    public suspend fun attachTerminal(request: TerminalAttachRequest): TerminalAttachSession

    /** Whether the underlying connection is currently established. */
    public val isConnected: Boolean

    /** Tears down every channel and the connection. Idempotent. */
    public suspend fun close()
}

/** herdr server identity as reported by `ping`. */
public data class ServerInfo(
    val version: String,
    val protocolVersion: Long,
    /**
     * The Host speaks a protocol newer than the schema snapshot this build was
     * generated against. Purely advisory: herdr's additions have been additive.
     */
    val exceedsGeneratedProtocol: Boolean = false,
)

/**
 * A coding agent process running inside a herdr Pane: the domain view of the
 * generated wire type [AgentInfo], with wire-level optionality resolved so a
 * missing title never drops the Agent from the list.
 */
public data class Agent(
    val terminalID: String,
    /** The agent program herdr detected ("claude", "codex", ...). */
    val kind: String,
    /** Terminal title with spinner/status glyphs stripped. */
    val title: String,
    val status: AgentStatus,
    val workspaceID: String,
    val tabID: String,
    /** The Pane address used for per-pane subscriptions and attach. */
    val paneID: String,
    val cwd: String,
    val revision: Long,
    /** The server-reported name (`display_agent`, falling back to `name`); null when neither is set. */
    val name: String? = null,
    val terminalTitle: String? = null,
    val paneTitle: String? = null,
    val tokens: Map<String, String> = emptyMap(),
    val stateLabels: Map<String, String> = emptyMap(),
    val stateChangeSeq: Long? = null,
) {
    /** The card's primary label: the server-reported name when present, otherwise the kind. */
    val displayName: String get() = name ?: kind

    public companion object {
        public fun fromWire(info: AgentInfo): Agent = Agent(
            terminalID = info.terminalID,
            kind = info.agent ?: "unknown",
            title = TerminalTitleGlyphs.strip(info.terminalTitleStripped ?: info.terminalTitle ?: ""),
            status = info.agentStatus,
            workspaceID = info.workspaceID,
            tabID = info.tabID,
            paneID = info.paneID,
            cwd = info.cwd ?: "",
            revision = info.revision,
            name = info.displayAgent?.ifEmpty { null } ?: info.name?.ifEmpty { null },
            terminalTitle = info.terminalTitle,
            paneTitle = info.title,
            tokens = info.tokens ?: emptyMap(),
            stateLabels = info.stateLabels ?: emptyMap(),
            stateChangeSeq = info.stateChangeSeq,
        )
    }
}

/** Strips the leading spinner/status glyphs herdr's agents put in terminal titles. */
public object TerminalTitleGlyphs {
    public fun strip(title: String): String {
        var index = 0
        val codePoints = title.codePoints().toArray()
        while (index < codePoints.size && (isStatusGlyph(codePoints[index]) || Character.isWhitespace(codePoints[index]))) {
            index++
        }
        return String(codePoints, index, codePoints.size - index)
    }

    private fun isStatusGlyph(codePoint: Int): Boolean = when (codePoint) {
        in 0x2800..0x28FF -> true // braille spinner frames
        in 0x25D0..0x25D3 -> true // moon-phase spinner
        in 0x25CB..0x25CF -> true // circle frames
        0x2722, 0x2731, 0x2733, 0x2736, 0x273B, 0x273D -> true // spark frames
        else -> false
    }
}

/**
 * Where the herdr API socket lives on a Host. Home-relative locations are
 * resolved against the remote home directory, which the transport resolves
 * over exec once per Host and caches.
 */
public sealed interface HerdrSocketLocation {
    public data object DefaultSession : HerdrSocketLocation
    public data class NamedSession(val name: String) : HerdrSocketLocation
    public data class AbsolutePath(val path: String) : HerdrSocketLocation

    /** The absolute socket path, given the Host's home directory. */
    public fun path(homeDirectory: String): String {
        val home = homeDirectory.removeSuffix("/")
        return when (this) {
            DefaultSession -> "$home/.config/herdr/herdr.sock"
            is NamedSession -> "$home/.config/herdr/sessions/$name/herdr.sock"
            is AbsolutePath -> path
        }
    }
}

/** The grammar herdr enforces for named sessions, kept at the transport boundary. */
public object HerdrSessionName {
    public const val MAXIMUM_UTF8_LENGTH: Int = 64

    public fun isValid(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") return false
        if (name.toByteArray().size > MAXIMUM_UTF8_LENGTH) return false
        return name.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it == '.' || it == '_' || it == '-' }
    }
}

/**
 * Paths passed through the Host's login shell use the conservative quoting
 * subset shared by POSIX shells and fish: spaces are safe inside single
 * quotes; quote, backslash, and control characters are refused.
 */
public object RemoteShellPath {
    public fun quotedAbsolute(path: String): String? {
        if (!path.startsWith("/")) return null
        if (!path.all { it.code >= 0x20 && it.code != 0x7F && it != '\'' && it != '\\' }) return null
        return "'$path'"
    }
}

/**
 * The remote object one interactive PTY attach resolves. Agent attach
 * addresses `herdr agent attach` with a Pane id; an ordinary shell addresses
 * `herdr terminal attach` with the terminal id from `tab.create`.
 */
public sealed interface TerminalAttachTarget {
    public val identifier: String

    public data class AgentPane(override val identifier: String) : TerminalAttachTarget
    public data class Terminal(override val identifier: String) : TerminalAttachTarget
}

/** One interactive terminal request: target, herdr's takeover flag, and the initial PTY geometry. */
public data class TerminalAttachRequest(
    val target: TerminalAttachTarget,
    val cols: Int,
    val rows: Int,
    val takeover: Boolean = false,
)

/**
 * A live attach session over its Host's dedicated terminal channel: raw PTY
 * bytes out, keystrokes and window changes in. [output] completes normally
 * when the remote attach exits or after [end]; it fails if the channel dies.
 */
public interface TerminalAttachSession {
    public val output: Flow<ByteArray>

    /** Forwards raw keystroke bytes to the remote PTY. Fire-and-forget. */
    public fun send(keystrokes: ByteArray)

    /** Propagates a geometry change to the remote PTY via SSH window-change. */
    public fun resize(cols: Int, rows: Int)

    /** Closes the terminal channel and waits for its teardown. Idempotent. */
    public suspend fun end()
}
