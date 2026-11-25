package io.wingpane.input

/** Pure palm rejection rules; dimensions and tool types are supplied by the Android caller. */
class PalmRejectionFilter(private var level: Level = Level.Standard) {
    enum class Level { Off, Standard, Strict }
    enum class Tool { Finger, Stylus, Eraser, Palm, Other }
    data class PointerSample(val id: Int, val toolType: Tool, val x: Float, val y: Float,
                             val touchMajorPx: Float, val canceled: Boolean, val timeNs: Long)
    data class State(val stylusActive: Boolean = false, val lastStylusNs: Long = Long.MIN_VALUE, val cancelSentForContact: Boolean = false)
    data class Result(val accepted: List<PointerSample>, val cancelActiveTouch: Boolean, val nextState: State)

    private var state = State()

    fun setLevel(value: Level) { level = value }
    fun currentLevel(): Level = level

    fun noteStylus(active: Boolean, timeNs: Long) {
        state = State(active, timeNs, if (active) state.cancelSentForContact else false)
    }

    fun newTouchContact() { state = state.copy(cancelSentForContact = false) }

    fun filter(samples: List<PointerSample>, nowNs: Long, viewWidthPx: Float, viewHeightPx: Float,
               density: Float, pixelsPerMm: Float, activeTouchId: Int? = null): Result {
        return evaluate(level, state, samples, nowNs, viewWidthPx, viewHeightPx, density, pixelsPerMm, activeTouchId)
            .also { state = it.nextState }
    }

    companion object {
        const val QUIET_NS = 300_000_000L
        const val STRICT_EDGE_DP = 16f

        /** Stateless decision function used by the Android adapter and unit tests. */
        fun evaluate(level: Level, state: State, samples: List<PointerSample>, nowNs: Long,
                     viewWidthPx: Float, viewHeightPx: Float, density: Float, pixelsPerMm: Float,
                     activeTouchId: Int? = null): Result {
            if (level == Level.Off) return Result(samples, false, state)
            val rejectingFingers = state.stylusActive || (state.lastStylusNs != Long.MIN_VALUE &&
                nowNs >= state.lastStylusNs && nowNs - state.lastStylusNs <= QUIET_NS)
            var cancelSent = state.cancelSentForContact
            var mustCancel = false
            val accepted = samples.filter { p ->
                val markedPalm = p.toolType == Tool.Palm || p.canceled ||
                    (pixelsPerMm > 0f && p.touchMajorPx > 30f * pixelsPerMm)
                if (markedPalm) {
                    if (activeTouchId != null && !cancelSent) { mustCancel = true; cancelSent = true }
                    false
                } else if (p.toolType == Tool.Finger && rejectingFingers) false
                else if (level == Level.Strict && p.toolType == Tool.Finger && density > 0f) {
                    val edge = STRICT_EDGE_DP * density
                    p.x >= edge && p.x <= viewWidthPx - edge && p.y >= edge && p.y <= viewHeightPx - edge
                } else true
            }
            return Result(accepted, mustCancel, state.copy(cancelSentForContact = cancelSent))
        }
    }
}
