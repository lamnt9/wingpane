package io.wingpane.net

import android.os.Build
import android.content.res.Resources
import android.media.MediaCodecList
import android.media.MediaFormat
import io.wingpane.proto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

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
) {
    private val mutableState = MutableStateFlow<ClientState>(ClientState.Waiting)
    val state: StateFlow<ClientState> = mutableState.asStateFlow()
    private val incoming = Channel<Packet>(Channel.BUFFERED)
    /** Only video and input packets are emitted; control messages are consumed by the client. */
    val packets: Flow<Packet> = incoming.receiveAsFlow()

    private var job: Job? = null
    @Volatile private var socket: Socket? = null
    private val stopped = AtomicBoolean(false)

    fun connect(host: String = "127.0.0.1", port: Int = 7788) {
        if (job?.isActive == true) return
        stopped.set(false)
        job = scope.launch {
            var reconnectStartedAt: Long? = null
            var everActive = false
            while (isActive && !stopped.get()) {
                val reconnectStart = reconnectStartedAt
                if (reconnectStart != null) {
                    val elapsed = (nanoTime() - reconnectStart).coerceAtLeast(0)
                    if (everActive && elapsed >= LOST_AFTER_NS) {
                        mutableState.value = ClientState.Lost
                        return@launch
                    }
                    mutableState.value = if (everActive) ClientState.Reconnecting else ClientState.Waiting
                    delay(if (elapsed >= SLOW_RETRY_AFTER_NS) 2_000 else 1_000)
                    if (everActive && nanoTime() - reconnectStart >= LOST_AFTER_NS) {
                        mutableState.value = ClientState.Lost
                        return@launch
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
                    val buffer = ByteArray(16 * 1024)
                    val handshakeDeadline = nanoTime() + HANDSHAKE_TIMEOUT_NS
                    while (activeAccept == null && !stopped.get()) {
                        val reconnectStart = reconnectStartedAt
                        if (everActive && reconnectStart != null && nanoTime() - reconnectStart >= LOST_AFTER_NS) {
                            mutableState.value = ClientState.Lost
                            return@launch
                        }
                        if (nanoTime() >= handshakeDeadline) {
                            if (everActive) {
                                mutableState.value = ClientState.Reconnecting
                                reconnectStartedAt = reconnectStartedAt ?: nanoTime()
                                break
                            }
                            mutableState.value = ClientState.HandshakeFailed
                            return@launch
                        }
                        val count = readAvailable(thisSocket, input, buffer)
                        if (count < 0) throw java.io.EOFException("peer closed during handshake")
                        if (count == 0) { delay(POLL_MS); continue }
                        decoder.push(buffer, 0, count)
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
                                        return@launch
                                    }
                                    else -> Unit
                                }
                                else -> incoming.send(packet)
                            }
                        }
                    }
                    if (activeAccept == null) {
                        reconnectStartedAt = reconnectStartedAt ?: nanoTime()
                        continue
                    }
                    var lastPingAt = nanoTime()
                    var oldestUnansweredPing: Long? = null
                    while (isActive && !stopped.get()) {
                        val now = nanoTime()
                        val oldest = oldestUnansweredPing
                        if (oldest != null && now - oldest >= PONG_TIMEOUT_NS) {
                            mutableState.value = ClientState.Reconnecting
                            reconnectStartedAt = now
                            break
                        }
                        if (now - lastPingAt >= PING_INTERVAL_NS) {
                            withContext(ioDispatcher) {
                                send(thisSocket, Packet.Control(ControlMessage.Ping(now.toULong())))
                            }
                            lastPingAt = now
                            if (oldestUnansweredPing == null) oldestUnansweredPing = now
                        }
                        val count = readAvailable(thisSocket, input, buffer)
                        if (count < 0) throw java.io.EOFException("peer closed")
                        if (count > 0) {
                            decoder.push(buffer, 0, count)
                            while (true) {
                                when (val packet = decoder.next() ?: break) {
                                    is Packet.Control -> when (val message = packet.message) {
                                        is ControlMessage.Pong -> if (
                                            oldestUnansweredPing != null && message.timestampNs.toLong() == oldestUnansweredPing
                                        ) oldestUnansweredPing = null
                                        is ControlMessage.Reject -> {
                                            mutableState.value = ClientState.Rejected(message.reason)
                                            return@launch
                                        }
                                        else -> Unit
                                    }
                                    else -> incoming.send(packet)
                                }
                            }
                        } else delay(POLL_MS)
                    }
                    if (mutableState.value is ClientState.Lost) return@launch
                    if (reconnectStartedAt == null && !stopped.get()) reconnectStartedAt = nanoTime()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    if (!stopped.get() && mutableState.value is ClientState.Handshaking && !everActive) {
                        mutableState.value = ClientState.HandshakeFailed
                        return@launch
                    }
                    if (!stopped.get() && mutableState.value !is ClientState.HandshakeFailed && mutableState.value !is ClientState.Rejected) {
                        val wasActive = everActive
                        if (wasActive) mutableState.value = ClientState.Reconnecting
                        if (reconnectStartedAt == null) reconnectStartedAt = nanoTime()
                    }
                } finally {
                    try { thisSocket?.close() } catch (_: Throwable) { }
                    if (socket === thisSocket) socket = null
                }
                if (mutableState.value is ClientState.Rejected) return@launch
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

    fun cancel() {
        stopped.set(true)
        job?.cancel()
        try { socket?.close() } catch (_: Throwable) { }
        socket = null
        incoming.close()
        mutableState.value = ClientState.Waiting
    }

    // Called from the client's dispatcher (Dispatchers.IO in production). available() ensures
    // read() will not wait for the peer, while making the state loop deterministic under runTest.
    private fun readAvailable(socket: Socket, input: java.io.InputStream, buffer: ByteArray): Int =
        if (socket.isClosed) -1 else if (input.available() == 0) 0 else input.read(buffer)

    private fun send(socket: Socket, packet: Packet) {
        val bytes = packet.encodeFrame()
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val HANDSHAKE_TIMEOUT_NS = 3_000_000_000L
        const val PONG_TIMEOUT_NS = 2_000_000_000L
        const val PING_INTERVAL_NS = 500_000_000L
        const val LOST_AFTER_NS = 10_000_000_000L
        const val SLOW_RETRY_AFTER_NS = 30_000_000_000L
        const val POLL_MS = 10L

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
