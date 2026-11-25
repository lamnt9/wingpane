package io.wingpane.input

import io.wingpane.proto.InputEvent
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Android independent conversion of raw stylus samples into normalized wire samples. */
object StylusMapper {
    enum class Action { Down, Move, Up, Cancel, Hover, Exit }
    data class Sample(
        val x: Float, val y: Float, val pressure: Float, val tiltRad: Float,
        val orientationRad: Float, val buttonState: Int, val action: Action,
        val isHover: Boolean, val eventTimeNs: Long, val eraser: Boolean = false,
    )

    fun map(sample: Sample, videoWidth: Int, videoHeight: Int, viewWidth: Int, viewHeight: Int): InputEvent.Pen? {
        val point = LetterboxMapper.mapPoint(videoWidth, videoHeight, viewWidth, viewHeight, sample.x, sample.y)
            ?: return null
        val theta = sample.tiltRad.takeIf { it.isFinite() }?.coerceIn(0f, Math.PI.toFloat() / 2f) ?: 0f
        val phi = sample.orientationRad.takeIf { it.isFinite() } ?: 0f
        val tx = Math.toDegrees(atan((tan(theta) * sin(phi)).toDouble())).toFloat().coerceIn(-90f, 90f)
        val ty = Math.toDegrees(atan((tan(theta) * cos(phi)).toDouble())).toFloat().coerceIn(-90f, 90f)
        val down = sample.action == Action.Down || sample.action == Action.Move && !sample.isHover
        val hover = sample.isHover && sample.action != Action.Exit
        val pressure = if (down && !sample.isHover && sample.pressure.isFinite()) sample.pressure.coerceIn(0f, 1f) else 0f
        var buttons = 0
        if (sample.buttonState and BUTTON_PRIMARY != 0) buttons = buttons or 1
        if (sample.buttonState and BUTTON_SECONDARY != 0) buttons = buttons or 2
        if (sample.eraser) buttons = buttons or 4
        return InputEvent.Pen(point.x, point.y, pressure, tx.toInt().toByte(), ty.toInt().toByte(),
            down, hover, buttons.toUByte(), (sample.eventTimeNs.coerceAtLeast(0) / 1_000).toULong())
    }

    fun mapBatch(samples: List<Sample>, videoWidth: Int, videoHeight: Int, viewWidth: Int, viewHeight: Int): List<InputEvent.Pen> =
        samples.mapNotNull { map(it, videoWidth, videoHeight, viewWidth, viewHeight) }

    const val BUTTON_PRIMARY = 32
    const val BUTTON_SECONDARY = 64
}
