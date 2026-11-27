package io.wingpane.input

import io.wingpane.proto.GestureKind
import io.wingpane.proto.InputEvent
import kotlin.math.abs
import kotlin.math.hypot

/** Pure pointer state machine. Coordinates and scroll deltas are pixels; callers supply monotonic ms. */
class TrackpadGestureDetector(private val gainK: Float = 0.8f, private val tapSlopPx: Float = 8f, private val threeFingerSwipePx: Float = 60f) {
    data class PointerSample(val id: Int, val x: Float, val y: Float)
    enum class Action { Down, Move, Up, Cancel }
    private var startAt = 0L
    private var lastAt = 0L
    private var startX = 0f; private var startY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var count = 0
    private var startCount = 0
    private var moved = false
    private var dragging = false
    private var lastTapUpAt = -1L
    private var scrollVelocityX = 0f; private var scrollVelocityY = 0f
    private var inertiaUntil = 0L
    private var gestureBaseDistance = 0f
    private var gestureSent = false
    private var buttonHeld = false
    private var touchStartedButtonHeld = false
    private var fingerStart = emptyMap<Int, Pair<Float, Float>>()

    private fun centroidX(p: List<PointerSample>) = p.map { it.x }.average().toFloat()
    private fun centroidY(p: List<PointerSample>) = p.map { it.y }.average().toFloat()

