package io.wingpane.input

import io.wingpane.proto.InputEvent
import org.junit.Assert.*
import org.junit.Test

class StylusMapperTest {
    private fun sample(action: StylusMapper.Action = StylusMapper.Action.Move, theta: Float = 0f, phi: Float = 0f,
                       eraser: Boolean = false, time: Long = 1_000_000L) = StylusMapper.Sample(
        500f, 500f, .7f, theta, phi, StylusMapper.BUTTON_PRIMARY, action,
        action == StylusMapper.Action.Hover, time, eraser)

    @Test fun mapsLetterboxedPointAndPressureAndHover() {
        val pen = StylusMapper.map(sample(), 1000, 500, 1000, 1000)!!
        assertEquals(.5f, pen.x, .001f)
        assertEquals(.5f, pen.y, .001f)
        assertEquals(.7f, pen.pressure, .001f)
        assertTrue(pen.isDown)
        val hover = StylusMapper.map(sample(StylusMapper.Action.Hover), 1000, 500, 1000, 1000)!!
        assertTrue(hover.isHover)
        assertFalse(hover.isDown)
        assertEquals(0f, hover.pressure, 0f)
    }

    @Test fun tiltSignsEraserAndTimestampAreMapped() {
        val t = Math.PI.toFloat() / 4
        val north = StylusMapper.map(sample(theta = t, phi = 0f), 1000, 1000, 1000, 1000)!!
        assertEquals(0, north.tiltX.toInt())
        assertTrue(north.tiltY > 0)
        val east = StylusMapper.map(sample(theta = t, phi = (Math.PI / 2).toFloat(), eraser = true, time = 15_000_000L), 1000, 1000, 1000, 1000)!!
        assertTrue(east.tiltX > 0)
        assertEquals(0, east.tiltY.toInt())
        assertEquals(5.toUByte(), east.buttonMask)
        assertEquals(15_000uL, east.timestampUs) // 15_000_000 ns = 15_000 us
        val south = StylusMapper.map(sample(theta = t, phi = Math.PI.toFloat()), 1000, 1000, 1000, 1000)!!
        assertEquals(0, south.tiltX.toInt())
        assertTrue(south.tiltY < 0)
        val west = StylusMapper.map(sample(theta = t, phi = (-Math.PI / 2).toFloat()), 1000, 1000, 1000, 1000)!!
        assertTrue(west.tiltX < 0)
        assertEquals(0, west.tiltY.toInt())
    }

    @Test fun batchPreservesHistoricalOrderAndExitEncoding() {
        val batch = StylusMapper.mapBatch(listOf(sample(time = 1_000_000L), sample(time = 2_000_000L)), 1000, 1000, 1000, 1000)
        assertEquals(listOf(1_000uL, 2_000uL), batch.map { it.timestampUs })
        val exit = StylusMapper.map(sample(StylusMapper.Action.Exit), 1000, 1000, 1000, 1000)!!
        assertFalse(exit.isDown)
        assertFalse(exit.isHover)
    }
}
