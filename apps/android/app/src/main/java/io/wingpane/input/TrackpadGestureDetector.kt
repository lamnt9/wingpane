package io.wingpane.input

import io.wingpane.proto.InputEvent
import kotlin.math.hypot
import kotlin.math.min

/** Pure pointer state machine. Coordinates and scroll deltas are pixels; callers supply monotonic ms. */
class TrackpadGestureDetector(private val gainK: Float = 0.8f, private val tapSlopPx: Float = 8f) {
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
    private var scrollVelocityX = 0f; private var scrollVelocityY = 0f
    private var inertiaUntil = 0L

    private fun centroidX(p: List<PointerSample>) = p.map { it.x }.average().toFloat()
    private fun centroidY(p: List<PointerSample>) = p.map { it.y }.average().toFloat()

    fun onTouch(action: Action, pointers: List<PointerSample>, nowMs: Long): List<InputEvent> {
        val out = mutableListOf<InputEvent>()
        when (action) {
            Action.Down -> if (pointers.isNotEmpty()) {
                startAt=nowMs; lastAt=nowMs; count=pointers.size; startCount=pointers.size
                startX=centroidX(pointers); startY=centroidY(pointers); lastX=startX; lastY=startY
                moved=false; dragging=false; inertiaUntil=0
            }
            Action.Move -> if (pointers.isNotEmpty()) {
                val cx=centroidX(pointers); val cy=centroidY(pointers)
                if (pointers.size != count) {
                    // A finger landed or lifted: the centroid jumps, which is not finger motion.
                    count=pointers.size; startCount=maxOf(startCount,count)
                    startX=cx; startY=cy; lastX=cx; lastY=cy; lastAt=nowMs
                    return out
                }
                val dx=cx-lastX; val dy=cy-lastY
                val dt=(nowMs-lastAt).coerceAtLeast(1); val dist=hypot(cx-startX,cy-startY)
                if (dist > tapSlopPx) moved=true
                when {
                    pointers.size >= 2 -> if (moved) {
                        out += InputEvent.Scroll(-dx,-dy)
                        scrollVelocityX = -dx*1000f/dt; scrollVelocityY = -dy*1000f/dt
                    }
                    dragging -> out += InputEvent.PointerDelta(dx,dy)
                    !moved && nowMs-startAt >= TAP_MS -> { out += InputEvent.MouseButton(0u,true); dragging=true }
                    else -> {
                        val speed=hypot(dx,dy)*1000f/dt
                        val gain=(1f+gainK*speed/1000f).coerceIn(1f,4f)
                        if (moved) out += InputEvent.PointerDelta(dx*gain,dy*gain)
                    }
                }
                lastX=cx;lastY=cy;lastAt=nowMs
            }
            Action.Up -> {
                if (dragging) out += InputEvent.MouseButton(0u,false)
                else if (!moved && nowMs-startAt <= TAP_MS) {
                    if (startCount >= 2) { out += InputEvent.MouseButton(1u,true); out += InputEvent.MouseButton(1u,false) }
                    else out += InputEvent.Gesture(io.wingpane.proto.GestureKind.Tap)
                }
                if (startCount >= 2 && moved) inertiaUntil=nowMs+INERTIA_MS
                dragging=false; count=0
            }
            Action.Cancel -> { if (dragging) out += InputEvent.MouseButton(0u,false); dragging=false; inertiaUntil=0; count=0 }
        }
        return out
    }
    fun tick(nowMs: Long): List<InputEvent> {
        if (inertiaUntil == 0L || nowMs >= inertiaUntil) { inertiaUntil=0; return emptyList() }
        val remaining=(inertiaUntil-nowMs).toFloat()/325f
        val decay=remaining*0.12f
        return listOf(InputEvent.Scroll(scrollVelocityX*decay,scrollVelocityY*decay))
    }
    companion object { const val TAP_MS=180L; const val TAP_SLOP_DP=8f; const val INERTIA_MS=325L }
}
