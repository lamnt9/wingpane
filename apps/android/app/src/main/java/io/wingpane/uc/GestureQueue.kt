package io.wingpane.uc

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/** Serializes dispatchGesture; Android cancels the prior gesture if dispatch overlaps. */
class GestureQueue(private val dispatch: (GestureDescription, AccessibilityService.GestureResultCallback) -> Boolean,
                   private val handler: Handler = Handler(Looper.getMainLooper()), private val timeoutMs: Long = 2_000) {
    private data class Job(val gesture: GestureDescription)
    private val pending = ArrayDeque<Job>()
    private var busy = false
    private var generation = 0
    fun enqueue(gesture: GestureDescription) { pending.addLast(Job(gesture)); pump() }
    fun clear() { pending.clear(); generation++; busy = false; pump() }
    private fun pump() {
        if (busy || pending.isEmpty()) return
        busy = true; val token = ++generation; val job = pending.removeFirst()
        val done = Runnable { if (generation == token && busy) { busy = false; pump() } }
        handler.postDelayed(done, timeoutMs)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { handler.removeCallbacks(done); done.run() }
            override fun onCancelled(gestureDescription: GestureDescription?) { handler.removeCallbacks(done); done.run() }
        }
        if (!dispatch(job.gesture, callback)) { handler.removeCallbacks(done); done.run() }
    }
}
