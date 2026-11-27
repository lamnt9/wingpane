package io.wingpane.uc

import android.app.Activity
import io.wingpane.WingpaneApplication
import io.wingpane.proto.ControlMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A focused foreground window is required to read clipboard on Android 10+. */
class ClipboardSendActivity : Activity() {
    private var handled = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val client = (application as WingpaneApplication).clientHolder.client
        val text = ClipboardBridge.readForeground(this)
        if (text != null && client.supportsClipboardText()) {
            CoroutineScope(Dispatchers.IO).launch {
                try { client.sendControl(ControlMessage.Clipboard(text)) }
                catch (_: Exception) { /* The connection may close while the activity has focus. */ }
                finally { runOnUiThread { finish() } }
            }
        } else finish()
    }
}
