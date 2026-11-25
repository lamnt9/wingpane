package io.wingpane.uc

import kotlin.math.hypot

/** Plans one continuous finger stroke per trackpad gesture and one swipe per wheel burst. */
class ScrollPlanner(private val touchSlopPx: Float) {
    private var nextId = 0L
    private var activeId: Long? = null
    private var position = CursorModel.Position(0f, 0f)
    private var wheelDx = 0f
    private var wheelDy = 0f
    private var lastWheelAtMs: Long? = null

    fun onScroll(
        event: CursorEvent.Scroll,
        cursor: CursorModel.Position,
        invert: Boolean = false,
    ): List<GesturePlan> {
        if (event.phase == CursorEvent.Phase.Momentum) return emptyList()
        val direction = if (invert) -1f else 1f
        val dx = event.dx * direction
        val dy = event.dy * direction
        if (event.phase == CursorEvent.Phase.Discrete) {
            wheelDx += dx
            wheelDy += dy
            lastWheelAtMs = event.nowMs
            return emptyList()
        }
        return when (event.phase) {
            CursorEvent.Phase.Began -> {
                val old = activeId
                val end = if (old == null) emptyList() else listOf(GesturePlan.StrokeEnd(old, position, position, 32))
                position = cursor
                val id = --nextId
                activeId = id
                val start = GesturePlan.StrokeStart(id, cursor, cursor)
                end + start + move(dx, dy)
            }
            CursorEvent.Phase.Changed -> move(dx, dy)
            CursorEvent.Phase.Ended -> {
                val movement = move(dx, dy)
                val id = activeId ?: return emptyList()
                activeId = null
                movement + GesturePlan.StrokeEnd(id, position, position, 32)
            }
            else -> emptyList()
        }
    }

    /** Emits one wheel swipe once the 40 ms aggregation window has passed. */
    fun flushWheel(cursor: CursorModel.Position, nowMs: Long): List<GesturePlan> {
        val last = lastWheelAtMs ?: return emptyList()
        if (nowMs - last < 40L) return emptyList()
        val dx = wheelDx
        val dy = wheelDy
        wheelDx = 0f
        wheelDy = 0f
        lastWheelAtMs = null
        val length = hypot(dx, dy)
        if (length <= 0f) return emptyList()
        val scale = maxOf(1f, 2f * touchSlopPx / length)
        val end = CursorModel.Position(cursor.x + dx * scale, cursor.y + dy * scale)
        val id = --nextId
        return listOf(
            GesturePlan.StrokeStart(id, cursor, end),
            GesturePlan.StrokeEnd(id, end, end, 16),
        )
    }

    /** Drops pending wheel deltas; an in-progress finger stroke is ended so it cannot stay pressed. */
    fun cancel(): List<GesturePlan> {
        val id = activeId
        activeId = null
        wheelDx = 0f
        wheelDy = 0f
        lastWheelAtMs = null
        return if (id == null) emptyList() else listOf(GesturePlan.StrokeEnd(id, position, position, 16))
    }

    private fun move(dx: Float, dy: Float): List<GesturePlan> {
        val id = activeId ?: return emptyList()
        if (dx == 0f && dy == 0f) return emptyList()
        val from = position
        position = CursorModel.Position(from.x + dx, from.y + dy)
        return listOf(GesturePlan.StrokeSegment(id, from, position))
    }
}
