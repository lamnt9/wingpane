package io.wingpane.input

import org.junit.Assert.*
import org.junit.Test

class PalmRejectionFilterTest {
    private fun finger(id: Int = 1, x: Float = 100f, y: Float = 100f, major: Float = 10f, canceled: Boolean = false) =
        PalmRejectionFilter.PointerSample(id, PalmRejectionFilter.Tool.Finger, x, y, major, canceled, 0)

    @Test fun fingersAreDroppedDuringStylusAndAcceptedAfterQuietWindow() {
        val filter = PalmRejectionFilter()
        filter.noteStylus(true, 1_000_000_000L)
        assertTrue(filter.filter(listOf(finger()), 1_100_000_000L, 500f, 500f, 1f, 1f).accepted.isEmpty())
        filter.noteStylus(false, 1_200_000_000L)
        assertTrue(filter.filter(listOf(finger()), 1_400_000_000L, 500f, 500f, 1f, 1f).accepted.isEmpty())
        assertEquals(1, filter.filter(listOf(finger()), 1_501_000_000L, 500f, 500f, 1f, 1f).accepted.size)
    }

    @Test fun palmFlagAndLargeContactCancelActiveTouchOnlyOnce() {
        val filter = PalmRejectionFilter()
        val palm = finger(major = 31f)
        assertTrue(filter.filter(listOf(palm), 10, 500f, 500f, 1f, 1f, activeTouchId = 1).cancelActiveTouch)
        assertFalse(filter.filter(listOf(palm), 11, 500f, 500f, 1f, 1f, activeTouchId = 1).cancelActiveTouch)
        val canceled = finger(canceled = true)
        val decision = PalmRejectionFilter.evaluate(PalmRejectionFilter.Level.Standard, PalmRejectionFilter.State(),
            listOf(canceled), 12, 500f, 500f, 1f, 1f, activeTouchId = 9)
        assertTrue(decision.accepted.isEmpty())
        assertTrue(decision.cancelActiveTouch)
    }

    @Test fun strictRejectsEdgeFingerAndOffLeavesSamplesUntouched() {
        val edge = finger(x = 10f)
        val filter = PalmRejectionFilter(PalmRejectionFilter.Level.Strict)
        assertTrue(filter.filter(listOf(edge), 1, 500f, 500f, 1f, 1f).accepted.isEmpty())
        filter.setLevel(PalmRejectionFilter.Level.Off)
        assertEquals(listOf(edge), filter.filter(listOf(edge), 1, 500f, 500f, 1f, 1f).accepted)
    }
}
