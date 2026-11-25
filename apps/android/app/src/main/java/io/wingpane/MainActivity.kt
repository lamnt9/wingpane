package io.wingpane

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.wingpane.input.TouchMapper
import io.wingpane.input.StylusMapper
import io.wingpane.input.PalmRejectionFilter
import io.wingpane.net.ClientState
import io.wingpane.net.WingpaneClient
import io.wingpane.proto.ControlMessage
import io.wingpane.proto.InputEvent
import io.wingpane.proto.Packet
import io.wingpane.ui.hud.StreamHudOverlay
import io.wingpane.ui.hud.StreamTouchPoint
import io.wingpane.ui.screen.ConnectionScreen
import io.wingpane.ui.state.ClientStateMapper
import io.wingpane.ui.theme.WingpaneTheme
import io.wingpane.video.VideoDecoder
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var client: WingpaneClient
    private lateinit var decoder: VideoDecoder
    private var clientState by mutableStateOf<ClientState>(ClientState.Waiting)
    private var stats by mutableStateOf(VideoDecoder.Stats(0f, 0, 0, 0, 0, 0, 0, 0, 0))
    private var lastBitrateKbps by mutableStateOf(0u)
    private var videoAspectRatio by mutableStateOf<Float?>(null)
    private var touchMapper: TouchMapper? = null
    private var touchMapperWidth: Int? = null
    private var touchMapperHeight: Int = 0
    private val palmFilter = PalmRejectionFilter()
    private var lastStylusSample: InputEvent.Pen? = null
    private fun hostSupportsPen() = client.hostCapabilitiesReceived.value && (client.hostFeatures.value and 1) != 0

    private var activeStreamTouch by mutableStateOf<StreamTouchPoint?>(null)
    private var threeFingerTapHud by mutableStateOf(false)
    private var hudOpenRequestTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storedPalm = getSharedPreferences("input", MODE_PRIVATE).getString("palm_rejection", "Standard")
        palmFilter.setLevel(runCatching { PalmRejectionFilter.Level.valueOf(storedPalm ?: "Standard") }.getOrDefault(PalmRejectionFilter.Level.Standard))
        applyImmersiveSticky()
        decoder = VideoDecoder({ lifecycleScope.launch { client.sendControl(ControlMessage.RequestKeyframe) } }) { stats = it }
        client = WingpaneClient()
        client.onClockSync = { sync -> decoder.updateClockSync(sync.offsetUs, sync.rttUs) }
        client.connect()
        lifecycleScope.launch {
            var wasActive = false
            client.state.collect { nextState ->
                val activeNow = nextState is ClientState.Active
                if (wasActive && !activeNow) cancelTouch()
                wasActive = activeNow
                clientState = nextState
                if (nextState is ClientState.Active) {
                    lastBitrateKbps = nextState.accept.bitrateKbps
                    nextState.accept.virtualDisplaySpec?.let { spec ->
                        val w = spec.first.toInt()
                        val h = spec.second.toInt()
                        if (w > 0 && h > 0) {
                            videoAspectRatio = w.toFloat() / h.toFloat()
                        }
                        touchMapper = TouchMapper(w, h)
                        touchMapperWidth = w
                        touchMapperHeight = h
                        decoder.configure(w, h)
                    }
                }
            }
        }
        // Video must never wait for the UI thread (lifecycleScope defaults to Main.immediate).
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            client.packets.collect { packet ->
                if (packet is Packet.Video) {
                    decoder.queueAccessUnit(packet.packet.payload, packet.packet.ptsUs.toLong(), client.takeVideoReceivedAtNs(packet.packet.ptsUs.toLong()))
                }
            }
        }
        setContent {
            WingpaneTheme {
                val uiModel = ClientStateMapper.map(clientState)
                val hostFeatures by client.hostFeatures.collectAsState()
                val hostCapabilitiesReceived by client.hostCapabilitiesReceived.collectAsState()
                var keepHudVisible by rememberSaveable { mutableStateOf(false) }
                var lockAspectRatio by rememberSaveable { mutableStateOf(true) }
                var showTouches by rememberSaveable { mutableStateOf(false) }
                var palmLevel by rememberSaveable { mutableStateOf(palmFilter.currentLevel().name) }

                // Brief: Stream forces sensorLandscape; connection screens support both tablet landscape & phone portrait.
                LaunchedEffect(uiModel.isStreamScreen) {
                    requestedOrientation = if (uiModel.isStreamScreen) {
                        applyImmersiveSticky()
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(WingpaneTheme.colors.bg),
                    contentAlignment = Alignment.Center,
                ) {
                    val ratio = videoAspectRatio
                    val surfaceModifier = if (lockAspectRatio && ratio != null && ratio > 0f) {
                        Modifier
                            .fillMaxSize()
                            .aspectRatio(ratio, matchHeightConstraintsFirst = false)
                    } else {
                        Modifier.fillMaxSize()
                    }

                    // Existing SurfaceView (DEFAULT z-order, do not change)
                    AndroidView(
                        factory = { context ->
                            SurfaceView(context).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        decoder.setSurface(holder.surface)
                                    }

                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                        decoder.setSurface(holder.surface)
                                    }

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        decoder.setSurface(null)
                                    }
                                })
                                setOnTouchListener { view, event -> handleTouch(view, event) }
                                setOnGenericMotionListener { view, event ->
                                    when (event.actionMasked) {
                                        MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT ->
                                            if (hostSupportsPen()) handleStylus(view, event, hover = true) else false
                                        else -> false
                                    }
                                }
                            }
                        },
                        modifier = surfaceModifier,
                    )

                    if (uiModel.isStreamScreen) {
                        StreamHudOverlay(
                            stats = stats,
                            bitrateKbps = if (uiModel.streamBitrateKbps > 0u) uiModel.streamBitrateKbps else lastBitrateKbps,
                            isReconnecting = uiModel.isReconnecting,
                            accessibilityMissing = hostCapabilitiesReceived && (hostFeatures and (1 shl 6)) == 0,
                            keepHudVisible = keepHudVisible,
                            lockAspectRatio = lockAspectRatio,
                            onLockAspectRatioChange = { lockAspectRatio = it },
                            showTouches = showTouches,
                            onShowTouchesChange = { showTouches = it },
                            palmRejectionLevel = PalmRejectionFilter.Level.valueOf(palmLevel),
                            onPalmRejectionLevelChange = { level ->
                                palmLevel = level.name
                                palmFilter.setLevel(level)
                                getSharedPreferences("input", MODE_PRIVATE).edit().putString("palm_rejection", level.name).apply()
                            },
                            activeStreamTouch = activeStreamTouch,
                            externalOpenRequestTick = hudOpenRequestTick,
                            onDisconnect = {
                                cancelTouch()
                                client.disconnect() // -> Disconnected; no auto-reconnect
                            },
                            onCancelReconnect = {
                                cancelTouch()
                                client.disconnect()
                                client.connect()
                            },
                        )
                    } else {
                        ConnectionScreen(
                            uiModel = uiModel,
                            keepHudVisible = keepHudVisible,
                            onKeepHudVisibleChange = { keepHudVisible = it },
                            threeFingerTapHud = threeFingerTapHud,
                            onThreeFingerTapHudChange = { threeFingerTapHud = it },
                            onCancelHandshake = {
                                client.disconnect()
                                client.connect()
                            },
                            onTryAgain = {
                                client.disconnect()
                                client.connect()
                            },
                        )
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveSticky() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        val rawX = event.x + view.left
        val rawY = event.y + view.top
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                activeStreamTouch = StreamTouchPoint(rawX, rawY, isDown = true)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                activeStreamTouch = StreamTouchPoint(
                    xPx = rawX,
                    yPx = rawY,
                    isDown = false,
                    liftedAtElapsedMs = SystemClock.elapsedRealtime(),
                )
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (threeFingerTapHud && event.pointerCount == 3) {
                    hudOpenRequestTick++
                }
            }
        }

        val mapper = touchMapper ?: return false
        val stylusIndex = (0 until event.pointerCount).firstOrNull {
            event.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
        }
        if (stylusIndex != null && hostSupportsPen()) {
            mapper.cancel().forEach(client::sendInput)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) view.requestUnbufferedDispatch(event)
            return handleStylus(view, event, hover = false, pointerIndex = stylusIndex)
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) palmFilter.newTouchContact()
        val nowNs = event.eventTime * 1_000_000L
        val samples = (0 until event.pointerCount).map { index ->
            val tool = when (event.getToolType(index)) {
                MotionEvent.TOOL_TYPE_FINGER -> PalmRejectionFilter.Tool.Finger
                TOOL_TYPE_PALM -> PalmRejectionFilter.Tool.Palm // MotionEvent.TOOL_TYPE_PALM (API 34); literal keeps minSdk 29
                else -> PalmRejectionFilter.Tool.Other
            }
            PalmRejectionFilter.PointerSample(event.getPointerId(index), tool, event.getX(index), event.getY(index),
                event.getTouchMajor(index), android.os.Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0, nowNs)
        }
        val filtered = palmFilter.filter(samples, nowNs, view.width.toFloat(), view.height.toFloat(), resources.displayMetrics.density,
            resources.displayMetrics.xdpi / 25.4f, mapper.activePointerId())
        if (filtered.cancelActiveTouch) mapper.cancel().forEach(client::sendInput)
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchMapper.Action.Down
            MotionEvent.ACTION_MOVE -> TouchMapper.Action.Move
            MotionEvent.ACTION_UP -> TouchMapper.Action.Up
            MotionEvent.ACTION_POINTER_UP -> {
                val activeId = mapper.activePointerId() ?: return true
                if (event.getPointerId(event.actionIndex) != activeId) return true
                TouchMapper.Action.Up
            }
            MotionEvent.ACTION_CANCEL -> TouchMapper.Action.Cancel
            else -> return true // Ignore secondary pointers for the single-pointer MVP.
        }
        val pointerId = mapper.activePointerId()
        val pointerIndex = when (action) {
            TouchMapper.Action.Down -> event.actionIndex
            TouchMapper.Action.Cancel -> 0
            else -> pointerId?.let(event::findPointerIndex)?.takeIf { it >= 0 } ?: return true
        }
        if (filtered.accepted.none { it.id == event.getPointerId(pointerIndex) }) {
            mapper.cancel().forEach(client::sendInput)
            return true
        }
        val historical = if (action == TouchMapper.Action.Move && filtered.accepted.any { it.id == event.getPointerId(pointerIndex) }) {
            val points = ArrayList<TouchMapper.Point>(event.historySize)
            for (index in 0 until event.historySize) {
                points += TouchMapper.Point(event.getHistoricalX(pointerIndex, index), event.getHistoricalY(pointerIndex, index))
            }
            points
        } else emptyList()
        val eventPointerId = if (action == TouchMapper.Action.Down) event.getPointerId(pointerIndex) else pointerId ?: event.getPointerId(pointerIndex)
        mapper.map(action, view.width, view.height, eventPointerId, event.getX(pointerIndex), event.getY(pointerIndex), historical)
            .forEach(client::sendInput)
        return true
    }

    private fun handleStylus(view: View, event: MotionEvent, hover: Boolean, pointerIndex: Int = event.actionIndex.coerceIn(0, event.pointerCount - 1)): Boolean {
        if (event.pointerCount == 0) return false
        val tool = event.getToolType(pointerIndex)
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) return false
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> StylusMapper.Action.Down
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> StylusMapper.Action.Up
            MotionEvent.ACTION_CANCEL -> StylusMapper.Action.Cancel
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> StylusMapper.Action.Hover
            MotionEvent.ACTION_HOVER_EXIT -> StylusMapper.Action.Exit
            else -> if (hover) StylusMapper.Action.Hover else StylusMapper.Action.Move
        }
        val inRange = action != StylusMapper.Action.Exit && action != StylusMapper.Action.Cancel
        val timeNs = event.eventTime * 1_000_000L
        palmFilter.noteStylus(inRange, timeNs)
        val videoWidth = touchMapperWidth ?: return false
        val videoHeight = touchMapperHeight
        if (action == StylusMapper.Action.Move) {
            for (historyIndex in 0 until event.historySize) {
                val historical = StylusMapper.Sample(
                    event.getHistoricalX(pointerIndex, historyIndex), event.getHistoricalY(pointerIndex, historyIndex),
                    event.getHistoricalPressure(pointerIndex, historyIndex), event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, historyIndex),
                    event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex, historyIndex), event.buttonState, action, false,
                    event.getHistoricalEventTime(historyIndex) * 1_000_000L, tool == MotionEvent.TOOL_TYPE_ERASER)
                StylusMapper.map(historical, videoWidth, videoHeight, view.width, view.height)?.let(::sendPen)
            }
        }
        val sample = StylusMapper.Sample(event.getX(pointerIndex), event.getY(pointerIndex), event.getPressure(pointerIndex),
            event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex), event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex),
            event.buttonState, action, hover || action == StylusMapper.Action.Up || action == StylusMapper.Action.Hover,
            timeNs, tool == MotionEvent.TOOL_TYPE_ERASER)
        StylusMapper.map(sample, videoWidth, videoHeight, view.width, view.height)?.let(::sendPen)
        return true
    }

    private fun sendPen(sample: InputEvent.Pen) {
        client.sendInput(sample)
        lastStylusSample = if (sample.isDown || sample.isHover) sample else null
    }

    private fun cancelStylus() {
        lastStylusSample?.copy(
            pressure = 0f, isDown = false, isHover = false, buttonMask = 0u.toUByte(),
            timestampUs = (SystemClock.elapsedRealtimeNanos() / 1_000).toULong(),
        )?.let(client::sendInput)
        lastStylusSample = null
        palmFilter.noteStylus(false, SystemClock.elapsedRealtimeNanos())
    }

    private fun cancelTouch() {
        activeStreamTouch = null
        touchMapper?.cancel()?.forEach(client::sendInput)
        cancelStylus()
    }

    override fun onPause() { cancelTouch(); super.onPause() }
    override fun onStop() { cancelTouch(); super.onStop() }
    override fun onDestroy() { cancelTouch(); client.cancel(); decoder.close(); super.onDestroy() }
}

private const val TOOL_TYPE_PALM = 5
