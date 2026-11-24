package io.wingpane.net

import android.os.Build
import android.content.res.Resources
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import io.wingpane.proto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

sealed interface ClientState {
    data object Waiting : ClientState
    data object Handshaking : ClientState
    data class Active(val accept: ControlMessage.Accept) : ClientState
    data class Rejected(val reason: RejectReason) : ClientState
    data object HandshakeFailed : ClientState
    data object Reconnecting : ClientState
    data object Lost : ClientState
}

/** TCP client for one Wingpane session. Socket creation and the monotonic clock are injectable. */
class WingpaneClient(
    private val deviceName: () -> String = { Build.MODEL ?: "Android" },
    private val screenSizePx: () -> Pair<Int, Int> = {
        Resources.getSystem().displayMetrics.let { it.widthPixels to it.heightPixels }
    },
    private val supportedCodecs: () -> List<VideoCodec> = { deviceCodecs() },
    private val refreshRateHz: () -> Int = { 60 },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val socketFactory: () -> Socket = { Socket() },
    private val nanoTime: () -> Long = System::nanoTime,
    private val inputTimeUs: () -> Long = { SystemClock.uptimeNanos() / 1_000L },
) {
    val clockOffsetEstimator = ClockOffsetEstimator()
    @Volatile var clockSync: ClockOffsetEstimator.Estimate? = null
        private set
    @Volatile var onClockSync: ((ClockOffsetEstimator.Estimate) -> Unit)? = null
    private val mutableState = MutableStateFlow<ClientState>(ClientState.Waiting)
    val state: StateFlow<ClientState> = mutableState.asStateFlow()
    // Never conflate/drop here: H.264 P-frames depend on predecessors. Backlog is handled by
    // VideoDecoder (drop until next IDR + RequestKeyframe).
    private val videoIncoming = Channel<Packet>(Channel.UNLIMITED)
    private val otherIncoming = Channel<Packet>(Channel.UNLIMITED)
    /** Only video and input packets are emitted; control messages are consumed by the client. */
    val packets: Flow<Packet> = merge(videoIncoming.receiveAsFlow(), otherIncoming.receiveAsFlow())

    private var job: Job? = null
    @Volatile private var socket: Socket? = null
    private val stopped = AtomicBoolean(false)
    private val inputSeq = AtomicInteger(0)
    private val inputQueue = Channel<InputPacket>(Channel.UNLIMITED)
    private val outputLock = Any()

    init {
        scope.launch(ioDispatcher) {
            for (input in inputQueue) {
                val isCancel = input.event is InputEvent.Touch && input.event.phase == TouchPhase.Cancel
                if (mutableState.value !is ClientState.Active && !(isCancel && socket != null)) continue
                val current = socket ?: continue
                try { synchronized(outputLock) { send(current, Packet.Input(input)) } }
                catch (_: Throwable) { /* disconnect/reconnect owns socket recovery */ }
            }
        }
    }

    /** Nonblocking for UI callers; packet encoding and socket writes happen on ioDispatcher. */
    fun sendInput(event: InputEvent) {
        val isCancel = event is InputEvent.Touch && event.phase == TouchPhase.Cancel
        if (mutableState.value !is ClientState.Active && !(isCancel && socket != null)) return
        inputQueue.trySend(InputPacket(inputSeq.getAndIncrement().toUInt(), inputTimeUs().coerceAtLeast(0).toULong(), event))
    }

    fun connect(host: String = "127.0.0.1", port: Int = 7788) {
        if (job?.isActive == true) return
        stopped.set(false)
        job = scope.launch clientLoop@ {
            var reconnectStartedAt: Long? = null
            var everActive = false
            var lostWatchdog: Job? = null
            fun watchLost(startedAt: Long) {
                lostWatchdog?.cancel()
                lostWatchdog = launch {
                    val remaining = (LOST_AFTER_NS - (nanoTime() - startedAt)).coerceAtLeast(0)
                    delay((remaining / 1_000_000L).coerceAtLeast(1))
                    if (everActive && reconnectStartedAt == startedAt && !stopped.get()) {
                        mutableState.value = ClientState.Lost
                        try { socket?.close() } catch (_: Throwable) { }
                    }
                }
            }
            while (isActive && !stopped.get()) {
                val reconnectStart = reconnectStartedAt
                if (reconnectStart != null) {
                    val elapsed = (nanoTime() - reconnectStart).coerceAtLeast(0)
                    if (everActive && elapsed >= LOST_AFTER_NS) {
                        mutableState.value = ClientState.Lost
                        return@clientLoop
                    }
                    mutableState.value = if (everActive) ClientState.Reconnecting else ClientState.Waiting
                    delay(if (elapsed >= SLOW_RETRY_AFTER_NS) 2_000 else 1_000)
                    if (everActive && nanoTime() - reconnectStart >= LOST_AFTER_NS) {
                        mutableState.value = ClientState.Lost
                        return@clientLoop
                    }
                } else {
                    mutableState.value = ClientState.Waiting
                }

                var activeAccept: ControlMessage.Accept? = null
                var thisSocket: Socket? = null
                try {
                    thisSocket = socketFactory()
                    socket = thisSocket
                    withContext(ioDispatcher) {
                        thisSocket.tcpNoDelay = true
                        thisSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                        val (width, height) = screenSizePx()
                        send(thisSocket, Packet.Control(ControlMessage.Hello(
                            PROTO_VERSION.toUShort(), deviceName(),
                            width.toUInt() to height.toUInt(), supportedCodecs(), refreshRateHz().toUInt()
                        )))
                    }
                    mutableState.value = ClientState.Handshaking
                    val decoder = FrameDecoder()
                    val input = thisSocket.getInputStream()
                    val reads = Channel<ByteArray>(Channel.UNLIMITED)
                    val reader = Thread({
                        val bytes = ByteArray(64 * 1024)
                        try { while (true) { val n = input.read(bytes); if (n < 0) { reads.trySend(ByteArray(0)); break }; reads.trySend(bytes.copyOf(n)) } }
                        catch (_: Throwable) { reads.trySend(ByteArray(0)) }
                    }, "Wingpane-SocketReader").apply { isDaemon = true; start() }
                    val handshakeDeadline = nanoTime() + HANDSHAKE_TIMEOUT_NS
                    while (activeAccept == null && !stopped.get()) {
                        val reconnectStart = reconnectStartedAt
                        if (everActive && reconnectStart != null && nanoTime() - reconnectStart >= LOST_AFTER_NS) {
                            mutableState.value = ClientState.Lost
                            return@clientLoop
                        }
                        val remainingMs = ((handshakeDeadline - nanoTime()).coerceAtLeast(0) / 1_000_000L)
                        if (remainingMs == 0L) {
                            if (everActive) {
                                mutableState.value = ClientState.Reconnecting
                                reconnectStartedAt = reconnectStartedAt ?: nanoTime()
                                break
                            }
                            mutableState.value = ClientState.HandshakeFailed
                            return@clientLoop
                        }
                        val bytes = withTimeoutOrNull(remainingMs) { reads.receive() } ?: continue
                        if (bytes.isEmpty()) throw java.io.EOFException("peer closed during handshake")
                        decoder.push(bytes)
                        while (true) {
                            when (val packet = decoder.next() ?: break) {
                                is Packet.Control -> when (val message = packet.message) {
                                    is ControlMessage.Accept -> {
                                        activeAccept = message
                                        everActive = true
                                        reconnectStartedAt = null
                                        mutableState.value = ClientState.Active(message)
                                    }
                                    is ControlMessage.Reject -> {
                                        mutableState.value = ClientState.Rejected(message.reason)
                                        return@clientLoop
                                    }
                                    else -> Unit
                                }
                                else -> emitPacket(packet)
                            }
                        }
                    }
                    if (activeAccept == null) {
                        reconnectStartedAt = reconnectStartedAt ?: nanoTime()
                        continue
                    }
                    val oldestUnansweredPing = AtomicLong(0L)
                    val pingTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
                    val pingTicker = launch {
                        while (isActive && !stopped.get()) {
                            delay(500)
                            val timestamp = nanoTime()
                            pingTimes[timestamp] = timestamp
                            while (pingTimes.size > 8) pingTimes.keys.minOrNull()?.let(pingTimes::remove)
                            withContext(ioDispatcher) { send(thisSocket, Packet.Control(ControlMessage.Ping(timestamp.toULong()))) }
                            oldestUnansweredPing.compareAndSet(0L, timestamp)
                        }
                    }
                    val pongWatchdog = launch {
                        while (isActive && !stopped.get()) {
                            delay(50)
                            val oldest = oldestUnansweredPing.get()
                            val now = nanoTime()
                            if (oldest != 0L && now - oldest >= PONG_TIMEOUT_NS) {
                                mutableState.value = ClientState.Reconnecting
                                reconnectStartedAt = now
                                watchLost(now)
                                try { thisSocket.close() } catch (_: Throwable) { }
                                return@launch
                            }
                        }
                    }
                    try { activeRead@ while (isActive && !stopped.get()) {
                        val bytes = reads.receive()
                        if (bytes.isEmpty()) throw IllegalStateException("peer closed")
                        decoder.push(bytes)
                        while (true) {
                            when (val packet = decoder.next() ?: break) {
                                is Packet.Control -> when (val message = packet.message) {
                                    is ControlMessage.Pong -> if (message.timestampNs.toLong() == oldestUnansweredPing.get()) oldestUnansweredPing.set(0L)
                                    is ControlMessage.HostTime -> {
                                        val sent = pingTimes.remove(message.echoNs.toLong())
                                        if (sent != null) clockOffsetEstimator.add(sent, nanoTime(), message.hostUs)?.let { sync ->
                                            clockSync = sync
                                            onClockSync?.invoke(sync)
                                        }
                                    }
                                    is ControlMessage.Reject -> {
                                        mutableState.value = ClientState.Rejected(message.reason)
                                        break@activeRead
                                    }
                                    else -> Unit
                                }
                                else -> emitPacket(packet)
                            }
                        }
                    } } finally { pingTicker.cancel(); pongWatchdog.cancel() }
                    if (mutableState.value is ClientState.Lost) return@clientLoop
                    if (reconnectStartedAt == null && !stopped.get()) reconnectStartedAt = nanoTime()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    if (mutableState.value is ClientState.Lost) return@clientLoop
                    if (!stopped.get() && mutableState.value is ClientState.Handshaking && !everActive) {
                        mutableState.value = ClientState.HandshakeFailed
                        return@clientLoop
                    }
                    if (!stopped.get() && mutableState.value !is ClientState.HandshakeFailed && mutableState.value !is ClientState.Rejected) {
                        val wasActive = everActive
                        if (wasActive) mutableState.value = ClientState.Reconnecting
                        if (reconnectStartedAt == null) {
                            reconnectStartedAt = nanoTime()
                            if (everActive) watchLost(reconnectStartedAt!!)
                        }
                    }
                } finally {
                    try { thisSocket?.close() } catch (_: Throwable) { }
                    if (socket === thisSocket) socket = null
                }
                if (mutableState.value is ClientState.Rejected) return@clientLoop
            }
        }
    }

    fun disconnect() {
        try { socket?.let { send(it, Packet.Control(ControlMessage.Disconnect)) } } catch (_: Throwable) { }
        stopped.set(true)
        job?.cancel()
        try { socket?.close() } catch (_: Throwable) { }
        socket = null
        mutableState.value = ClientState.Waiting
    }

    suspend fun sendControl(message: ControlMessage) = withContext(ioDispatcher) {
        socket?.let { send(it, Packet.Control(message)) }
    }

    fun cancel() {
        stopped.set(true)
        job?.cancel()
        try { socket?.close() } catch (_: Throwable) { }
        socket = null
        videoIncoming.close(); otherIncoming.close()
        mutableState.value = ClientState.Waiting
    }

    private fun send(socket: Socket, packet: Packet) {
        val bytes = packet.encodeFrame()
        synchronized(outputLock) {
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }
    }

    private fun emitPacket(packet: Packet) {
        if (packet is Packet.Video) videoIncoming.trySend(packet) else otherIncoming.trySend(packet)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val HANDSHAKE_TIMEOUT_NS = 3_000_000_000L
        const val PONG_TIMEOUT_NS = 2_000_000_000L
        const val PING_INTERVAL_NS = 500_000_000L
        const val LOST_AFTER_NS = 10_000_000_000L
        const val SLOW_RETRY_AFTER_NS = 30_000_000_000L

        fun deviceCodecs(): List<VideoCodec> {
            val types = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .flatMap { it.supportedTypes.asList() }
                .toSet()
            return buildList {
                if (MediaFormat.MIMETYPE_VIDEO_AVC in types) add(VideoCodec.H264)
                if (MediaFormat.MIMETYPE_VIDEO_HEVC in types) add(VideoCodec.Hevc)
            }
        }
    }
}
