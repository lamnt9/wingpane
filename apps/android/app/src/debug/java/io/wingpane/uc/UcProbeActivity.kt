package io.wingpane.uc

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.view.Gravity
import android.widget.TextView

/** On-device target for measuring dispatch-to-ACTION_DOWN latency. */
class UcProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(TextView(this).apply { text="Wingpane UC latency probe\nTap anywhere"; textSize=22f; gravity=Gravity.CENTER }) }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) UcLatencyProbe.touchDown(event.eventTime)
        return super.dispatchTouchEvent(event)
    }
}
