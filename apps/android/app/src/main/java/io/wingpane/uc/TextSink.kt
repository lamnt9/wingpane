package io.wingpane.uc

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo

interface TextSink {
    fun edit(edit: Edit): Boolean
    fun key(keycode: Int, meta: Int = 0): Boolean
    fun context(action: Context): Boolean
    fun deleteWord(): Boolean
}

// Every call goes through `connection`, which is null below API 33.
@SuppressLint("NewApi")
class ImeTextSink(private val service: AccessibilityService) : TextSink {
    private val connection get() = if (Build.VERSION.SDK_INT >= 33) service.inputMethod?.currentInputConnection else null
    override fun edit(edit: Edit): Boolean {
        val c = connection ?: return false
        if (edit.deleteBefore > 0) c.deleteSurroundingText(edit.deleteBefore, 0)
        if (edit.insert.isNotEmpty()) c.commitText(edit.insert, 1, null)
        return true
    }
    override fun key(keycode: Int, meta: Int): Boolean {
        val c = connection ?: return false
        c.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keycode, 0, meta))
        c.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, keycode, 0, meta))
        return true
    }
    override fun context(action: Context): Boolean {
        val c = connection ?: return false
        c.performContextMenuAction(when (action) {
            Context.COPY -> android.R.id.copy
            Context.CUT -> android.R.id.cut
            Context.PASTE -> android.R.id.paste
            Context.SELECT_ALL -> android.R.id.selectAll
        })
        return true
    }
    override fun deleteWord(): Boolean {
        val c = connection ?: return false
        c.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0, KeyEvent.META_CTRL_ON))
        c.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0, KeyEvent.META_CTRL_ON))
        return true
    }
}

class SetTextFallbackSink(private val service: AccessibilityService) : TextSink {
    private fun node(): AccessibilityNodeInfo? = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    override fun edit(edit: Edit): Boolean {
        val n = node() ?: return false
        try {
            val old = n.text?.toString() ?: ""
            val selectionStart = n.textSelectionStart.takeIf { it >= 0 } ?: old.length
            val selectionEnd = n.textSelectionEnd.takeIf { it >= selectionStart } ?: selectionStart
            val start = (selectionStart - edit.deleteBefore).coerceAtLeast(0)
            val updated = old.substring(0, start) + edit.insert + old.substring(selectionEnd)
            val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
            })
            if (ok) n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start + edit.insert.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, start + edit.insert.length)
            })
            return ok
        } finally { n.recycle() }
    }
    override fun key(keycode: Int, meta: Int): Boolean {
        return when (keycode) {
        KeyEvent.KEYCODE_DEL -> edit(Edit(1, ""))
        KeyEvent.KEYCODE_ENTER -> edit(Edit(0, "\n"))
        KeyEvent.KEYCODE_TAB -> edit(Edit(0, "\t"))
        KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
            val n = node() ?: return false
            try {
                val text = n.text?.toString() ?: ""
                val at = n.textSelectionStart.takeIf { it >= 0 } ?: text.length
                val target = when (keycode) {
                    KeyEvent.KEYCODE_MOVE_HOME -> 0
                    KeyEvent.KEYCODE_MOVE_END -> text.length
                    KeyEvent.KEYCODE_DPAD_LEFT -> (at - 1).coerceAtLeast(0)
                    else -> (at + 1).coerceAtMost(text.length)
                }
                n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, target)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, target)
                })
            } finally { n.recycle() }
        }
        else -> false
        }
    }
    override fun context(action: Context): Boolean {
        val n = node() ?: return false
        try {
            if (action == Context.SELECT_ALL) return n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, n.text?.length ?: 0)
            })
            return n.performAction(when (action) {
                Context.COPY -> AccessibilityNodeInfo.ACTION_COPY
                Context.CUT -> AccessibilityNodeInfo.ACTION_CUT
                Context.PASTE -> AccessibilityNodeInfo.ACTION_PASTE
                Context.SELECT_ALL -> AccessibilityNodeInfo.ACTION_SELECT
            })
        } finally { n.recycle() }
    }
    override fun deleteWord(): Boolean {
        val n = node() ?: return false
        try {
            val value = n.text?.toString() ?: return false
            val caret = n.textSelectionStart.takeIf { it >= 0 } ?: value.length
            var start = caret
            while (start > 0 && value[start - 1].isWhitespace()) start--
            while (start > 0 && !value[start - 1].isWhitespace()) start--
            return edit(Edit(caret - start, ""))
        } finally { n.recycle() }
    }
}
