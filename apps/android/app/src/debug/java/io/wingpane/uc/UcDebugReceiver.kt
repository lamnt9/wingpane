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
                if (intent.hasExtra("btn")) { router.boundary(); input.button(intent.getIntExtra("btn",0),intent.getBooleanExtra("down",false)) }
                if (intent.hasExtra("scroll")) input.scroll(0f,intent.getFloatExtra("scroll",0f),CursorEvent.Phase.Discrete)
                intent.getStringExtra("text")?.let(input::text)
                if (intent.hasExtra("global")) input.global(intent.getIntExtra("global",AccessibilityService.GLOBAL_ACTION_BACK))
                if (intent.hasExtra("hid")) {
                    val ime = ImeTextSink(service)
                    val fallback = SetTextFallbackSink(service)
                    router.onKey(intent.getIntExtra("hid", 0), intent.getIntExtra("mods", 0),
                        intent.getBooleanExtra("down", true), service.editorFocused).forEach { action ->
                        when (action) {
                            is KeyAction.Commit -> if (!ime.edit(action.edit)) fallback.edit(action.edit)
                            is KeyAction.SendKey -> if (!ime.key(action.keycode, action.meta)) fallback.key(action.keycode, action.meta)
                            is KeyAction.Context -> if (!ime.context(action.action)) fallback.context(action.action)
                            is KeyAction.Global -> input.global(when (action.action) {
                                Global.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                                Global.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
                                Global.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
                                Global.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                                Global.TAKE_SCREENSHOT -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                                Global.LOCK_SCREEN -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                            })
                            KeyAction.DeleteWord -> if (!ime.deleteWord()) fallback.deleteWord()
                            KeyAction.ToggleVi -> Unit
                        }
                    }
                }
            }
            else -> return
        }
    }
    companion object {
        const val ACTION="io.wingpane.debug.UC"
        private val router = KeyRouter()
    }
}
