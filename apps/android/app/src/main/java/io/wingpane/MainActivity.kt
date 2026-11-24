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
import io.wingpane.net.ClientState
import io.wingpane.net.WingpaneClient
import io.wingpane.proto.ControlMessage
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

    private var activeStreamTouch by mutableStateOf<StreamTouchPoint?>(null)
    private var threeFingerTapHud by mutableStateOf(false)
    private var hudOpenRequestTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        decoder.configure(w, h)
                    }
                }
            }
        }
        lifecycleScope.launch {
            client.packets.collect { packet ->
                if (packet is Packet.Video) {
                    decoder.queueAccessUnit(packet.packet.payload, packet.packet.ptsUs.toLong())
                }
            }
        }
        setContent {
            WingpaneTheme {
                val uiModel = ClientStateMapper.map(clientState)
                var keepHudVisible by rememberSaveable { mutableStateOf(false) }
                var lockAspectRatio by rememberSaveable { mutableStateOf(true) }
                var showTouches by rememberSaveable { mutableStateOf(false) }

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
                            }
                        },
                        modifier = surfaceModifier,
                    )

                    if (uiModel.isStreamScreen) {
                        StreamHudOverlay(
                            stats = stats,
                            bitrateKbps = if (uiModel.streamBitrateKbps > 0u) uiModel.streamBitrateKbps else lastBitrateKbps,
                            isReconnecting = uiModel.isReconnecting,
                            keepHudVisible = keepHudVisible,
                            lockAspectRatio = lockAspectRatio,
                            onLockAspectRatioChange = { lockAspectRatio = it },
                            showTouches = showTouches,
                            onShowTouchesChange = { showTouches = it },
                            activeStreamTouch = activeStreamTouch,
                            externalOpenRequestTick = hudOpenRequestTick,
                            onDisconnect = {
                                cancelTouch()
                                client.disconnect()
                                client.connect()
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
        val historical = if (action == TouchMapper.Action.Move) {
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

    private fun cancelTouch() {
        activeStreamTouch = null
        touchMapper?.cancel()?.forEach(client::sendInput)
    }

    override fun onPause() { cancelTouch(); super.onPause() }
    override fun onStop() { cancelTouch(); super.onStop() }
    override fun onDestroy() { cancelTouch(); client.cancel(); decoder.close(); super.onDestroy() }
}
