package io.wingpane.uc

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class ScrollPlannerTest {
    private val cursor = CursorModel.Position(100f, 200f)
    private fun scroll(dx: Float, dy: Float, phase: CursorEvent.Phase, at: Long = 0L) =
        CursorEvent.Scroll(dx, dy, phase, at)

    @Test fun fingerScrollIsOneContinuousStrokeWithStationaryEnd() {
        val planner = ScrollPlanner(8f)
        val plans = planner.onScroll(scroll(0f, 0f, CursorEvent.Phase.Began), cursor) +
            planner.onScroll(scroll(0f, 12f, CursorEvent.Phase.Changed), cursor) +
            planner.onScroll(scroll(3f, 4f, CursorEvent.Phase.Ended), cursor)
        assertEquals(4, plans.size)
        val start = plans[0] as GesturePlan.StrokeStart
        val first = plans[1] as GesturePlan.StrokeSegment
        val second = plans[2] as GesturePlan.StrokeSegment
        val end = plans[3] as GesturePlan.StrokeEnd
        assertEquals(start.id, first.id)
        assertEquals(start.id, second.id)
        assertEquals(start.id, end.id)
        assertEquals(start.to, first.from)
        assertEquals(first.to, second.from)
        assertEquals(second.to, end.from)
        assertEquals(end.from, end.to)
        assertEquals(32L, end.durMs)
        assertTrue(start.willContinue && first.willContinue && second.willContinue)
        assertFalse(end.willContinue)
    }

    @Test fun momentumIsDroppedEvenDuringAStroke() {
        val planner = ScrollPlanner(8f)
        planner.onScroll(scroll(0f, 0f, CursorEvent.Phase.Began), cursor)
        assertTrue(planner.onScroll(scroll(0f, 100f, CursorEvent.Phase.Momentum), cursor).isEmpty())
        val end = planner.onScroll(scroll(0f, 0f, CursorEvent.Phase.Ended), cursor).single() as GesturePlan.StrokeEnd
        assertEquals(cursor, end.from)
    }

    @Test fun smallWheelDeltasCoalesceIntoOneSwipeOfAtLeastTwiceSlop() {
        val planner = ScrollPlanner(8f)
        assertTrue(planner.onScroll(scroll(0f, 2f, CursorEvent.Phase.Discrete), cursor).isEmpty())
        assertTrue(planner.onScroll(scroll(0f, 3f, CursorEvent.Phase.Discrete, 20), cursor).isEmpty())
        assertTrue(planner.flushWheel(cursor, 59).isEmpty())
        val plans = planner.flushWheel(cursor, 60)
        assertEquals(2, plans.size)
        val start = plans[0] as GesturePlan.StrokeStart
        val end = plans[1] as GesturePlan.StrokeEnd
        assertEquals(16f, hypot(start.to.x - start.from.x, start.to.y - start.from.y), 0.001f)
        assertEquals(start.id, end.id)
        assertEquals(start.to, end.from)
        assertEquals(end.from, end.to)
        assertEquals(16L, end.durMs)
        assertTrue(planner.flushWheel(cursor, 100).isEmpty())
    }

    @Test fun invertReversesFingerAndWheelDirection() {
        val planner = ScrollPlanner(8f)
        planner.onScroll(scroll(0f, 0f, CursorEvent.Phase.Began), cursor)
        val finger = planner.onScroll(scroll(4f, 10f, CursorEvent.Phase.Changed), cursor, invert = true)
            .single() as GesturePlan.StrokeSegment
        assertEquals(CursorModel.Position(96f, 190f), finger.to)
        planner.onScroll(scroll(0f, 0f, CursorEvent.Phase.Ended), cursor)
        planner.onScroll(scroll(0f, 5f, CursorEvent.Phase.Discrete), cursor, invert = true)
        val wheel = planner.flushWheel(cursor, 40).first() as GesturePlan.StrokeStart
        assertTrue(wheel.to.y < cursor.y)
    }

    @Test fun cancelEndsAnActiveFingerStroke() {
        val planner = ScrollPlanner(8f)
        val start = planner.onScroll(scroll(0f, 0f, CursorEvent.Phase.Began), cursor).first() as GesturePlan.StrokeStart
        planner.onScroll(scroll(0f, 12f, CursorEvent.Phase.Changed), cursor)
        val end = planner.cancel().single() as GesturePlan.StrokeEnd
        assertEquals(start.id, end.id)
        assertEquals(end.from, end.to)
        assertTrue(planner.cancel().isEmpty())
        assertTrue(planner.onScroll(scroll(0f, 5f, CursorEvent.Phase.Changed), cursor).isEmpty())
    }
}
