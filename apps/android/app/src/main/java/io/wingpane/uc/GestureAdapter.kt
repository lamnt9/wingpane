package io.wingpane.uc

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.ViewConfiguration

/** Maps planner output to single-finger AccessibilityService gestures. */
class GestureAdapter(private val queue: GestureQueue) {
    private val strokes = mutableMapOf<Long, GestureDescription.StrokeDescription>()
    fun submit(plan: GesturePlan) {
        when (plan) {
            is GesturePlan.Tap -> dispatch(path(plan.x,plan.y,plan.x,plan.y), plan.durMs)
            is GesturePlan.LongPress -> dispatch(path(plan.x,plan.y,plan.x,plan.y), maxOf(plan.durMs, ViewConfiguration.getLongPressTimeout()+100L))
            is GesturePlan.StrokeStart -> {
                val p = path(plan.from.x,plan.from.y,plan.to.x,plan.to.y)
                val stroke = GestureDescription.StrokeDescription(p,0,plan.durMs,plan.willContinue); strokes[plan.id] = stroke; queue.enqueue(GestureDescription.Builder().addStroke(stroke).build())
            }
            is GesturePlan.StrokeSegment -> continueStroke(plan.id,plan.from,plan.to,plan.durMs,plan.willContinue)
            is GesturePlan.StrokeEnd -> continueStroke(plan.id,plan.from,plan.to,plan.durMs,false)
        }
    }
    private fun continueStroke(id: Long, from: CursorModel.Position, to: CursorModel.Position, duration: Long, more: Boolean) {
        val previous = strokes[id] ?: return
        val next = previous.continueStroke(path(from.x,from.y,to.x,to.y),0,duration,more)
        if (more) strokes[id] = next else strokes.remove(id)
        queue.enqueue(GestureDescription.Builder().addStroke(next).build())
    }
    private fun dispatch(path: Path, duration: Long) = queue.enqueue(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,duration)).build())
    private fun path(x: Float,y: Float,x2: Float,y2: Float) = Path().apply { moveTo(x,y); lineTo(x2,y2) }
}
