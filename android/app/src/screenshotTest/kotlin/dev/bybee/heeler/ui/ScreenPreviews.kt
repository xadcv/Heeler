package dev.bybee.heeler.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import dev.bybee.heeler.data.HostRecord
import dev.bybee.heeler.herdr.Agent
import dev.bybee.heeler.herdr.HostKeyFingerprint
import dev.bybee.heeler.herdr.ServerInfo
import dev.bybee.heeler.herdr.TransportError
import dev.bybee.heeler.herdr.generated.AgentStatus
import dev.bybee.heeler.ssh.SshEndpoint
import kotlinx.coroutines.CompletableDeferred

/**
 * Reference renders for every Console state, validated host-side by
 * `:app:validateDebugScreenshotTest`. Regenerate with
 * `:app:updateDebugScreenshotTest` after an intentional UI change and review
 * the PNG diff in the commit.
 */
private val devbox = HostRecord(id = "1", name = "devbox", hostname = "devbox.tail1234.ts.net", username = "ada", session = "main")
private val laptop = HostRecord(id = "2", name = "", hostname = "192.168.1.20", port = 2222, username = "ada")

private fun agent(pane: String, kind: String, name: String?, status: AgentStatus, title: String, cwd: String) = Agent(
    terminalID = "t-$pane", kind = kind, title = title, status = status, workspaceID = "w1", tabID = "tab1",
    paneID = pane, cwd = cwd, revision = 3, name = name,
)

private val agents = listOf(
    agent("w1:pA", "claude", "refactor", AgentStatus.working, "Refactoring transport queue", "~/src/heeler"),
    agent("w1:pB", "codex", null, AgentStatus.idle, "codex", "~/src/herdr"),
    agent("w2:pC", "claude", "docs", AgentStatus.blocked, "Waiting for approval", "~/src/heeler/docs"),
    agent("w2:pD", "grok", null, AgentStatus.done, "", "~/scratch"),
)

private val connected = ConsoleUiState(
    connection = ConnectionState.Connected(ServerInfo(version = "0.9.0", protocolVersion = 22)),
    agents = agents,
)

@Preview(name = "light", showBackground = true)
@Preview(name = "dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
private annotation class LightAndDark

@PreviewTest
@LightAndDark
@Composable
fun HostListPopulated() = HeelerTheme {
    HostListScreen(listOf(devbox, laptop), onAdd = {}, onEdit = {}, onOpen = {}, onDeviceKey = {})
}

@PreviewTest
@Preview(showBackground = true)
@Composable
fun HostListEmpty() = HeelerTheme {
    HostListScreen(emptyList(), onAdd = {}, onEdit = {}, onOpen = {}, onDeviceKey = {})
}

@PreviewTest
@Preview(showBackground = true)
@Composable
fun HostEditorNew() = HeelerTheme {
    HostEditorScreen(initial = null, onSave = {}, onDelete = null, onCancel = {})
}

@PreviewTest
@Preview(showBackground = true)
@Composable
fun HostEditorExisting() = HeelerTheme {
    HostEditorScreen(initial = devbox, onSave = {}, onDelete = {}, onCancel = {})
}

@PreviewTest
@Preview(showBackground = true)
@Composable
fun DeviceKey() = HeelerTheme {
    DeviceKeyContent(
        line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINdamAGCsQq31Uv+08lkBzoO4XLz2qYjJa8CGmj3B1Ea heeler-android",
        fingerprint = "SHA256:bbXpuKG6zhzdmnxq256TlqzFBzRl2f6OOg722cYNbU8",
        onBack = {},
        onCopy = {},
    )
}

@PreviewTest
@Preview(showBackground = true)
@Composable
fun ConsoleConnecting() = HeelerTheme {
    ConsoleContent(devbox, ConsoleUiState(connection = ConnectionState.Connecting), ConsoleActions())
}

@PreviewTest
@LightAndDark
@Composable
fun ConsoleAgentList() = HeelerTheme {
    ConsoleContent(devbox, connected, ConsoleActions())
}

@PreviewTest
@Preview(showBackground = true)
@Composable
fun ConsoleNoAgents() = HeelerTheme {
    ConsoleContent(devbox, connected.copy(agents = emptyList()), ConsoleActions())
}

@PreviewTest
@LightAndDark
@Composable
fun ConsoleAgentDetail() = HeelerTheme {
    ConsoleContent(
        devbox,
        connected.copy(
            selectedPaneID = "w1:pA",
            readText = "\$ make test\nRunning 412 tests…\n✔ HerdrWireTests (38)\n✔ TransportQueueTests (17)\n\n● Working: rewriting the request queue to bound stream-local channels at 8…",
            readNotice = "Older output not available",
        ),
        ConsoleActions(),
    )
}

@PreviewTest
@Preview(showBackground = true)
@Composable
fun ConsoleAuthFailed() = HeelerTheme {
    ConsoleContent(devbox, ConsoleUiState(connection = ConnectionState.Failed(TransportError.AuthenticationFailed)), ConsoleActions())
}

@PreviewTest
@Preview(showBackground = true)
@Composable
fun ConsoleSocketMissing() = HeelerTheme {
    ConsoleContent(
        devbox,
        ConsoleUiState(connection = ConnectionState.Failed(TransportError.SocketNotFound("/home/ada/.config/herdr/sessions/main/herdr.sock"))),
        ConsoleActions(),
    )
}

@PreviewTest
@Preview(showBackground = true)
@Composable
fun ConsoleHostKeyPrompt() = HeelerTheme {
    ConsoleContent(
        devbox,
        ConsoleUiState(
            connection = ConnectionState.Connecting,
            pendingHostKey = PendingHostKey(
                SshEndpoint("devbox.tail1234.ts.net", 22, "ada"),
                HostKeyFingerprint(ByteArray(32) { (it * 7).toByte() }, "ssh-ed25519"),
                CompletableDeferred(),
            ),
        ),
        ConsoleActions(),
    )
}

@PreviewTest
@Preview(showBackground = true)
@Composable
fun ConsoleLiveUpdatesNotice() = HeelerTheme {
    ConsoleContent(devbox, connected.copy(notice = "Live updates unavailable: channel failed: events channel closed by the Host"), ConsoleActions())
}
