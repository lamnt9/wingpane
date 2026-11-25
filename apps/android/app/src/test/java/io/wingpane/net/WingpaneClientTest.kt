package io.wingpane.net

import io.wingpane.proto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WingpaneClientTest {
    private val accept = Packet.Control(ControlMessage.Accept(
        Mode.ExtendedDisplay, null, VideoCodec.H264, 20_000u
    ))

    private fun server(handler: (Socket, Int) -> Unit): FakeServer = FakeServer(handler)

    @Test fun reducerKeepsOneLossEpisodeAcrossFailedTcpAttempts() {
        var m = ConnectionStateMachine.Model(ClientState.Active(accept.message as ControlMessage.Accept), true)
        fun event(e: ConnectionStateMachine.Event) { m = ConnectionStateMachine.create(m).reduce(e).model }
        event(ConnectionStateMachine.Event.PongTimeout(1_000))
        assertEquals(1_000L, m.lossStartedAtNs)
        event(ConnectionStateMachine.Event.TcpConnected)
        event(ConnectionStateMachine.Event.TransportLost(4_000))
        assertEquals(1_000L, m.lossStartedAtNs)
        event(ConnectionStateMachine.Event.Tick(10_000_000_999L))
        assertEquals(ClientState.Reconnecting, m.state)
        event(ConnectionStateMachine.Event.Tick(10_000_001_000L))
        assertEquals(ClientState.Lost, m.state)
    }

    @Test fun reducerWaitsForeverAndSwitchesRetryCadenceAtThirtySeconds() {
        var m = ConnectionStateMachine.Model()
        m = ConnectionStateMachine.create(m).reduce(ConnectionStateMachine.Event.Tick(0)).model
        var r = ConnectionStateMachine.create(m).reduce(ConnectionStateMachine.Event.Tick(31_000_000_000L))
        assertEquals(ClientState.Waiting, r.model.state)
        assertEquals(ConnectionStateMachine.Effect.ScheduleRetry(2_000), r.effects.single())
    }

    @Test fun reducerCancelAndRetryTransitions() {
        for (state in listOf(ClientState.Handshaking, ClientState.Reconnecting)) {
            val model = ConnectionStateMachine.Model(state, state == ClientState.Reconnecting, if (state == ClientState.Reconnecting) 5L else null)
            assertEquals(ClientState.Waiting, ConnectionStateMachine.create(model).reduce(ConnectionStateMachine.Event.UserCancel).model.state)
        }
        for (state in listOf(ClientState.Rejected(RejectReason.VersionMismatch), ClientState.HandshakeFailed, ClientState.Lost)) {
            val result = ConnectionStateMachine.create(ConnectionStateMachine.Model(state, true, 4L)).reduce(ConnectionStateMachine.Event.UserRetry)
            assertEquals(ClientState.Waiting, result.model.state)
            assertEquals(ConnectionStateMachine.Effect.Connect, result.effects.single())
        }
    }

    private fun client(testScope: kotlinx.coroutines.test.TestScope, server: FakeServer, inputClock: () -> Long = { 123_456L }) = WingpaneClient(
        deviceName = { "Unit Test" }, screenSizePx = { 1920 to 1080 },
        supportedCodecs = { listOf(VideoCodec.H264) }, refreshRateHz = { 60 },
        scope = testScope.backgroundScope,
        ioDispatcher = testScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
        socketFactory = { Socket() },
        nanoTime = { testScope.testScheduler.currentTime * 1_000_000L },
        inputTimeUs = inputClock,
    ).also { it.connect("127.0.0.1", server.port) }

    private fun await(latch: CountDownLatch) {
        assertTrue("server did not observe expected packet", latch.await(5, TimeUnit.SECONDS))
        // The socket reader is deliberately a real blocking thread, independent of runTest's clock.
        // Let it enqueue bytes written immediately before the server releases its latch.
        Thread.sleep(20)
    }

    private fun awaitClient(testScope: kotlinx.coroutines.test.TestScope, client: WingpaneClient, condition: () -> Boolean) {
        repeat(100) {
            testScope.runCurrent()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("client state did not settle: ${client.state.value}")
    }

    @Test fun acceptMovesClientToActive() = runTest {
        val hello = CountDownLatch(1)
        val capability = CountDownLatch(1)
        val srv = server { socket, _ ->
            assertIsHello(readPacket(socket))
            socket.getOutputStream().write(accept.encodeFrame())
            // Release the test thread first: it must pump runTest (runCurrent) so the client can process Accept.
            hello.countDown()
            // A Ping may race ahead of Capabilities; skip it.
            var p = readPacket(socket)
            while (p is Packet.Control && p.message is ControlMessage.Ping) p = readPacket(socket)
            assertEquals(Packet.Control(ControlMessage.Capabilities(0u)), p)
            capability.countDown()
            try { while (true) readPacket(socket) } catch (_: Throwable) { }
        }
        val client = client(this, srv)
        try {
            runCurrent()
            await(hello)
            awaitClient(this, client) { client.state.value is ClientState.Active }
            await(capability)
            advanceTimeBy(20)
            runCurrent()
            assertTrue("client state=${client.state.value}", client.state.value is ClientState.Active)
        } finally { client.cancel(); srv.close() }
    }

    @Test fun sendInputDropsBeforeActiveAndSequencesEncodedPackets() = runTest {
        val accepted = CountDownLatch(1)
        val received = CountDownLatch(2)
        val packets = java.util.Collections.synchronizedList(mutableListOf<Packet>())
        val srv = server { socket, _ ->
            readPacket(socket)
            accepted.await(5, TimeUnit.SECONDS)
            socket.getOutputStream().write(accept.encodeFrame())
            // Ignore Capabilities / Ping control traffic; only Input packets are asserted.
            while (packets.size < 2) {
                val p = readPacket(socket)
                if (p is Packet.Input) { packets += p; received.countDown() }
            }
        }
        val client = client(this, srv)
        try {
            runCurrent()
            client.sendInput(InputEvent.Touch(0u, 0.2f, 0.3f, TouchPhase.Down))
            accepted.countDown()
            awaitClient(this, client) { client.state.value is ClientState.Active }
            client.sendInput(InputEvent.Touch(0u, 0.2f, 0.3f, TouchPhase.Down))
            client.sendInput(InputEvent.Touch(0u, 0.4f, 0.5f, TouchPhase.Up))
            runCurrent()
            await(received)
            val inputs = packets.map { (it as Packet.Input).packet }
            assertEquals(listOf(0u, 1u), inputs.map { it.seq })
            assertTrue(inputs.all { it.tsUs == 123_456uL })
            assertEquals(InputEvent.Touch(0u, 0.2f, 0.3f, TouchPhase.Down), inputs[0].event)
            assertEquals(InputEvent.Touch(0u, 0.4f, 0.5f, TouchPhase.Up), inputs[1].event)
        } finally { client.cancel(); srv.close() }
    }

    @Test fun rejectVersionMismatchMovesClientToRejected() = runTest {
        val hello = CountDownLatch(1)
        val srv = server { socket, _ ->
            readPacket(socket)
            socket.getOutputStream().write(Packet.Control(ControlMessage.Reject(
                RejectReason.VersionMismatch, 1u
            )).encodeFrame())
            hello.countDown()
        }
        val client = client(this, srv)
        try {
            runCurrent()
            await(hello)
            awaitClient(this, client) { client.state.value == ClientState.Rejected(RejectReason.VersionMismatch, 1) }
            advanceTimeBy(20)
            runCurrent()
            assertEquals(ClientState.Rejected(RejectReason.VersionMismatch, 1), client.state.value)
        } finally { client.cancel(); srv.close() }
    }

    @Test fun silentServerFailsHandshakeAtThreeSeconds() = runTest {
        val hello = CountDownLatch(1)
        val srv = server { socket, _ -> readPacket(socket); hello.countDown(); try { readPacket(socket) } catch (_: Throwable) { } }
        val client = client(this, srv)
        try {
            runCurrent()
            await(hello)
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(ClientState.HandshakeFailed, client.state.value)
        } finally { client.cancel(); srv.close() }
    }

    @Test fun missingPongAndRepeatedFailedTcpAttemptsLoseTenSecondsFromFirstLoss() {
        var m = ConnectionStateMachine.Model(ClientState.Active(accept.message as ControlMessage.Accept), true)
        fun event(e: ConnectionStateMachine.Event) { m = ConnectionStateMachine.create(m).reduce(e).model }
        event(ConnectionStateMachine.Event.PongTimeout(100_000_000L))
        assertEquals(ClientState.Reconnecting, m.state)
        repeat(12) { attempt ->
            event(ConnectionStateMachine.Event.TcpConnected)
            event(ConnectionStateMachine.Event.TransportLost(200_000_000L + attempt * 1_000_000_000L))
        }
        assertEquals(100_000_000L, m.lossStartedAtNs)
        event(ConnectionStateMachine.Event.Tick(10_099_999_999L))
        assertEquals(ClientState.Reconnecting, m.state)
        event(ConnectionStateMachine.Event.Tick(10_100_000_000L))
        assertEquals(ClientState.Lost, m.state)
    }

    @Test fun closedServerConnectionReconnectsAndAcceptsAgain() = runTest {
        val firstHello = CountDownLatch(1)
        val secondHello = CountDownLatch(1)
        val srv = server { socket, index ->
            readPacket(socket)
            if (index == 1) {
                socket.getOutputStream().write(accept.encodeFrame())
                socket.getOutputStream().flush()
                firstHello.countDown()
                socket.close()
            } else {
                socket.getOutputStream().write(accept.encodeFrame())
                secondHello.countDown()
                try { while (true) readPacket(socket) } catch (_: Throwable) { }
            }
        }
        val client = client(this, srv)
        try {
            runCurrent()
            await(firstHello)
            advanceTimeBy(1_000)
            runCurrent()
            await(secondHello)
            awaitClient(this, client) { client.state.value is ClientState.Active }
            advanceTimeBy(20)
            runCurrent()
            assertTrue("reconnect handshake should be active", client.state.value is ClientState.Active)
        } finally { client.cancel(); srv.close() }
    }

    private fun assertIsHello(packet: Packet) {
        assertTrue(packet is Packet.Control && packet.message is ControlMessage.Hello)
    }

    private fun readPacket(socket: Socket): Packet {
        val input = socket.getInputStream()
        val header = ByteArray(4)
        readFully(input, header)
        val size = (header[0].toInt() and 255) or ((header[1].toInt() and 255) shl 8) or
            ((header[2].toInt() and 255) shl 16) or ((header[3].toInt() and 255) shl 24)
        val body = ByteArray(size)
        readFully(input, body)
        val decoder = FrameDecoder()
        decoder.push(header + body)
        return decoder.next() ?: error("incomplete frame")
    }

    private fun readFully(input: java.io.InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = input.read(bytes, offset, bytes.size - offset)
            if (n < 0) throw java.io.EOFException()
            offset += n
        }
    }

    private class FakeServer(private val handler: (Socket, Int) -> Unit) : AutoCloseable {
        private val listener = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int get() = listener.localPort
        @Volatile private var closed = false
        private val thread = Thread {
            var index = 0
            while (!closed) {
                try {
                    val socket = listener.accept()
                    index++
                    try { handler(socket, index) } catch (_: Throwable) { }
                    finally { try { socket.close() } catch (_: Throwable) { } }
                } catch (error: java.net.SocketException) { if (!closed) throw error }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            closed = true
            listener.close()
            thread.join(1_000)
        }
    }
}
