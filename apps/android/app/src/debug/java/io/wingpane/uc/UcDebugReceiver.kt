package io.wingpane.uc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.accessibilityservice.AccessibilityService

/** adb-only spike input; declared exclusively in src/debug/AndroidManifest.xml. */
class UcDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent:Intent) {
        val service=WingpaneAccessibilityService.current() ?: return
        val input=service.injector()
        when (intent.action) {
            ACTION -> {
                if (intent.hasExtra("dx") || intent.hasExtra("dy")) input.move(intent.getFloatExtra("dx",0f),intent.getFloatExtra("dy",0f))
                if (intent.hasExtra("btn")) input.button(intent.getIntExtra("btn",0),intent.getBooleanExtra("down",false))
                if (intent.hasExtra("scroll")) input.scroll(0f,intent.getFloatExtra("scroll",0f),CursorEvent.Phase.Discrete)
                intent.getStringExtra("text")?.let(input::text)
                if (intent.hasExtra("global")) input.global(intent.getIntExtra("global",AccessibilityService.GLOBAL_ACTION_BACK))
            }
            else -> return
        }
    }
    companion object { const val ACTION="io.wingpane.debug.UC" }
}
