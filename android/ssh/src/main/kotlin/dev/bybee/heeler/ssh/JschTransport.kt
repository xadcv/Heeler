package dev.bybee.heeler.ssh

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelDirectStreamLocal
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import dev.bybee.heeler.herdr.Agent
import dev.bybee.heeler.herdr.EventSubscription
import dev.bybee.heeler.herdr.HerdrEvent
import dev.bybee.heeler.herdr.HerdrEventStream
import dev.bybee.heeler.herdr.HerdrJson
import dev.bybee.heeler.herdr.HerdrSocketLocation
import dev.bybee.heeler.herdr.HerdrWire
import dev.bybee.heeler.herdr.HostKeyFingerprint
import dev.bybee.heeler.herdr.ServerInfo
import dev.bybee.heeler.herdr.TerminalAttachRequest
import dev.bybee.heeler.herdr.TerminalAttachSession
import dev.bybee.heeler.herdr.Transport
import dev.bybee.heeler.herdr.TransportError
import dev.bybee.heeler.herdr.generated.AgentInfoResponse
import dev.bybee.heeler.herdr.generated.AgentListResponse
import dev.bybee.heeler.herdr.generated.AgentPromptParams
import dev.bybee.heeler.herdr.generated.AgentPromptedResponse
import dev.bybee.heeler.herdr.generated.AgentReadParams
import dev.bybee.heeler.herdr.generated.AgentRenameParams
import dev.bybee.heeler.herdr.generated.AgentSendKeysParams
import dev.bybee.heeler.herdr.generated.OkResponse
import dev.bybee.heeler.herdr.generated.PaneReadParams
import dev.bybee.heeler.herdr.generated.PaneReadResponse
import dev.bybee.heeler.herdr.generated.PaneReadResult
import dev.bybee.heeler.herdr.generated.PaneTarget
import dev.bybee.heeler.herdr.generated.PongResponse
import dev.bybee.heeler.herdr.generated.SessionSnapshot
import dev.bybee.heeler.herdr.generated.SessionSnapshotResponse
import dev.bybee.heeler.herdr.generated.WorkspaceInfoResponse
import dev.bybee.heeler.herdr.generated.WorkspaceRenameParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel as CoroutineChannel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Everything a [JschTransport] needs to reach one Host. */
public data class JschTransportConfig(
    val endpoint: SshEndpoint,
    val identity: Identity,
    val hostKeyVerifier: HostKeyVerifier,
    val socketLocation: HerdrSocketLocation = HerdrSocketLocation.DefaultSession,
    val connectTimeout: Duration = 20.seconds,
    val requestTimeout: Duration = 20.seconds,
    /** Stream-local RPC channels in flight at once; one more is reserved for events (ADR 0011). */
    val rpcConcurrency: Int = 8,
    /** Exec channels in flight at once; one more session slot is reserved for attach (`MaxSessions` 10). */
    val execConcurrency: Int = 8,
)

/**
 * The herdr [Transport] over SSH with the mwiede JSch fork (ADR 0011, ADR
 * 0017): JSON RPC rides direct-streamlocal channels opened straight onto the
 * herdr API socket, one request per channel because herdr serves one request
 * per connection; events keep one dedicated channel; attach is a PTY exec.
 *
 * JSch is blocking, so every channel operation runs on [Dispatchers.IO] under
 * a deadline, and a timed-out channel is disconnected rather than left to
 * hold one of sshd's slots.
 */
