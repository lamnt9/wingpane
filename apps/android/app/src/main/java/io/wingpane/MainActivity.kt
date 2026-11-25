package io.wingpane

import android.content.pm.ActivityInfo
import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.wingpane.input.TrackpadGestureDetector
import io.wingpane.net.ClientState
import io.wingpane.net.WingpaneClient
import io.wingpane.proto.ControlMessage
import io.wingpane.proto.InputEvent
import io.wingpane.proto.Packet
import io.wingpane.proto.Mode
import io.wingpane.proto.PenTarget
import io.wingpane.ui.hud.StreamHudOverlay
import io.wingpane.ui.hud.StreamTouchPoint
import io.wingpane.ui.screen.ConnectionScreen
import io.wingpane.ui.screen.PenCanvasScreen
import io.wingpane.ui.screen.TrackpadScreen
import io.wingpane.ui.screen.AndroidControlScreen
import io.wingpane.uc.WingpaneAccessibilityService
import android.provider.Settings
import io.wingpane.ui.state.ClientStateMapper
import io.wingpane.ui.theme.WingpaneTheme
import io.wingpane.video.VideoDecoder
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var client: WingpaneClient
    private var ucServiceCheck: Job? = null
    private val clockSyncCallback: (io.wingpane.net.ClockOffsetEstimator.Estimate) -> Unit = { sync ->
        decoder.updateClockSync(sync.offsetUs, sync.rttUs)
    }
    private lateinit var decoder: VideoDecoder
    private var clientState by mutableStateOf<ClientState>(ClientState.Waiting)
    private var mode by mutableStateOf(Mode.ExtendedDisplay)
    private var ucServiceConnected by mutableStateOf(false)
    private var ucFgsRunning by mutableStateOf(false)
    private var ucEdge by mutableStateOf<io.wingpane.proto.UcEdge?>(null)
    private var activeTrackpadDetector: TrackpadGestureDetector? = null
    private var stats by mutableStateOf(VideoDecoder.Stats(0f, 0, 0, 0, 0, 0, 0, 0, 0))
    private var lastBitrateKbps by mutableStateOf(0u)
    private var videoAspectRatio by mutableStateOf<Float?>(null)
    private var touchMapper: TouchMapper? = null
    private var touchMapperWidth: Int? = null
    private var touchMapperHeight: Int = 0
    private val palmFilter = PalmRejectionFilter()
    private var lastStylusSample: InputEvent.Pen? = null
    private var pressureCurve by mutableStateOf(listOf(.25f, .25f, .75f, .75f))
    private var penTarget by mutableStateOf(PenTarget.VirtualDisplay)
    private var penTargetCustomized = false
    private fun hostSupportsPen() = client.hostCapabilitiesReceived.value && (client.hostFeatures.value and 1) != 0

    private var activeStreamTouch by mutableStateOf<StreamTouchPoint?>(null)
    private var threeFingerTapHud by mutableStateOf(false)
    private var hudOpenRequestTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storedPalm = getSharedPreferences("input", MODE_PRIVATE).getString("palm_rejection", "Standard")
        val inputPrefs = getSharedPreferences("input", MODE_PRIVATE)
        penTargetCustomized = inputPrefs.getBoolean("pen_target_customized", false)
        pressureCurve = (0..3).map { inputPrefs.getFloat("pressure_curve_$it", listOf(.25f, .25f, .75f, .75f)[it]) }
        penTarget = runCatching { PenTarget.valueOf(inputPrefs.getString("pen_target", PenTarget.VirtualDisplay.name)!!) }.getOrDefault(PenTarget.VirtualDisplay)
        palmFilter.setLevel(runCatching { PalmRejectionFilter.Level.valueOf(storedPalm ?: "Standard") }.getOrDefault(PalmRejectionFilter.Level.Standard))
        applyImmersiveSticky()
        decoder = VideoDecoder({ lifecycleScope.launch { client.sendControl(ControlMessage.RequestKeyframe) } }) { stats = it }
        client = (application as WingpaneApplication).clientHolder.client
        mode = (application as WingpaneApplication).currentMode
        client.onClockSync = clockSyncCallback
        if (client.state.value is ClientState.Waiting) client.connect()
        lifecycleScope.launch {
            var wasActive = client.state.value is ClientState.Active
            client.state.collect { nextState ->
                val activeNow = nextState is ClientState.Active
                val becameActive = !wasActive && activeNow
                if (wasActive && !activeNow) cancelTouch()
                wasActive = activeNow
                clientState = nextState
                if (nextState is ClientState.Active) {
                    if (becameActive) {
                        mode = Mode.ExtendedDisplay
                        (application as WingpaneApplication).currentMode = mode
                    }
                    if (!penTargetCustomized) penTarget = PenTarget.VirtualDisplay
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
        lifecycleScope.launch {
            client.ucControl.collect { message ->
                if (message is ControlMessage.SetMode) {
                    cancelTouch()
                    mode = message.mode
                    (application as WingpaneApplication).currentMode = mode
                    if (mode == Mode.AndroidControl && WingpaneAccessibilityService.current() != null) {
                        (application as WingpaneApplication).startUcServiceIfEligible()
                        moveTaskToBack(true)
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
            var language by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(io.wingpane.ui.util.AppLanguage.load(this@MainActivity)) }
            io.wingpane.ui.util.LocalizedContent(language) {
            WingpaneTheme {
                val uiModel = ClientStateMapper.map(clientState)
                val hostFeatures by client.hostFeatures.collectAsState()
                val hostCapabilitiesReceived by client.hostCapabilitiesReceived.collectAsState()
                LaunchedEffect(clientState, hostCapabilitiesReceived, hostFeatures, pressureCurve, penTarget) {
                    if (clientState is ClientState.Active && hostCapabilitiesReceived && (hostFeatures and (1 shl 5)) != 0) {
                        lifecycleScope.launch {
                            client.sendControl(ControlMessage.PenConfig(pressureCurve, penTarget))
                        }
                    }
                }
                var keepHudVisible by rememberSaveable { mutableStateOf(false) }
                var lockAspectRatio by rememberSaveable { mutableStateOf(true) }
                var showTouches by rememberSaveable { mutableStateOf(false) }
                var palmLevel by rememberSaveable { mutableStateOf(palmFilter.currentLevel().name) }
                val trackpadDetector = androidx.compose.runtime.remember { TrackpadGestureDetector(tapSlopPx = 8f * resources.displayMetrics.density, threeFingerSwipePx = 60f * resources.displayMetrics.density) }
                activeTrackpadDetector = trackpadDetector

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
                    val surfaceModifier = if (mode == Mode.TrackpadKeyboard) {
                        // Trackpad mode: Mac screen on top, pad + keyboard below.
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().fillMaxHeight(0.42f)
                    } else if (lockAspectRatio && ratio != null && ratio > 0f) {
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

                    when (mode) {
                        Mode.GraphicsTablet -> if (uiModel.isStreamScreen) PenCanvasScreen(onStylus = { event, view ->
                            if (hostSupportsPen()) handleStylus(view, event, hover = event.actionMasked in setOf(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT)) else false
                        })
                        Mode.TrackpadKeyboard -> if (uiModel.isStreamScreen) TrackpadScreen(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.58f), detector = trackpadDetector, onEvents = { events -> events.forEach { event ->
                            val allowed = when (event) {
                                is InputEvent.Keyboard -> hostCapabilitiesReceived && (hostFeatures and (1 shl 3)) != 0
                                is InputEvent.Text -> hostCapabilitiesReceived && (hostFeatures and (1 shl 4)) != 0
                                is InputEvent.Gesture -> when (event.kind) {
                                    io.wingpane.proto.GestureKind.Swipe3Up, io.wingpane.proto.GestureKind.Swipe3Down,
                                    io.wingpane.proto.GestureKind.Swipe3Left, io.wingpane.proto.GestureKind.Swipe3Right,
                                    is io.wingpane.proto.GestureKind.PinchZoom -> hostCapabilitiesReceived && (hostFeatures and (1 shl 2)) != 0
                                    else -> true
                                }
                                else -> true
                            }
                            if (allowed) client.sendInput(event)
                        } })
                        Mode.ExtendedDisplay, Mode.AndroidControl -> Unit
                    }
                    if (uiModel.isStreamScreen) {
                        StreamHudOverlay(
                            language = language,
                            onLanguageChange = { language = it; io.wingpane.ui.util.AppLanguage.save(this@MainActivity, it) },
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
                            mode = mode,
                            hostFeatures = hostFeatures,
                            capabilitiesReceived = hostCapabilitiesReceived,
                            onModeChange = modeChange@ { next ->
                                if (next != mode) {
                                    if (next == Mode.AndroidControl && !client.supportsUniversalControl()) return@modeChange
                                    if (next == Mode.AndroidControl && WingpaneAccessibilityService.current() == null) {
                                        android.widget.Toast.makeText(this@MainActivity, R.string.uc_enable_service_hint, android.widget.Toast.LENGTH_LONG).show()
                                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                        return@modeChange
                                    }
                                    cancelTouch()
                                    mode = next
                                    (application as WingpaneApplication).currentMode = next
                                    if (!penTargetCustomized) {
                                        penTarget = when (next) {
                                            Mode.GraphicsTablet -> PenTarget.MainDisplay
                                            Mode.ExtendedDisplay, Mode.TrackpadKeyboard, Mode.AndroidControl -> PenTarget.VirtualDisplay
                                        }
                                    }
                                    lifecycleScope.launch {
                                        client.sendControl(ControlMessage.SetMode(next))
                                        if (next == Mode.ExtendedDisplay) client.sendControl(ControlMessage.RequestKeyframe)
                                    }
                                    if (next == Mode.AndroidControl) {
                                        (application as WingpaneApplication).startUcServiceIfEligible()
                                        moveTaskToBack(true)
                                    }
                                }
                            },
                            pressureCurve = pressureCurve,
                            penTarget = penTarget,
                            onPenConfigChange = { curve, target ->
                                if (curve.size == 4) pressureCurve = curve
                                if (target != penTarget) penTargetCustomized = true
                                penTarget = target
                                getSharedPreferences("input", MODE_PRIVATE).edit().apply {
                                    pressureCurve.forEachIndexed { index, value -> putFloat("pressure_curve_$index", value) }
                                    putString("pen_target", penTarget.name)
                                    putBoolean("pen_target_customized", penTargetCustomized)
                                }.apply()
                            },
                        )
                    } else {
                        ConnectionScreen(
                            language = language,
                            onLanguageChange = { language = it; io.wingpane.ui.util.AppLanguage.save(this@MainActivity, it) },
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
                    if (uiModel.isStreamScreen && mode == Mode.AndroidControl) {
                        AndroidControlScreen(
                            serviceConnected = ucServiceConnected,
                            fgsRunning = ucFgsRunning,
                            edge = ucEdge,
                            onDisplay = { handleUcNotificationAction(Intent().setAction(io.wingpane.uc.UcForegroundService.ACTION_DISPLAY)) },
                            onAccessibilitySettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        )
                    }
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
        if (mode != Mode.ExtendedDisplay) return true
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
        val videoWidth = if (mode == Mode.GraphicsTablet) 1600 else touchMapperWidth ?: return false
        val videoHeight = if (mode == Mode.GraphicsTablet) 1000 else touchMapperHeight
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
        activeTrackpadDetector?.onTouch(TrackpadGestureDetector.Action.Cancel, emptyList(), SystemClock.uptimeMillis())?.forEach(client::sendInput)
    }

    override fun onResume() {
        super.onResume()
        handleUcNotificationAction(intent)
        val app = application as WingpaneApplication
        if ((app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            intent?.hasExtra("uc_force_fgs") == true) {
            app.ucForceFgs = intent.getBooleanExtra("uc_force_fgs", false)
        }
        ucServiceCheck = lifecycleScope.launch {
            while (true) {
                app.startUcServiceIfEligible()
                ucServiceConnected = WingpaneAccessibilityService.current() != null
                ucFgsRunning = app.foregroundServiceRunning
                ucEdge = WingpaneAccessibilityService.current()?.ucEdge()
                delay(500)
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUcNotificationAction(intent)
    }
    private fun handleUcNotificationAction(intent: Intent?) {
        if (intent?.action != io.wingpane.uc.UcForegroundService.ACTION_DISPLAY) return
        intent.action = null
        mode = Mode.ExtendedDisplay
        (application as WingpaneApplication).currentMode = mode
        lifecycleScope.launch {
            client.sendControl(ControlMessage.SetMode(Mode.ExtendedDisplay))
            client.sendControl(ControlMessage.RequestKeyframe)
        }
    }
    override fun onStart() {
        super.onStart()
        client.videoSinkAttached = true
        if (client.isActive()) lifecycleScope.launch { client.sendControl(ControlMessage.RequestKeyframe) }
    }
    override fun onPause() { ucServiceCheck?.cancel(); ucServiceCheck = null; cancelTouch(); super.onPause() }
    override fun onStop() { client.videoSinkAttached = false; cancelTouch(); super.onStop() }
    override fun onDestroy() {
        cancelTouch()
        if (client.onClockSync === clockSyncCallback) client.onClockSync = null
        val app = application as WingpaneApplication
        if (!isChangingConfigurations && !app.foregroundServiceRunning) {
            client.cancel()
            app.clientHolder.clear()
        }
        decoder.close()
        super.onDestroy()
    }
}

private const val TOOL_TYPE_PALM = 5
