package dev.bybee.heeler.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bybee.heeler.data.HostRecord
import dev.bybee.heeler.herdr.Agent
import dev.bybee.heeler.herdr.EventSubscription
import dev.bybee.heeler.herdr.GlobalEventKind
import dev.bybee.heeler.herdr.HerdrEventKind
import dev.bybee.heeler.herdr.HostKeyFingerprint
import dev.bybee.heeler.herdr.PaneEventKind
import dev.bybee.heeler.herdr.ServerInfo
import dev.bybee.heeler.herdr.Transport
import dev.bybee.heeler.herdr.TransportError
import dev.bybee.heeler.herdr.generated.AgentPromptParams
import dev.bybee.heeler.herdr.generated.AgentReadParams
import dev.bybee.heeler.herdr.generated.AgentStatus
import dev.bybee.heeler.herdr.generated.PaneReadParams
import dev.bybee.heeler.herdr.generated.ReadSource
import dev.bybee.heeler.ssh.SshEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A host key the user has to approve before the connection proceeds. */
data class PendingHostKey(
    val endpoint: SshEndpoint,
    val fingerprint: HostKeyFingerprint,
    internal val verdict: CompletableDeferred<Boolean>,
)

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val server: ServerInfo) : ConnectionState
    data class Failed(val error: TransportError) : ConnectionState
}

data class ConsoleUiState(
    val connection: ConnectionState = ConnectionState.Idle,
    val agents: List<Agent> = emptyList(),
    val selectedPaneID: String? = null,
    /** The last `agent.read`/`pane.read` text for the selected Agent. */
    val readText: String = "",
    val readNotice: String? = null,
    val pendingHostKey: PendingHostKey? = null,
    val promptBusy: Boolean = false,
    val notice: String? = null,
) {
    val selectedAgent: Agent? get() = agents.firstOrNull { it.paneID == selectedPaneID }
}

/**
 * One Host's Console: connects, snapshots the Agent list, follows lifecycle
 * events for membership changes and status pushes, and reads or prompts the
 * selected Agent. Depends on [Transport] only (ADR 0011).
 */