    fun onTouch(action: Action, pointers: List<PointerSample>, nowMs: Long): List<InputEvent> {
        val out = mutableListOf<InputEvent>()
        when (action) {
            Action.Down -> if (pointers.isNotEmpty()) {
                startAt=nowMs; lastAt=nowMs; count=pointers.size; startCount=pointers.size
                startX=centroidX(pointers); startY=centroidY(pointers); lastX=startX; lastY=startY
                fingerStart=pointers.associate { it.id to (it.x to it.y) }
                moved=false; dragging=false; inertiaUntil=0; touchStartedButtonHeld=buttonHeld
                if (pointers.size == 1 && !buttonHeld && lastTapUpAt >= 0 && nowMs-lastTapUpAt <= DOUBLE_TAP_MS) {
                    out += InputEvent.MouseButton(0u,true); dragging=true
                }
                lastTapUpAt=-1L
                gestureBaseDistance=if (pointers.size >= 5) spread(pointers) else pairDistance(pointers); gestureSent=false
            }
            Action.Move -> if (pointers.isNotEmpty()) {
                val cx=centroidX(pointers); val cy=centroidY(pointers)
                if (pointers.size != count) {
                    count=pointers.size; startCount=maxOf(startCount,count)
                    startX=cx; startY=cy; lastX=cx; lastY=cy; lastAt=nowMs
                    fingerStart=pointers.associate { it.id to (it.x to it.y) }
                    // Gesture baselines are captured at the transition to their active finger count.
                    if (count == 2) gestureBaseDistance=pairDistance(pointers)
                    if (count == 5) gestureBaseDistance=spread(pointers)
                    return out
                }
                val dx=cx-lastX; val dy=cy-lastY
                val dt=(nowMs-lastAt).coerceAtLeast(1); val dist=hypot(cx-startX,cy-startY)
                if (dist > tapSlopPx) moved=true
                // Per-finger slop only for 3+ finger taps; a 2-finger pinch must not count as scroll motion.
                if (startCount >= 3 && pointers.any { sample -> fingerStart[sample.id]?.let { hypot(sample.x-it.first,sample.y-it.second) > tapSlopPx } == true }) moved=true
                when {
                    startCount >= 4 -> {
                        if (startCount == 4 && pointers.size == 4 && !gestureSent) {
                            val ddx=cx-startX; val ddy=cy-startY
                            if (maxOf(abs(ddx),abs(ddy)) >= threeFingerSwipePx) {
                                val kind=swipeKind(ddx,ddy,4)
                                out += InputEvent.Gesture(kind); gestureSent=true
                            }
                        } else if (startCount == 5 && pointers.size == 5 && !gestureSent && gestureBaseDistance > 0f) {
                            val ratio=spread(pointers)/gestureBaseDistance
                            if (ratio <= 0.75f) { out += InputEvent.Gesture(GestureKind.FiveFingerPinchIn); gestureSent=true }
                            else if (ratio >= 1.25f) { out += InputEvent.Gesture(GestureKind.FiveFingerSpread); gestureSent=true }
                        }
                    }
                    pointers.size == 3 -> {
                        val ddx=cx-startX; val ddy=cy-startY
                        if (!gestureSent && maxOf(abs(ddx),abs(ddy)) >= threeFingerSwipePx) {
                            out += InputEvent.Gesture(swipeKind(ddx,ddy,3)); gestureSent=true
                        }
                    }
                    startCount >= 3 -> Unit
                    pointers.size == 2 -> {
                        val d=pairDistance(pointers)
                        if (gestureBaseDistance > 0 && !gestureSent && abs(d/gestureBaseDistance-1f) >= PINCH_STEP) {
                            out += InputEvent.Gesture(GestureKind.PinchZoom(if (d > gestureBaseDistance) 1.15f else 0.85f)); gestureBaseDistance=d; gestureSent=false
                        } else if (moved) { out += InputEvent.Scroll(-dx,-dy); scrollVelocityX=-dx*1000f/dt; scrollVelocityY=-dy*1000f/dt }
                    }
                    dragging -> out += InputEvent.PointerDelta(dx,dy)
                    !touchStartedButtonHeld && !buttonHeld && !moved && nowMs-startAt >= TAP_MS -> { out += InputEvent.MouseButton(0u,true); dragging=true }
                    else -> if (moved) out += pointerDelta(dx,dy,dt)
                }
                lastX=cx;lastY=cy;lastAt=nowMs
            }
            Action.Up -> {
                if (dragging) { if (!touchStartedButtonHeld) out += InputEvent.MouseButton(0u,false) }
                else if (startCount == 3 && !moved && nowMs-startAt <= TAP_MS) {
                    out += InputEvent.Gesture(GestureKind.ThreeFingerTap); gestureSent=true
                } else if (startCount < 3 && !touchStartedButtonHeld && !buttonHeld && !moved && nowMs-startAt <= TAP_MS) {
                    if (startCount == 2) { out += InputEvent.MouseButton(1u,true); out += InputEvent.MouseButton(1u,false) }
                    else { out += InputEvent.Gesture(GestureKind.Tap); lastTapUpAt=nowMs }
                }
                if (startCount == 2 && moved) inertiaUntil=nowMs+INERTIA_MS
                dragging=false; count=0; startCount=0; touchStartedButtonHeld=false; fingerStart=emptyMap()
            }
            Action.Cancel -> { if (dragging && !touchStartedButtonHeld) out += InputEvent.MouseButton(0u,false); dragging=false; inertiaUntil=0; count=0; startCount=0; touchStartedButtonHeld=false; fingerStart=emptyMap() }
        }
        return out
    }
    private fun pointerDelta(dx: Float, dy: Float, dt: Long): InputEvent.PointerDelta {
        val speed=hypot(dx,dy)*1000f/dt
        val gain=(1f+gainK*speed/1000f).coerceIn(1f,4f)
        return InputEvent.PointerDelta(dx*gain,dy*gain)
    }
    private fun swipeKind(dx: Float, dy: Float, fingers: Int): GestureKind =
        if (abs(dy)>abs(dx)) {
            if (dy<0) if (fingers==3) GestureKind.Swipe3Up else GestureKind.Swipe4Up
            else if (fingers==3) GestureKind.Swipe3Down else GestureKind.Swipe4Down
        } else {
            if (dx<0) if (fingers==3) GestureKind.Swipe3Left else GestureKind.Swipe4Left
            else if (fingers==3) GestureKind.Swipe3Right else GestureKind.Swipe4Right
        }
    fun tick(nowMs: Long): List<InputEvent> {
        if (startCount >= 3) { inertiaUntil=0; return emptyList() }
        if (inertiaUntil == 0L || nowMs >= inertiaUntil) { inertiaUntil=0; return emptyList() }
        val remaining=(inertiaUntil-nowMs).toFloat()/325f
        val decay=remaining*0.12f
        return listOf(InputEvent.Scroll(scrollVelocityX*decay,scrollVelocityY*decay))
    }
    private fun pairDistance(p: List<PointerSample>) = if (p.size < 2) 0f else hypot(p[0].x-p[1].x,p[0].y-p[1].y)
    private fun spread(p: List<PointerSample>): Float {
        if (p.isEmpty()) return 0f
        val cx=centroidX(p); val cy=centroidY(p)
        return p.map { hypot(it.x-cx,it.y-cy) }.average().toFloat()
    }
    companion object { const val TAP_MS=180L; const val DOUBLE_TAP_MS=300L; const val TAP_SLOP_DP=8f; const val INERTIA_MS=325L; const val PINCH_STEP=0.15f }
    fun setButtonHeld(held: Boolean) { buttonHeld=held }
}
