package io.wingpane.uc

import kotlin.math.hypot

sealed interface CursorEvent {
    val nowMs: Long
    data class Move(val x: Float, val y: Float, override val nowMs: Long): CursorEvent
    data class Button(val button: Int, val down: Boolean, val x: Float, val y: Float, override val nowMs: Long): CursorEvent
    data class Scroll(val dx: Float, val dy: Float, val phase: Phase, override val nowMs: Long): CursorEvent
    enum class Phase { Discrete, Began, Changed, Ended, Momentum }
}
sealed interface GesturePlan {
    data class Tap(val x: Float, val y: Float, val durMs: Long = 40): GesturePlan
    data class LongPress(val x: Float, val y: Float, val durMs: Long): GesturePlan
    data class StrokeStart(val id: Long, val from: CursorModel.Position, val to: CursorModel.Position, val durMs: Long = 16, val willContinue: Boolean = true): GesturePlan
    data class StrokeSegment(val id: Long, val from: CursorModel.Position, val to: CursorModel.Position, val durMs: Long = 16, val willContinue: Boolean = true): GesturePlan
    data class StrokeEnd(val id: Long, val from: CursorModel.Position, val to: CursorModel.Position, val durMs: Long = 16, val willContinue: Boolean = false): GesturePlan
}

/** Converts pointer button/scroll events into serializable touchscreen gesture segments. */
class PointerGesturePlanner(private val longPressMs: Long = 600, private val slopPx: Float = 6f) {
    private data class Press(val button: Int, val x: Float, val y: Float, val at: Long, var moved: Boolean = false, var dragging: Boolean = false)
    private var press: Press? = null
    private var strokeId = 0L
    private var lastPosition = CursorModel.Position(0f, 0f)
    private var scrollStroke: Long? = null
    fun onEvent(event: CursorEvent): List<GesturePlan> = when (event) {
        is CursorEvent.Move -> move(event)
        is CursorEvent.Button -> button(event)
        is CursorEvent.Scroll -> scroll(event)
    }
    fun cancelAll(): List<GesturePlan> {
        val p = press ?: return emptyList(); press = null
        return if (p.dragging) listOf(GesturePlan.StrokeEnd(strokeId, lastPosition, lastPosition)) else emptyList()
    }
    private fun move(e: CursorEvent.Move): List<GesturePlan> {
        val to = CursorModel.Position(e.x, e.y); val p = press ?: run { lastPosition = to; return emptyList() }
        val dist = hypot(e.x - p.x, e.y - p.y); if (dist >= slopPx) p.moved = true
        if (p.button != 0) { lastPosition = to; return emptyList() }
        if (!p.dragging && (e.nowMs - p.at >= 250 || p.moved)) {
            p.dragging = true; strokeId++
            lastPosition = to
            return listOf(GesturePlan.StrokeStart(strokeId, CursorModel.Position(p.x, p.y), to))
        }
        if (p.dragging) { val from = lastPosition; lastPosition = to; return listOf(GesturePlan.StrokeSegment(strokeId, from, to)) }
        lastPosition = to; return emptyList()
    }
    private fun button(e: CursorEvent.Button): List<GesturePlan> {
        if (e.down) { press = Press(e.button, e.x, e.y, e.nowMs); lastPosition = CursorModel.Position(e.x,e.y); return emptyList() }
        val p = press ?: return emptyList(); press = null
        val to = CursorModel.Position(e.x,e.y)
        if (p.dragging) return listOf(GesturePlan.StrokeEnd(strokeId, lastPosition, to))
        if (p.button == 1) return listOf(GesturePlan.LongPress(p.x,p.y,maxOf(longPressMs,500)))
        if (p.button != 0) return emptyList()
        if (e.nowMs - p.at > 250) {
            strokeId++
            val start=CursorModel.Position(p.x,p.y)
            return listOf(GesturePlan.StrokeStart(strokeId,start,start),GesturePlan.StrokeEnd(strokeId,start,to))
        }
        val plan = GesturePlan.Tap(p.x,p.y)
        return listOf(plan)
    }
    private fun scroll(e: CursorEvent.Scroll): List<GesturePlan> {
        if (e.phase == CursorEvent.Phase.Momentum) return emptyList()
        val from=lastPosition
        val to=CursorModel.Position(from.x+e.dx,from.y+e.dy)
        lastPosition=to
        return when (e.phase) {
            CursorEvent.Phase.Began -> { strokeId++; scrollStroke=strokeId; listOf(GesturePlan.StrokeStart(strokeId,from,to)) }
            CursorEvent.Phase.Changed -> scrollStroke?.let { listOf(GesturePlan.StrokeSegment(it,from,to)) } ?: emptyList()
            CursorEvent.Phase.Ended -> scrollStroke?.let { id -> scrollStroke=null; listOf(GesturePlan.StrokeEnd(id,from,to)) } ?: emptyList()
            CursorEvent.Phase.Discrete -> {
                val length=hypot(e.dx,e.dy); val scale=if(length<32f) 32f/maxOf(1f,length) else 1f
                val end=CursorModel.Position(from.x+e.dx*scale,from.y+e.dy*scale)
                strokeId++; listOf(GesturePlan.StrokeStart(strokeId,from,end),GesturePlan.StrokeEnd(strokeId,end,end))
            }
            CursorEvent.Phase.Momentum -> emptyList()
        }
    }
}
