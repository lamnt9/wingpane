package io.wingpane.uc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Clipboard writes use the connected accessibility service as their context. */
internal object ClipboardBridge {
    fun setFromHost(service: WingpaneAccessibilityService, text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > 64 * 1024) return
        service.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Wingpane", text))
    }

    fun readForeground(context: Context): String? {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
            ?.takeIf { it.toByteArray(Charsets.UTF_8).size <= 64 * 1024 }
    }
}