class ConsoleViewModel(
    val host: HostRecord,
    private val openTransport: (confirmHostKey: suspend (SshEndpoint, HostKeyFingerprint) -> Boolean) -> Transport,
) : ViewModel() {
    private val _state = MutableStateFlow(ConsoleUiState())
    val state: StateFlow<ConsoleUiState> = _state

    private var transport: Transport? = null
    private var eventsJob: Job? = null

    fun connect() {
        if (_state.value.connection is ConnectionState.Connecting) return
        _state.update { it.copy(connection = ConnectionState.Connecting, notice = null) }
        viewModelScope.launch {
            val transport = openTransport(::confirmHostKey).also { this@ConsoleViewModel.transport = it }
            try {
                val server = transport.ping()
                _state.update { it.copy(connection = ConnectionState.Connected(server)) }
                refresh()
                followEvents(transport, server)
            } catch (e: TransportError) {
                _state.update { it.copy(connection = ConnectionState.Failed(e)) }
            }
        }
    }

    fun disconnect() {
        eventsJob?.cancel()
        eventsJob = null
        val current = transport
        transport = null
        viewModelScope.launch { current?.close() }
        _state.update { ConsoleUiState() }
    }

    /** Re-snapshots the Agent list; the recovery path after any membership event or reconnect. */
    fun refresh() {
        val transport = transport ?: return
        viewModelScope.launch {
            try {
                val agents = transport.sessionSnapshot().agents.map(Agent::fromWire)
                _state.update { state ->
                    state.copy(
                        agents = agents,
                        selectedPaneID = state.selectedPaneID?.takeIf { id -> agents.any { it.paneID == id } },
                    )
                }
            } catch (e: TransportError) {
                _state.update { it.copy(notice = e.message) }
            }
        }
    }

    fun select(agent: Agent?) {
        _state.update { it.copy(selectedPaneID = agent?.paneID, readText = "", readNotice = null) }
        if (agent != null) read()
    }

    /**
     * `agent.read` when depth matters, `pane.read` as the fallback: for an
     * alternate-screen agent that is working, `agent.read` fails
     * `agent_not_idle` while `pane.read` still returns the visible screen.
     */
    fun read() {
        val transport = transport ?: return
        val agent = _state.value.selectedAgent ?: return
        viewModelScope.launch {
            try {
                val result = try {
                    transport.readAgent(AgentReadParams(source = ReadSource.recent, target = agent.paneID, lines = 400, stripANSI = true))
                } catch (e: TransportError.ApiRejected) {
                    if (e.code != "agent_not_idle") throw e
                    transport.readPane(PaneReadParams(paneID = agent.paneID, source = ReadSource.visible, stripANSI = true))
                }
                _state.update {
                    it.copy(readText = result.text, readNotice = if (result.truncated) "Older output not available" else null)
                }
            } catch (e: TransportError) {
                _state.update { it.copy(readNotice = e.message) }
            }
        }
    }

    /** `agent.prompt` types the text and Enter; the ack is delivery only, the reply arrives as a status change. */
    fun prompt(text: String) {
        val transport = transport ?: return
        val agent = _state.value.selectedAgent ?: return
        if (text.isBlank()) return
        _state.update { it.copy(promptBusy = true) }
        viewModelScope.launch {
            try {
                transport.promptAgent(AgentPromptParams(target = agent.paneID, text = text))
            } catch (e: TransportError) {
                _state.update { it.copy(notice = e.message) }
            } finally {
                _state.update { it.copy(promptBusy = false) }
            }
        }
    }

    fun answerHostKey(approved: Boolean) {
        _state.value.pendingHostKey?.verdict?.complete(approved)
        _state.update { it.copy(pendingHostKey = null) }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    private suspend fun confirmHostKey(endpoint: SshEndpoint, fingerprint: HostKeyFingerprint): Boolean {
        val verdict = CompletableDeferred<Boolean>()
        _state.update { it.copy(pendingHostKey = PendingHostKey(endpoint, fingerprint, verdict)) }
        return verdict.await()
    }

    /**
     * Subscribes after the snapshot; pane-scoped entries come from that
     * snapshot and die with this connection (a dead pane id fails the whole
     * request with `pane_not_found`). Any failure of the stream degrades to
     * manual refresh rather than taking the Console down.
     */
    private fun followEvents(transport: Transport, server: ServerInfo) {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            val subscriptions = buildList {
                GlobalEventKind.membership
                    .filter { it != GlobalEventKind.WORKSPACE_REORDERED || server.protocolVersion >= 19 }
                    .forEach { add(EventSubscription.Global(it)) }
                _state.value.agents.forEach { add(EventSubscription.Pane(PaneEventKind.AGENT_STATUS_CHANGED, it.paneID)) }
            }
            try {
                val stream = transport.subscribeToEvents(subscriptions)
                try {
                    stream.events.collect { event -> handle(event.kind, event.data) }
                } finally {
                    stream.end()
                }
            } catch (e: TransportError) {
                _state.update { it.copy(notice = "Live updates unavailable: ${e.message}") }
            }
        }
    }

    private fun handle(kind: HerdrEventKind, data: JsonElement) {
        when {
            kind == PaneEventKind.AGENT_STATUS_CHANGED.kind -> {
                // PaneAgentStatusChangedEvent: pane_id, agent_status, state_labels, plus optional agent/title.
                val payload = data as? JsonObject ?: return refresh()
                val paneID = payload["pane_id"]?.jsonPrimitive?.contentOrNull
                val status = payload["agent_status"]?.jsonPrimitive?.contentOrNull
                if (paneID == null || status == null) return refresh()
                _state.update { state ->
                    state.copy(agents = state.agents.map { if (it.paneID == paneID) it.copy(status = AgentStatus(status)) else it })
                }
                if (paneID == _state.value.selectedPaneID) read()
            }
            kind == HerdrEventKind.eventsDropped || GlobalEventKind.membership.any { it.kind == kind } -> refresh()
        }
    }

    override fun onCleared() {
        disconnect()
    }
}
