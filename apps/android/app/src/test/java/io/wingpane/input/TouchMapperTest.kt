package io.wingpane.input

import io.wingpane.proto.TouchPhase
import org.junit.Assert.*
import org.junit.Test

class TouchMapperTest {
    @Test fun letterboxesLandscapeAndPortrait() {
        val landscape = TouchMapper(1920, 1080).map(TouchMapper.Action.Down, 1000, 1000, 7, 500f, 500f).single()
        assertEquals(0.5f, landscape.x, 0.0001f)
        assertEquals(0.5f, landscape.y, 0.01f)
        val portrait = TouchMapper(1080, 1920).map(TouchMapper.Action.Down, 1000, 1000, 8, 781.25f, 500f).single()
        assertEquals(1f, portrait.x, 0.0001f)
        assertEquals(0.5f, portrait.y, 0.0001f)
    }

    @Test fun clampsMovesAndIgnoresDownOutsideContent() {
        val mapper = TouchMapper(1920, 1080)
        assertTrue(mapper.map(TouchMapper.Action.Down, 1000, 1000, 1, 500f, 10f).isEmpty())
        val down = mapper.map(TouchMapper.Action.Down, 1000, 1000, 1, 500f, 500f).single()
        assertEquals(0.5f, down.x, 0.0001f)
        val move = mapper.map(TouchMapper.Action.Move, 1000, 1000, 1, 2000f, 500f).single()
        assertEquals(1f, move.x, 0.0001f)
    }

    @Test fun mapsPhasesAndKeepsHistoricalMovePoints() {
        val mapper = TouchMapper(100, 100)
        assertEquals(TouchPhase.Down, mapper.map(TouchMapper.Action.Down, 100, 100, 2, 10f, 20f).single().phase)
        val moves = mapper.map(TouchMapper.Action.Move, 100, 100, 2, 40f, 50f, listOf(TouchMapper.Point(20f, 30f), TouchMapper.Point(30f, 40f)))
        assertEquals(3, moves.size)
        assertEquals(listOf(0.2f, 0.3f, 0.4f), moves.map { it.x })
        assertTrue(moves.all { it.phase == TouchPhase.Move })
        assertEquals(TouchPhase.Up, mapper.map(TouchMapper.Action.Up, 100, 100, 2, 40f, 50f).single().phase)
        mapper.map(TouchMapper.Action.Down, 100, 100, 3, 1f, 1f)
        assertEquals(TouchPhase.Cancel, mapper.cancel().single().phase)
    }
}