public class JschTransport(
    private val config: JschTransportConfig,
    private val jsch: JSch = JSch(),
) : Transport {
    private val connectMutex = Mutex()
    private var session: Session? = null
    private var homeDirectory: String? = null
    private val rpcSlots = Semaphore(config.rpcConcurrency)
    private val execSlots = Semaphore(config.execConcurrency)
    private val eventsOpen = AtomicBoolean(false)
    private val terminalOpen = AtomicBoolean(false)
    private val nextRequestID = AtomicLong(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val isConnected: Boolean
        get() = session?.isConnected == true

    /** Establishes the SSH session (idempotent). Requests call this implicitly. */
    public suspend fun connect() {
        ensureSession()
    }

    override suspend fun ping(): ServerInfo {
        val pong = request<PongResponse>("ping")
        return serverInfo(pong)
    }

    override suspend fun listAgents(): List<Agent> =
        request<AgentListResponse>("agent.list").agents.map(Agent::fromWire)

    override suspend fun sessionSnapshot(): SessionSnapshot =
        request<SessionSnapshotResponse>("session.snapshot").snapshot

    override suspend fun readPane(params: PaneReadParams): PaneReadResult =
        request<PaneReadParams, PaneReadResponse>("pane.read", params).read

    override suspend fun readAgent(params: AgentReadParams): PaneReadResult =
        request<AgentReadParams, PaneReadResponse>("agent.read", params).read

    override suspend fun promptAgent(params: AgentPromptParams): Agent =
        Agent.fromWire(request<AgentPromptParams, AgentPromptedResponse>("agent.prompt", params).agent)

    override suspend fun sendAgentKeys(params: AgentSendKeysParams) {
        request<AgentSendKeysParams, OkResponse>("agent.send_keys", params)
    }

    override suspend fun closePane(params: PaneTarget) {
        request<PaneTarget, OkResponse>("pane.close", params)
    }

    override suspend fun renameAgent(params: AgentRenameParams) {
        request<AgentRenameParams, AgentInfoResponse>("agent.rename", params)
    }

    override suspend fun renameWorkspace(params: WorkspaceRenameParams) {
        request<WorkspaceRenameParams, WorkspaceInfoResponse>("workspace.rename", params)
    }

    override suspend fun subscribeToEvents(subscriptions: List<EventSubscription>): HerdrEventStream {
        if (!eventsOpen.compareAndSet(false, true)) throw TransportError.EventsChannelAlreadyOpen
        try {
            val session = ensureSession()
            val socketPath = socketPath(session)
            val id = "${nextRequestID.getAndIncrement()}"
            val channel = openStreamLocal(session, socketPath)
            val input = BufferedInputStream(channel.inputStream)
            val output = channel.outputStream
            try {
                // The server acknowledges (or refuses, all-or-nothing) on the
                // first line; only after that do event lines follow.
                val ack = withDeadline(channel) {
                    output.write(HerdrWire.subscribeRequestLine(id, subscriptions).toByteArray())
                    output.flush()
                    readLine(input)
                }
                HerdrWire.decodeResult(serializer<JsonElement>(), ack, id)
            } catch (e: Throwable) {
                withContext(Dispatchers.IO) { channel.disconnect() }
                throw e
            }
            return EventsChannelStream(channel, input)
        } catch (e: Throwable) {
            eventsOpen.set(false)
            throw e
        }
    }

    override suspend fun attachTerminal(request: TerminalAttachRequest): TerminalAttachSession {
        if (!terminalOpen.compareAndSet(false, true)) throw TransportError.TerminalChannelAlreadyOpen
        try {
            val session = ensureSession()
            val socketPath = socketPath(session)
            val command = RemoteCommands.attach(request, socketPath)
            val channel = try {
                session.openChannel("exec") as ChannelExec
            } catch (e: JSchException) {
                throw TransportError.ChannelFailed("open exec: ${e.message}")
            }
            channel.setPty(true)
            channel.setPtyType("xterm-256color", request.cols, request.rows, 0, 0)
            channel.setCommand(command)
            val input = channel.inputStream
            val output = channel.outputStream
            try {
                withContext(Dispatchers.IO) { channel.connect(config.connectTimeout.inWholeMilliseconds.toInt()) }
            } catch (e: JSchException) {
                channel.disconnect()
                throw TransportError.ChannelFailed("attach exec: ${e.message}")
            }
            return AttachChannelSession(channel, input, output)
        } catch (e: Throwable) {
            terminalOpen.set(false)
            throw e
        }
    }

    override suspend fun close() {
        val current = connectMutex.withLock {
            val s = session
            session = null
            homeDirectory = null
            s
        }
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        withContext(Dispatchers.IO) { current?.disconnect() }
    }

    // MARK: Connection

    private suspend fun ensureSession(): Session = connectMutex.withLock {
        session?.takeIf { it.isConnected }?.let { return it }
        val repository = VerifyingHostKeyRepository()
        val fresh = try {
            jsch.removeAllIdentity()
            jsch.addIdentity(config.identity, null)
            jsch.getSession(config.endpoint.username, config.endpoint.host, config.endpoint.port).apply {
                hostKeyRepository = repository
                setConfig("StrictHostKeyChecking", "yes")
                setConfig("PreferredAuthentications", "publickey")
                userInfo = SilentUserInfo
                serverAliveInterval = 15_000
            }
        } catch (e: JSchException) {
            throw TransportError.SshUnreachable(e.message ?: e.toString())
        }
        try {
            runInterruptible(Dispatchers.IO) { fresh.connect(config.connectTimeout.inWholeMilliseconds.toInt()) }
        } catch (e: JSchException) {
            throw mapConnectFailure(e, repository.failure)
        } catch (e: CancellationException) {
            fresh.disconnect()
            throw e
        }
        session = fresh
        fresh
    }

    private fun mapConnectFailure(e: JSchException, hostKeyFailure: TransportError?): TransportError {
        hostKeyFailure?.let { return it }
        val message = e.message ?: e.toString()
        return when {
            message.startsWith("Auth fail") || message.startsWith("Auth cancel") -> TransportError.AuthenticationFailed
            message.startsWith("reject HostKey") || message.startsWith("HostKey has been changed") ->
                TransportError.ChannelFailed(message)
            else -> TransportError.SshUnreachable(message)
        }
    }

    private suspend fun socketPath(session: Session): String {
        val location = config.socketLocation
        if (location is HerdrSocketLocation.AbsolutePath) return location.path
        val home = homeDirectory ?: resolveHome(session).also { homeDirectory = it }
        return location.path(home)
    }

    private suspend fun resolveHome(session: Session): String {
        val result = exec(session, RemoteCommands.HOME)
        val line = result.stdout.decodeToString().lineSequence()
            .firstOrNull { it.startsWith(RemoteCommands.HOME_MARKER) }
            ?: throw TransportError.HomeDirectoryUnresolvable("no marker line in: ${preview(result.stdout)}")
        val home = line.removePrefix(RemoteCommands.HOME_MARKER).trimEnd()
        if (!home.startsWith("/")) throw TransportError.HomeDirectoryUnresolvable("not absolute: $home")
        return home
    }

    // MARK: RPC

    private suspend inline fun <reified R> request(method: String): R =
        request(method, JsonObject(emptyMap()), serializer<R>())

    private suspend inline fun <reified P, reified R> request(method: String, params: P): R =
        request(method, HerdrJson.encodeToJsonElement(serializer<P>(), params), serializer<R>())

    private suspend fun <R> request(method: String, params: JsonElement, deserializer: DeserializationStrategy<R>): R {
        val session = ensureSession()
        val socketPath = socketPath(session)
        val id = "${nextRequestID.getAndIncrement()}"
        val line = HerdrWire.requestLine(id, method, params)
        val response = rpcSlots.withPermit { exchange(session, socketPath, line) }
        return HerdrWire.decodeResult(deserializer, response, id)
    }

    /** One request on one fresh stream-local channel, closed after the single response line. */
    private suspend fun exchange(session: Session, socketPath: String, line: String): String {
        val channel = openStreamLocal(session, socketPath)
        try {
            val input = BufferedInputStream(channel.inputStream)
            val output = channel.outputStream
            return withDeadline(channel) {
                output.write(line.toByteArray())
                output.flush()
                readLine(input)
            }
        } finally {
            withContext(Dispatchers.IO) { channel.disconnect() }
        }
    }

    private suspend fun openStreamLocal(session: Session, socketPath: String): ChannelDirectStreamLocal {
        val channel = try {
            session.openChannel("direct-streamlocal@openssh.com") as ChannelDirectStreamLocal
        } catch (e: JSchException) {
            throw TransportError.ChannelFailed("open stream-local: ${e.message}")
        }
        channel.socketPath = socketPath
        try {
            runInterruptible(Dispatchers.IO) { channel.connect(config.connectTimeout.inWholeMilliseconds.toInt()) }
        } catch (e: JSchException) {
            channel.disconnect()
            if (!session.isConnected) throw TransportError.SshUnreachable(e.message ?: "session lost")
            throw diagnoseStreamLocalFailure(session, socketPath)
        }
        return channel
    }

    /**
     * The SSH layer cannot tell a listening socket refused by sshd policy from
     * a missing or stale socket file. `test -S` over exec separates the one
     * cause it can name (no socket file) from the combined remainder.
     */
    private suspend fun diagnoseStreamLocalFailure(session: Session, socketPath: String): TransportError {
        val probe = try {
            exec(session, RemoteCommands.socketProbe(socketPath))
        } catch (_: TransportError) {
            return TransportError.StreamLocalOpenFailed(socketPath)
        }
        return if (probe.exitStatus == 0) {
            TransportError.StreamLocalOpenFailed(socketPath)
        } else {
            TransportError.SocketNotFound(socketPath)
        }
    }

    private class ExecResult(val stdout: ByteArray, val exitStatus: Int)

    private suspend fun exec(session: Session, command: String): ExecResult = execSlots.withPermit {
        val channel = try {
            session.openChannel("exec") as ChannelExec
        } catch (e: JSchException) {
            throw TransportError.ChannelFailed("open exec: ${e.message}")
        }
        channel.setCommand(command)
        channel.setInputStream(null)
        try {
            val input = channel.inputStream
            withDeadline(channel) {
                channel.connect(config.connectTimeout.inWholeMilliseconds.toInt())
                val stdout = input.readBytes()
                while (!channel.isClosed) Thread.sleep(10)
                ExecResult(stdout, channel.exitStatus)
            }
        } catch (e: JSchException) {
            throw TransportError.ChannelFailed("exec $command: ${e.message}")
        } finally {
            withContext(Dispatchers.IO) { channel.disconnect() }
        }
    }

    /**
     * Runs blocking channel IO under the request deadline. On timeout or
     * cancellation the channel is disconnected so no sshd slot stays held.
     */
    private suspend fun <T> withDeadline(channel: Channel, block: () -> T): T = try {
        withTimeout(config.requestTimeout) { runInterruptible(Dispatchers.IO) { block() } }
    } catch (e: TimeoutCancellationException) {
        channel.disconnect()
        throw TransportError.TimedOut
    } catch (e: CancellationException) {
        channel.disconnect()
        throw e
    } catch (e: IOException) {
        throw TransportError.ChannelFailed(e.toString())
    }

    private fun serverInfo(pong: PongResponse): ServerInfo {
        if (pong.protocolVersion < MINIMUM_PROTOCOL_VERSION) {
            throw TransportError.ProtocolVersionMismatch(pong.protocolVersion, MINIMUM_PROTOCOL_VERSION)
        }
        return ServerInfo(
            version = pong.version,
            protocolVersion = pong.protocolVersion,
            exceedsGeneratedProtocol = pong.protocolVersion > GENERATED_PROTOCOL_VERSION,
        )
    }

    // MARK: Events channel

    private inner class EventsChannelStream(
        private val channel: ChannelDirectStreamLocal,
        input: InputStream,
    ) : HerdrEventStream {
        private val buffer = CoroutineChannel<HerdrEvent>(HerdrEventStream.BUFFER_LIMIT)
        private val ended = AtomicBoolean(false)
        private val reader: Job = scope.launch {
            var dropped = false
            try {
                while (true) {
                    val line = try {
                        runInterruptible { readLineOrNull(input) }
                    } catch (e: IOException) {
                        throw TransportError.ChannelFailed("events channel: $e")
                    } ?: break
                    val event = HerdrWire.decodeEvent(line) ?: continue
                    if (dropped && buffer.trySend(HerdrEvent.eventsDropped).isSuccess) dropped = false
                    if (buffer.trySend(event).isFailure) dropped = true
                }
                if (ended.get()) buffer.close() else buffer.close(TransportError.ChannelFailed("events channel closed by the Host"))
            } catch (e: TransportError) {
                buffer.close(if (ended.get()) null else e)
            } catch (e: CancellationException) {
                buffer.close()
                throw e
            } catch (e: ClosedSendChannelException) {
                // Consumer went away; nothing left to deliver.
            } finally {
                channel.disconnect()
                eventsOpen.set(false)
            }
        }

        override val events: Flow<HerdrEvent> = buffer.receiveAsFlow()

        override suspend fun end() {
            if (!ended.compareAndSet(false, true)) return
            withContext(Dispatchers.IO) { channel.disconnect() }
            reader.cancelAndJoin()
        }
    }

    // MARK: Attach

    private inner class AttachChannelSession(
        private val channel: ChannelExec,
        input: InputStream,
        private val remoteInput: OutputStream,
    ) : TerminalAttachSession {
        private val bytes = CoroutineChannel<ByteArray>(CoroutineChannel.UNLIMITED)
        private val ended = AtomicBoolean(false)
        private val writeLock = Any()
        private val reader: Job = scope.launch {
            val gate = AttachBootstrapGate()
            val chunk = ByteArray(16 * 1024)
            try {
                while (true) {
                    val read = try {
                        runInterruptible { input.read(chunk) }
                    } catch (e: IOException) {
                        -1
                    }
                    if (read < 0) break
                    if (read == 0) continue
                    val admitted = gate.admit(chunk.copyOf(read))
                    if (admitted.isNotEmpty()) bytes.send(admitted)
                }
                val remainder = gate.flush()
                if (remainder.isNotEmpty()) bytes.send(remainder)
                bytes.close()
            } catch (e: CancellationException) {
                bytes.close()
                throw e
            } finally {
                channel.disconnect()
                terminalOpen.set(false)
            }
        }

        override val output: Flow<ByteArray> = bytes.receiveAsFlow()

        override fun send(keystrokes: ByteArray) {
            if (ended.get()) return
            try {
                synchronized(writeLock) {
                    remoteInput.write(keystrokes)
                    remoteInput.flush()
                }
            } catch (_: IOException) {
                // A dead channel drops keystrokes; the death surfaces on `output`.
            }
        }

        override fun resize(cols: Int, rows: Int) {
            if (ended.get()) return
            channel.setPtySize(cols, rows, 0, 0)
        }

        override suspend fun end() {
            if (!ended.compareAndSet(false, true)) return
            withContext(Dispatchers.IO) { channel.disconnect() }
            reader.cancelAndJoin()
        }
    }

    // MARK: Host keys

    /**
     * Adapts [HostKeyVerifier] to JSch's synchronous repository. The verdict is
     * made inside `check`, because with strict checking JSch rejects
     * `NOT_INCLUDED` before ever calling `add`; a refusal is recorded so the
     * connect failure can be reported as the trust error it is.
     */
    private inner class VerifyingHostKeyRepository : HostKeyRepository {
        @Volatile
        var failure: TransportError? = null

        override fun check(host: String, key: ByteArray): Int {
            val presented = HostKeyFingerprint.ofPublicKeyBlob(key)
            return try {
                runBlocking { config.hostKeyVerifier.verify(config.endpoint, presented) }
                HostKeyRepository.OK
            } catch (e: TransportError) {
                failure = e
                HostKeyRepository.NOT_INCLUDED
            }
        }

        override fun add(hostkey: HostKey, ui: UserInfo?) {}
        override fun remove(host: String, type: String?) {}
        override fun remove(host: String, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "heeler"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String, type: String?): Array<HostKey> = emptyArray()
    }

    private object SilentUserInfo : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?): Boolean = false
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) {}
    }

    public companion object {
        /** The oldest herdr protocol this build accepts. A floor, not equality (#140). */
        public const val MINIMUM_PROTOCOL_VERSION: Long = 17

        /** The protocol the generated wire types were produced from; newer Hosts only raise an advisory. */
        public const val GENERATED_PROTOCOL_VERSION: Long = 22

        /** Cap on one response line; pane reads are capped at 1000 lines by the server. */
        private const val MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024

        /**
         * Android runtimes ship no JCA Ed25519 or XDH provider, so JSch must
         * use its BouncyCastle-backed implementations there. Call once at app
         * start; the JVM test suites run on the JCE defaults.
         */
        public fun configureForAndroid() {
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        }

        private fun readLine(input: InputStream): String =
            readLineOrNull(input) ?: throw TransportError.ChannelFailed("connection closed before a response line")

        /** Reads one `\n`-terminated line; null at EOF with nothing read. */
        private fun readLineOrNull(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8)
                if (byte == '\n'.code) return out.toString(Charsets.UTF_8)
                out.write(byte)
                if (out.size() > MAXIMUM_RESPONSE_BYTES) {
                    throw TransportError.MalformedResponse("response exceeds $MAXIMUM_RESPONSE_BYTES bytes")
                }
            }
        }

        private fun preview(bytes: ByteArray): String = bytes.take(200).toByteArray().decodeToString()
    }
}
