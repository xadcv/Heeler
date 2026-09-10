package dev.bybee.heeler.ssh

import dev.bybee.heeler.herdr.generated.AgentStatus
import dev.bybee.heeler.herdr.EventSubscription
import dev.bybee.heeler.herdr.GlobalEventKind
import dev.bybee.heeler.herdr.HerdrEventKind
import dev.bybee.heeler.herdr.HerdrSocketLocation
import dev.bybee.heeler.herdr.HostKeyFingerprint
import dev.bybee.heeler.herdr.PaneEventKind
import dev.bybee.heeler.herdr.TerminalAttachRequest
import dev.bybee.heeler.herdr.TerminalAttachSession
import dev.bybee.heeler.herdr.TerminalAttachTarget
import dev.bybee.heeler.herdr.Transport
import dev.bybee.heeler.herdr.TransportError
import dev.bybee.heeler.herdr.generated.AgentPromptParams
import dev.bybee.heeler.herdr.generated.AgentRenameParams
import dev.bybee.heeler.herdr.generated.PaneReadParams
import dev.bybee.heeler.herdr.generated.ReadSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.Base64
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Real SSH, real stream-local channels, fake herdr. Provisioned by
 * `android/scripts/run-transport-e2e.sh`, which sets the `heeler.e2e.*`
 * system properties; without them every test here is skipped, not failed.
 *
 * The fixture (`scripts/fixtures/fake-herdr-streamlocal.py`, shared with the
 * iOS suites) listens at `$homeDir/.config/herdr/herdr.sock`, binds a stale
 * socket at `$homeDir/.config/herdr/sessions/stale/herdr.sock`, and the
 * sshd forces `HOME=$homeDir` so the transport's own `$HOME` resolution is
 * what finds the socket. A stub `herdr` under `$homeDir/.local/bin` answers
 * attach.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JschTransportE2ETest {
    private val port = System.getProperty("heeler.e2e.port")?.toIntOrNull()
    private val portDenied = System.getProperty("heeler.e2e.portDenied")?.toIntOrNull()
    private val user = System.getProperty("heeler.e2e.user")
    private val authorizedKeysFile = System.getProperty("heeler.e2e.authorizedKeysFile")
    private val hostKeyPublicFile = System.getProperty("heeler.e2e.hostKeyPublicFile")
    private val homeDir = System.getProperty("heeler.e2e.homeDir")

    private val deviceKey = Ed25519(Ed25519.generateSeed())
    private val identity = Ed25519Identity("heeler-e2e", deviceKey)
    private lateinit var hostKey: HostKeyFingerprint
    private val open = mutableListOf<Transport>()

    @BeforeAll
    fun provision() {
        assumeTrue(port != null && portDenied != null && user != null && authorizedKeysFile != null &&
            hostKeyPublicFile != null && homeDir != null, "heeler.e2e.* not set; run android/scripts/run-transport-e2e.sh")
        // The Device Key is generated in-process, exactly as the app would, and the
        // sshd re-reads authorized_keys on every authentication.
        File(authorizedKeysFile!!).writeText(deviceKey.authorizedKeysLine("heeler-e2e") + "\n")
        val blob = Base64.getDecoder().decode(File(hostKeyPublicFile!!).readText().trim().split(" ")[1])
        hostKey = HostKeyFingerprint.ofPublicKeyBlob(blob)
    }

    @AfterAll
    fun teardown() = runBlocking {
        open.forEach { runCatching { it.close() } }
    }

    private fun endpoint(port: Int = this.port!!) = SshEndpoint("127.0.0.1", port, user!!)

    private fun trusting() = TofuHostKeyVerifier(InMemoryKnownHostsStore(), pinned = hostKey) { _, _ -> false }

    private fun transport(
        location: HerdrSocketLocation = HerdrSocketLocation.DefaultSession,
        port: Int = this.port!!,
        identity: Ed25519Identity = this.identity,
        verifier: HostKeyVerifier = trusting(),
    ): JschTransport = JschTransport(
        JschTransportConfig(endpoint(port), identity, verifier, location, connectTimeout = 10.seconds, requestTimeout = 10.seconds),
    ).also { open += it }

    /** Returns Unit on purpose: a JUnit `@Test` that returns a value is silently not executed. */
    private fun e2e(block: suspend () -> Any?) {
        runBlocking { withTimeout(60.seconds) { block() } }
    }

    private val socketPath get() = "$homeDir/.config/herdr/herdr.sock"

    // MARK: RPC

    @Test
    fun `ping resolves HOME, opens a stream-local channel and reads the pong`() = e2e {
        val transport = transport()
        val info = transport.ping()
        assertEquals("fake", info.version)
        assertEquals(17L, info.protocolVersion)
        assertFalse(info.exceedsGeneratedProtocol)
        assertTrue(transport.isConnected)
    }

    @Test
    fun `typed requests round-trip through one channel per request`() = e2e {
        val transport = transport()
        val read = transport.readPane(PaneReadParams(paneID = "pane-1", source = ReadSource.recent, lines = 80))
        assertEquals("fixture output", read.text)
        assertEquals("pane-1", read.paneID)

        val agent = transport.promptAgent(AgentPromptParams(target = "pane-1", text = "fixture prompt"))
        assertEquals("pane-1", agent.paneID)
        assertEquals(AgentStatus.working, agent.status)

        assertTrue(transport.listAgents().isEmpty())
        assertEquals("fake", transport.sessionSnapshot().version)
    }

    @Test
    fun `server errors surface as ApiRejected with the code as text`() = e2e {
        val rejected = assertFailsWith<TransportError.ApiRejected> {
            transport().renameAgent(AgentRenameParams(target = "api-error", name = "x"))
        }
        assertEquals("500", rejected.code)
        assertEquals("scripted failure", rejected.apiMessage)
    }

    @Test
    fun `concurrent requests stay bounded and all complete`() = e2e {
        val transport = transport()
        val results = coroutineScope { (1..24).map { async { transport.ping().protocolVersion } }.awaitAll() }
        assertEquals(List(24) { 17L }, results)
    }

    // MARK: Socket diagnostics

    @Test
    fun `a stale socket file is a stream-local open failure, a missing one is SocketNotFound`() = e2e {
        val stale = assertFailsWith<TransportError.StreamLocalOpenFailed> {
            transport(HerdrSocketLocation.NamedSession("stale")).ping()
        }
        assertEquals("$homeDir/.config/herdr/sessions/stale/herdr.sock", stale.path)

        val missing = assertFailsWith<TransportError.SocketNotFound> {
            transport(HerdrSocketLocation.NamedSession("nowhere")).ping()
        }
        assertEquals("$homeDir/.config/herdr/sessions/nowhere/herdr.sock", missing.path)
    }

    @Test
    fun `an sshd that denies stream-local forwarding fails preflight rather than falling back`() = e2e {
        val denied = assertFailsWith<TransportError.StreamLocalOpenFailed> {
            transport(port = portDenied!!).ping()
        }
        assertEquals(socketPath, denied.path)
    }

    // MARK: Trust and authentication

    @Test
    fun `a mismatching pinned host key is refused before authentication`() = e2e {
        val wrong = HostKeyFingerprint(ByteArray(32) { 0x42 })
        val store = InMemoryKnownHostsStore()
        val error = assertFailsWith<TransportError.HostKeyMismatch> {
            transport(verifier = TofuHostKeyVerifier(store, pinned = wrong) { _, _ -> true }).ping()
        }
        assertEquals(hostKey, error.presented)
        assertEquals(wrong, error.known)
        assertEquals(null, store.trusted(endpoint()))
    }

    @Test
    fun `declining an unknown host key is HostKeyRejected and records nothing`() = e2e {
        val store = InMemoryKnownHostsStore()
        val error = assertFailsWith<TransportError.HostKeyRejected> {
            transport(verifier = TofuHostKeyVerifier(store) { _, _ -> false }).ping()
        }
        assertEquals(hostKey, error.presented)
        assertEquals(null, store.trusted(endpoint()))
    }

    @Test
    fun `approving an unknown host key records it and connects`() = e2e {
        val store = InMemoryKnownHostsStore()
        var prompted: HostKeyFingerprint? = null
        transport(verifier = TofuHostKeyVerifier(store) { _, presented -> prompted = presented; true }).ping()
        assertEquals(hostKey, prompted)
        assertEquals(hostKey, store.trusted(endpoint()))
    }

    @Test
    fun `an unauthorized device key is AuthenticationFailed`() = e2e {
        val stranger = Ed25519Identity("stranger", Ed25519(Ed25519.generateSeed()))
        assertFailsWith<TransportError.AuthenticationFailed> { transport(identity = stranger).ping() }
    }

    @Test
    fun `a closed port is SshUnreachable`() = e2e {
        assertFailsWith<TransportError.SshUnreachable> { transport(port = 1).ping() }
    }

    // MARK: Events

    @Test
    fun `events channel skips junk lines and preserves unknown kinds`() = e2e {
        val transport = transport()
        val stream = transport.subscribeToEvents(listOf(EventSubscription.Global(GlobalEventKind.PANE_CREATED)))
        val events = stream.events.take(3).toList()
        assertEquals(HerdrEventKind("future_herdr_event"), events[0].kind)
        assertEquals("preserved", events[0].data.jsonObject["value"]?.jsonPrimitive?.content)
        assertEquals(GlobalEventKind.PANE_CREATED.kind, events[1].kind)
        assertEquals(GlobalEventKind.PANE_CREATED.kind, events[2].kind)
        assertEquals("fixture:event", events[2].data.jsonObject["pane_id"]?.jsonPrimitive?.content)

        assertFailsWith<TransportError.EventsChannelAlreadyOpen> {
            transport.subscribeToEvents(listOf(EventSubscription.Global(GlobalEventKind.PANE_CREATED)))
        }
        stream.end()
        // Ending releases the single events slot.
        transport.subscribeToEvents(listOf(EventSubscription.Global(GlobalEventKind.PANE_CREATED))).end()
    }

    @Test
    fun `a refused subscription is the server's error, not a timeout`() = e2e {
        val rejected = assertFailsWith<TransportError.ApiRejected> {
            transport().subscribeToEvents(
                listOf(EventSubscription.Pane(PaneEventKind.AGENT_STATUS_CHANGED, "fixture:reject")),
            )
        }
        assertEquals("fixture_rejected", rejected.code)
    }

    @Test
    fun `a Host closing the events channel fails the stream after delivering what it sent`() = e2e {
        val stream = transport().subscribeToEvents(
            listOf(EventSubscription.Pane(PaneEventKind.AGENT_STATUS_CHANGED, "fixture:remote-close")),
        )
        val seen = mutableListOf<HerdrEventKind>()
        val failure = assertFailsWith<TransportError.ChannelFailed> {
            stream.events.collect { seen += it.kind }
        }
        assertEquals(listOf(PaneEventKind.AGENT_STATUS_CHANGED.kind), seen)
        assertContains(failure.detail, "closed by the Host")
    }

    // MARK: Attach

    @Test
    fun `attach runs the stub over a PTY, gates the bootstrap, and relays keystrokes and resizes`() = e2e {
        val transport = transport()
        val session = transport.attachTerminal(
            TerminalAttachRequest(TerminalAttachTarget.AgentPane("w1:p1"), cols = 100, rows = 30),
        )
        val output = StringBuilder()
        suspend fun awaitOutput(needle: String) {
            withTimeout(15.seconds) {
                while (!output.contains(needle)) {
                    output.append(session.output.take(1).toList().single().decodeToString())
                }
            }
        }
        awaitOutput("30 100")
        // Everything before the marker was withheld; the stub's first line leads.
        assertTrue(output.startsWith("TTY-OK"), "unexpected prefix: ${output.take(80)}")
        assertContains(output, "ARGS:agent attach w1:p1")
        assertContains(output, "SOCKET:$socketPath")

        session.resize(cols = 120, rows = 40)
        session.send("__size__\n".toByteArray())
        awaitOutput("40 120")

        session.send("hello from android\n".toByteArray())
        awaitOutput("GOT:hello from android")

        assertFailsWith<TransportError.TerminalChannelAlreadyOpen> {
            transport.attachTerminal(TerminalAttachRequest(TerminalAttachTarget.AgentPane("w1:p1"), 80, 24))
        }

        session.send("__exit__\n".toByteArray())
        // The stub exits 0; the output flow completes rather than failing.
        withTimeout(15.seconds) { session.output.collect { output.append(it.decodeToString()) } }
        session.end()
        transport.attachTerminal(TerminalAttachRequest(TerminalAttachTarget.AgentPane("w1:p1"), 80, 24)).also(::endAttach)
    }

    private fun endAttach(session: TerminalAttachSession) = runBlocking {
        session.send("__exit__\n".toByteArray())
        session.end()
    }

    @Test
    fun `a terminal target runs terminal attach and a failing exit completes the output`() = e2e {
        val session = transport().attachTerminal(
            TerminalAttachRequest(TerminalAttachTarget.Terminal("tX"), cols = 80, rows = 24, takeover = true),
        )
        val output = StringBuilder()
        withTimeout(15.seconds) {
            session.send("__fail__\n".toByteArray())
            // The stub exits 23; the channel closes and the flow completes.
            session.output.collect { output.append(it.decodeToString()) }
        }
        assertContains(output, "ARGS:terminal attach tX --takeover")
        session.end()
    }
}
