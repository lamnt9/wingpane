package io.wingpane.uc

import kotlin.math.abs

/** Tablet-owned pointer coordinates. All dimensions and deltas are in physical pixels. */
class CursorModel(width: Int, height: Int, var gain: Float = 1f, var entryEdge: Edge = Edge.Left) {
    enum class Edge { Left, Right, Top, Bottom }
    data class Position(val x: Float, val y: Float)
    data class Exit(val along: Float)

    var width: Int = width.coerceAtLeast(1); private set
    var height: Int = height.coerceAtLeast(1); private set
    var x: Float = (width - 1).coerceAtLeast(0) / 2f; private set
    var y: Float = (height - 1).coerceAtLeast(0) / 2f; private set
    private var pushStart = Long.MIN_VALUE
    private var pushDistance = 0f

    fun resize(width: Int, height: Int) {
        val oldW = this.width; val oldH = this.height
        x = if (oldW > 1) x / (oldW - 1) * (width - 1).coerceAtLeast(0) else 0f
        y = if (oldH > 1) y / (oldH - 1) * (height - 1).coerceAtLeast(0) else 0f
        this.width = width.coerceAtLeast(1); this.height = height.coerceAtLeast(1); clamp()
    }

    fun position() = Position(x, y)

    fun move(dx: Float, dy: Float, nowMs: Long, density: Float = 1f): Exit? {
        x += dx * gain; y += dy * gain; clamp()
        val edgeDistance = when (entryEdge) {
            Edge.Left -> x
            Edge.Right -> width - 1f - x
            Edge.Top -> y
            Edge.Bottom -> height - 1f - y
        }
        val outward = when (entryEdge) {
            Edge.Left -> dx < 0; Edge.Right -> dx > 0; Edge.Top -> dy < 0; Edge.Bottom -> dy > 0
        }
        if (edgeDistance > 0f || !outward) { pushStart = Long.MIN_VALUE; pushDistance = 0f; return null }
        if (pushStart == Long.MIN_VALUE || nowMs - pushStart > 300) { pushStart = nowMs; pushDistance = 0f }
        pushDistance += when (entryEdge) { Edge.Left, Edge.Right -> abs(dx * gain); else -> abs(dy * gain) }
        if (pushDistance < 24f * density) return null
        pushStart = Long.MIN_VALUE; pushDistance = 0f
        return Exit(along())
    }

    fun along(): Float = when (entryEdge) {
        Edge.Left, Edge.Right -> (y / (height - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
        Edge.Top, Edge.Bottom -> (x / (width - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    fun setAlong(along: Float) {
        val a = along.coerceIn(0f, 1f)
        when (entryEdge) {
            Edge.Left -> { x = 0f; y = a * (height - 1).coerceAtLeast(0) }
            Edge.Right -> { x = width - 1f; y = a * (height - 1).coerceAtLeast(0) }
            Edge.Top -> { x = a * (width - 1).coerceAtLeast(0); y = 0f }
            Edge.Bottom -> { x = a * (width - 1).coerceAtLeast(0); y = height - 1f }
        }
    }

    private fun clamp() { x = x.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat()); y = y.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat()) }
}
