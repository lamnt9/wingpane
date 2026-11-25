package io.wingpane.uc

import android.app.KeyguardManager
import android.os.Build
import android.view.ViewConfiguration
import io.wingpane.WingpaneApplication
import io.wingpane.net.ClientState
import io.wingpane.net.WingpaneClient
import io.wingpane.proto.ControlMessage
import io.wingpane.proto.InputEvent
import io.wingpane.proto.Mode
import io.wingpane.proto.ScrollPhase
import io.wingpane.proto.UcEdge
import io.wingpane.proto.UcExitReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlin.math.hypot

/** Owns UC effects on the accessibility service's main thread. */
class UcController(private val service: WingpaneAccessibilityService, private val client: WingpaneClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val keys = KeyRouter()
    private val ime = ImeTextSink(service)
    private val fallback = SetTextFallbackSink(service)
    private val prefs = service.getSharedPreferences("uc", android.content.Context.MODE_PRIVATE)
    private var state: UcClientState = UcClientState.Idle
    private var lastFlags: UInt? = null
    private var lastOverflow = client.droppedHostInputCount
    private var discreteDx = 0f
    private var discreteDy = 0f
    private var discreteJob: Job? = null
    val inside: Boolean get() = state is UcClientState.Inside
    val edge: UcEdge? get() = when (val s = state) {
        UcClientState.Idle -> null
        is UcClientState.Armed -> s.edge
        is UcClientState.Inside -> s.edge
    }

    fun start() {
        scope.launch { client.ucControl.collect { control(it) } }
        scope.launch { client.hostInput.collect { input(it) } }
        scope.launch {
            client.state.collect { if (it !is ClientState.Active) {
                transition(UcClientEvent.Disconnected)
                lastFlags = null
            } }
        }
        scope.launch {
            while (true) {
                if (client.droppedHostInputCount != lastOverflow) {
                    lastOverflow = client.droppedHostInputCount
                    returnMouse()
                }
                val flags = statusFlags()
                if (flags != lastFlags && client.supportsUniversalControl()) {
                    lastFlags = flags
                    client.sendControl(ControlMessage.UcStatus(flags))
                }
                delay(200)
            }
        }
    }

    fun stop() {
        transition(UcClientEvent.Disconnected)
        if (client.supportsUniversalControl()) {
            CoroutineScope(Dispatchers.IO).launch { client.sendControl(ControlMessage.UcStatus(0u)) }
        }
        scope.cancel()
    }

    fun returnMouse() {
        if (!inside) return
        val along = service.cursorAlong()
        transition(UcClientEvent.Exit)
        scope.launch { client.sendControl(ControlMessage.UcExit(along, UcExitReason.CursorReturned)) }
    }

    private fun control(message: ControlMessage) {
        when (message) {
            is ControlMessage.UcConfig -> transition(UcClientEvent.Config(message.edge))
            is ControlMessage.UcEnter -> {
                if (!ready()) return
                transition(UcClientEvent.Enter(message.edge))
                val entry = opposite(message.edge)
                service.enterUc(entry, message.along, prefs.getFloat("gain", 1f))
                when (message.imeHint.toInt()) {
                    1 -> keys.viMode = false
                    2 -> keys.viMode = true
                }
            }
            is ControlMessage.UcExit -> transition(UcClientEvent.Exit)
            is ControlMessage.SetMode -> if (message.mode != Mode.AndroidControl) transition(UcClientEvent.Exit)
            else -> Unit
        }
    }

    private fun input(event: InputEvent) {
        if (!inside || !ready()) return
        val pointer = service.injector()
        when (event) {
            is InputEvent.PointerDelta -> pointer.move(event.dx, event.dy)
            is InputEvent.MouseButton -> pointer.button(event.button.toInt(), event.isDown)
            is InputEvent.PreciseScroll -> {
                val direction = if (prefs.getBoolean("reverse_scroll", false)) -1f else 1f
                val dx = event.dx * direction
                val dy = event.dy * direction
                when (event.phase) {
                    ScrollPhase.Discrete -> {
                        discreteDx += dx; discreteDy += dy
                        discreteJob?.cancel()
                        discreteJob = scope.launch {
                            delay(40)
                            val length = hypot(discreteDx, discreteDy)
                            if (length > 0f) {
                                val minimum = 2f * ViewConfiguration.get(service).scaledTouchSlop / service.resources.displayMetrics.density
                                val scale = maxOf(1f, minimum / length)
                                pointer.scroll(discreteDx * scale, discreteDy * scale, CursorEvent.Phase.Discrete)
                            }
                            discreteDx = 0f; discreteDy = 0f
                        }
                    }
                    ScrollPhase.Began -> {
                        pointer.scroll(0f, 0f, CursorEvent.Phase.Began)
                        if (dx != 0f || dy != 0f) pointer.scroll(dx, dy, CursorEvent.Phase.Changed)
                    }
                    ScrollPhase.Changed -> pointer.scroll(dx, dy, CursorEvent.Phase.Changed)
                    ScrollPhase.Ended -> {
                        if (dx != 0f || dy != 0f) pointer.scroll(dx, dy, CursorEvent.Phase.Changed)
                        pointer.scroll(0f, 0f, CursorEvent.Phase.Changed)
                        pointer.scroll(0f, 0f, CursorEvent.Phase.Ended)
                    }
                    ScrollPhase.Momentum -> Unit
                }
            }
            is InputEvent.Keyboard -> {
                val style = if (prefs.getBoolean("old_tone", false)) ToneStyle.Old else ToneStyle.New
                if (keys.composer.style != style) { keys.reset(); keys.composer.style = style }
                keys.onKey(event.usbHidUsage.toInt(), event.modifiers.toInt(), event.isDown, service.editorFocused)
                    .forEach(::performKey)
            }
            else -> Unit
        }
    }

    private fun performKey(action: KeyAction) {
        fun withSink(block: (TextSink) -> Boolean) { if (!block(ime)) block(fallback) }
        when (action) {
            is KeyAction.Commit -> withSink { it.edit(action.edit) }
            is KeyAction.SendKey -> withSink { it.key(action.keycode, action.meta) }
            is KeyAction.Context -> withSink { it.context(action.action) }
            KeyAction.DeleteWord -> withSink { it.deleteWord() }
            KeyAction.ToggleVi -> Unit
            is KeyAction.Global -> service.injector().global(when (action.action) {
                Global.BACK -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                Global.HOME -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                Global.RECENTS -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                Global.NOTIFICATIONS -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                Global.TAKE_SCREENSHOT -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                Global.LOCK_SCREEN -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            })
        }
    }

    private fun transition(event: UcClientEvent) {
        val result = reduceUc(state, event)
        state = result.state
        if (UcClientEffect.CancelAll in result.effects || UcClientEffect.HideCursor in result.effects) {
            discreteJob?.cancel(); discreteJob = null; discreteDx = 0f; discreteDy = 0f
            service.exitUc()
            keys.reset()
        }
    }

    private fun ready(): Boolean {
        val flags = statusFlags()
        return flags and 0x13u == 0x13u && flags and 0x8u == 0u
    }

    private fun statusFlags(): UInt {
        val app = service.application as WingpaneApplication
        var flags = 1u
        if (service.gesturesAvailable()) flags = flags or 2u
        if (Build.VERSION.SDK_INT >= 33 && service.inputMethod != null) flags = flags or 4u
        if (service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) flags = flags or 8u
        if (app.foregroundServiceRunning) flags = flags or 16u
        if (inside) flags = flags or 32u
        return flags
    }

    private fun opposite(edge: UcEdge): CursorModel.Edge = when (edge) {
        UcEdge.Left -> CursorModel.Edge.Right
        UcEdge.Right -> CursorModel.Edge.Left
        UcEdge.Top -> CursorModel.Edge.Bottom
        UcEdge.Bottom -> CursorModel.Edge.Top
    }
}
