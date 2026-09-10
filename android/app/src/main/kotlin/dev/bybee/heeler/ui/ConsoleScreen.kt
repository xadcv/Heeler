package dev.bybee.heeler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.bybee.heeler.AppContainer
import dev.bybee.heeler.data.HostRecord
import dev.bybee.heeler.herdr.Agent
import dev.bybee.heeler.herdr.TransportError
import dev.bybee.heeler.herdr.generated.AgentStatus
import dev.bybee.heeler.ssh.JschTransport
import dev.bybee.heeler.ssh.JschTransportConfig
import dev.bybee.heeler.ssh.TofuHostKeyVerifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(container: AppContainer, host: HostRecord, onBack: () -> Unit) {
    val viewModel: ConsoleViewModel = viewModel(
        key = host.id,
        factory = viewModelFactory {
            initializer {
                ConsoleViewModel(host) { confirm ->
                    JschTransport(
                        JschTransportConfig(
                            endpoint = host.endpoint,
                            identity = container.deviceKey.identity(),
                            hostKeyVerifier = TofuHostKeyVerifier(container.knownHosts, confirm = confirm),
                            socketLocation = host.socketLocation,
                        ),
                    )
                }
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { if (state.connection is ConnectionState.Idle) viewModel.connect() }
    ConsoleContent(
        host = host,
        state = state,
        actions = ConsoleActions(
            back = { viewModel.disconnect(); onBack() },
            connect = viewModel::connect,
            refresh = viewModel::refresh,
            select = viewModel::select,
            read = viewModel::read,
            prompt = viewModel::prompt,
            answerHostKey = viewModel::answerHostKey,
            dismissNotice = viewModel::dismissNotice,
        ),
    )
}

/** Everything the Console can ask of its view model, so the stateless content is previewable. */
internal data class ConsoleActions(
    val back: () -> Unit = {},
    val connect: () -> Unit = {},
    val refresh: () -> Unit = {},
    val select: (Agent?) -> Unit = {},
    val read: () -> Unit = {},
    val prompt: (String) -> Unit = {},
    val answerHostKey: (Boolean) -> Unit = {},
    val dismissNotice: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConsoleContent(host: HostRecord, state: ConsoleUiState, actions: ConsoleActions) {
    state.pendingHostKey?.let { pending ->
        AlertDialog(
            onDismissRequest = { actions.answerHostKey(false) },
            title = { Text("New Host key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${pending.endpoint.host}:${pending.endpoint.port} presented a key this device has not seen. Compare the fingerprint with `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the Host before trusting it.")
                    Text(pending.fingerprint.displayString, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { actions.answerHostKey(true) }) { Text("Trust") } },
            dismissButton = { TextButton(onClick = { actions.answerHostKey(false) }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(host.displayName) },
                navigationIcon = { TextButton(onClick = actions.back) { Text("Hosts") } },
                actions = {
                    when (state.connection) {
                        is ConnectionState.Connected -> TextButton(onClick = actions.refresh) { Text("Refresh") }
                        is ConnectionState.Failed -> TextButton(onClick = actions.connect) { Text("Retry") }
                        else -> {}
                    }
                },
            )
        },
        snackbarHost = {
            // The raw Snackbar overload has no outer margin (only the SnackbarData one does), so add
            // the standard 12dp inset ourselves or it renders flush to the screen edges.
            state.notice?.let {
                Snackbar(
                    modifier = Modifier.padding(12.dp),
                    action = { TextButton(onClick = actions.dismissNotice) { Text("Dismiss") } },
                ) { Text(it) }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val connection = state.connection) {
                ConnectionState.Idle, ConnectionState.Connecting -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Connecting to ${host.username}@${host.hostname}…")
                }
                is ConnectionState.Failed -> FailureView(connection.error)
                is ConnectionState.Connected -> {
                    val selected = state.selectedAgent
                    if (selected == null) {
                        AgentList(state.agents, connection.server.version, onSelect = actions.select)
                    } else {
                        AgentDetail(selected, state, onBack = { actions.select(null) }, onRead = actions.read, onPrompt = actions.prompt)
                    }
                }
            }
        }
    }
}

@Composable
private fun FailureView(error: TransportError) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Could not reach herdr", style = MaterialTheme.typography.titleMedium)
        Text(error.message ?: error.toString(), style = MaterialTheme.typography.bodyMedium)
        val hint = when (error) {
            TransportError.AuthenticationFailed -> "Install this device's public key on the Host (Hosts › Device Key)."
            is TransportError.SocketNotFound -> "herdr is not running on the Host, or it serves a different session. Start it with `herdr` over SSH first."
            is TransportError.StreamLocalOpenFailed -> "The Host's sshd must allow stream-local forwarding (`AllowStreamLocalForwarding yes`), and herdr must be listening."
            is TransportError.HostKeyMismatch -> "The Host's key changed. If this is expected, forget the Host and add it again."
            is TransportError.ProtocolVersionMismatch -> "This build needs herdr protocol ${error.supported} or newer; the Host runs ${error.server}."
            else -> null
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun AgentList(agents: List<Agent>, serverVersion: String, onSelect: (Agent) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("herdr $serverVersion · ${agents.size} agent${if (agents.size == 1) "" else "s"}", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelMedium)
        if (agents.isEmpty()) Text("No Agents running. Start one in herdr on the Host and pull Refresh.", Modifier.padding(16.dp))
        LazyColumn {
            items(agents, key = { it.paneID }) { agent ->
                ListItem(
                    headlineContent = { Text(agent.displayName) },
                    supportingContent = { Text(listOfNotNull(agent.title.ifBlank { null }, agent.cwd.ifBlank { null }).joinToString("  ·  ")) },
                    trailingContent = { StatusChip(agent.status) },
                    modifier = Modifier.clickable { onSelect(agent) },
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * Read-only status badge. Colored by status so it stays legible in both color schemes: a disabled
 * AssistChip renders its label at 38% alpha, which is unreadable on the dark surface.
 */
@Composable
private fun StatusChip(status: AgentStatus) {
    val scheme = MaterialTheme.colorScheme
    val (container, label) = when (status) {
        AgentStatus.working -> scheme.primaryContainer to scheme.onPrimaryContainer
        AgentStatus.blocked -> scheme.errorContainer to scheme.onErrorContainer
        AgentStatus.done -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Text(
        status.rawValue,
        color = label,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .semantics { contentDescription = "Status: ${status.rawValue}" }
            .background(container, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun AgentDetail(agent: Agent, state: ConsoleUiState, onBack: () -> Unit, onRead: () -> Unit, onPrompt: (String) -> Unit) {
    var draft by rememberSaveable(agent.paneID) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Agents") }
            Text(agent.displayName, style = MaterialTheme.typography.titleMedium)
            StatusChip(agent.status)
        }
        state.readNotice?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(state.readText.ifEmpty { "(nothing read yet)" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Prompt ${agent.displayName}") }, maxLines = 4)
            Button(enabled = draft.isNotBlank() && !state.promptBusy, onClick = { onPrompt(draft); draft = "" }) { Text("Send") }
        }
        TextButton(onClick = onRead, Modifier.padding(bottom = 8.dp)) { Text("Re-read output") }
    }
}
