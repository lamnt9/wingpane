package io.wingpane.input

import io.wingpane.proto.InputEvent
import io.wingpane.proto.TouchPhase
import kotlin.math.min

/** Shared aspect-fit mapping for touch and stylus samples. */
object LetterboxMapper {
    fun mapPoint(videoWidth: Int, videoHeight: Int, viewWidth: Int, viewHeight: Int,
                 x: Float, y: Float, clamp: Boolean = true): TouchMapper.Point? {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return null
        val scale = min(viewWidth.toFloat() / videoWidth, viewHeight.toFloat() / videoHeight)
        val contentWidth = videoWidth * scale
        val contentHeight = videoHeight * scale
        val left = (viewWidth - contentWidth) / 2f
        val top = (viewHeight - contentHeight) / 2f
        if (!clamp && (x < left || x > left + contentWidth || y < top || y > top + contentHeight)) return null
        return TouchMapper.Point(((x-left)/contentWidth).coerceIn(0f,1f), ((y-top)/contentHeight).coerceIn(0f,1f))
    }
}

/** Platform independent single-pointer mapper. View space is letterboxed to the video aspect. */
class TouchMapper(private val videoWidth: Int, private val videoHeight: Int) {
    data class Point(val x: Float, val y: Float)
    enum class Action { Down, Move, Up, Cancel }

    private var activePointerId: Int? = null
    private var last = Point(0f, 0f)

    fun map(
        action: Action,
        viewWidth: Int,
        viewHeight: Int,
        pointerId: Int,
        x: Float,
        y: Float,
        historical: List<Point> = emptyList(),
    ): List<InputEvent.Touch> {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return emptyList()
        if (action == Action.Down) {
            if (activePointerId != null) return emptyList()
            val mapped = mapPoint(viewWidth, viewHeight, x, y, false) ?: return emptyList()
            activePointerId = pointerId
            last = mapped
            return listOf(event(pointerId, mapped, TouchPhase.Down))
        }
        if (action == Action.Cancel) {
            val active = activePointerId ?: return emptyList()
            activePointerId = null
            return listOf(event(active, last, TouchPhase.Cancel))
        }
        if (pointerId != activePointerId) return emptyList()
        val phase = if (action == Action.Up) TouchPhase.Up else TouchPhase.Move
        val result = ArrayList<InputEvent.Touch>(historical.size + 1)
        if (action == Action.Move) {
            historical.forEach { point ->
                val mapped = mapPoint(viewWidth, viewHeight, point.x, point.y, true) ?: return@forEach
                last = mapped
                result += event(pointerId, mapped, TouchPhase.Move)
            }
        }
        mapPoint(viewWidth, viewHeight, x, y, true)?.let { mapped ->
            last = mapped
            result += event(pointerId, mapped, phase)
        }
        if (action == Action.Up) activePointerId = null
        return result
    }

    fun cancel(): List<InputEvent.Touch> {
        val active = activePointerId ?: return emptyList()
        activePointerId = null
        return listOf(event(active, last, TouchPhase.Cancel))
    }

    fun isDown(): Boolean = activePointerId != null
    fun activePointerId(): Int? = activePointerId

    private fun event(id: Int, point: Point, phase: TouchPhase) =
        InputEvent.Touch(id.toUByte(), point.x, point.y, phase)

    private fun mapPoint(viewWidth: Int, viewHeight: Int, x: Float, y: Float, clamp: Boolean): Point? =
        LetterboxMapper.mapPoint(videoWidth, videoHeight, viewWidth, viewHeight, x, y, clamp)
}
