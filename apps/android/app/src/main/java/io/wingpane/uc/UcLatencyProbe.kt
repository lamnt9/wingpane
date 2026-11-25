package io.wingpane.uc

import android.os.SystemClock
import android.util.Log

/** Debug-only timing helper used by the standalone probe screen/receiver. */
object UcLatencyProbe {
    private const val TAG="WingpaneUcProbe"
    private var dispatchedAt=0L
    fun dispatched() { dispatchedAt=SystemClock.uptimeMillis(); Log.i(TAG,"dispatch=$dispatchedAt") }
    fun completed() { val now=SystemClock.uptimeMillis(); Log.i(TAG,"completed=$now elapsed=${now-dispatchedAt}ms") }
    fun touchDown(eventTime:Long) { Log.i(TAG,"ACTION_DOWN eventTime=$eventTime dispatchToDown=${eventTime-dispatchedAt}ms") }
}
