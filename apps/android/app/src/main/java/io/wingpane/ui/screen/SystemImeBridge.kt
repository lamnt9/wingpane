package io.wingpane.ui.screen

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import io.wingpane.input.SystemImeInput
import io.wingpane.proto.InputEvent

@Composable
internal fun SystemImeBridge(onEvents: (List<InputEvent>) -> Unit, requestFocus: Boolean) {
    val field = remember { mutableStateOf<ImeEditText?>(null) }
    AndroidView(
        modifier = Modifier.width(1.dp).height(1.dp),
        factory = { context ->
            ImeEditText(context).apply {
                alpha = 0.01f
                setSingleLine(false)
                setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                showSoftInputOnFocus = true
                field.value = this
            }
        },
        update = { edit ->
            edit.callback = onEvents
            if (requestFocus && !edit.hasFocus()) {
                edit.requestFocus()
                (edit.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            } else if (!requestFocus && edit.hasFocus()) {
                (edit.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(edit.windowToken, 0)
                edit.clearFocus()
            }
        },
    )
    DisposableEffect(Unit) {
        onDispose {
            field.value?.let { edit ->
                (edit.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(edit.windowToken, 0)
            }
        }
    }
}

private class ImeEditText(context: Context) : EditText(context) {
    var callback: ((List<InputEvent>) -> Unit)? = null
    fun send(events: List<InputEvent>) { callback?.invoke(events) }
    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(target, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val committed = super.commitText(text, newCursorPosition)
                if (committed && text != null) this@ImeEditText.send(SystemImeInput.committedText(text))
                return committed
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val composing = BaseInputConnection.getComposingSpanStart(this@ImeEditText.text) >= 0
                val deleted = super.deleteSurroundingText(beforeLength, afterLength)
                if (deleted && !composing && beforeLength > 0 && afterLength == 0) {
                    this@ImeEditText.send(SystemImeInput.backspace())
                }
                return deleted
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) {
                    this@ImeEditText.send(SystemImeInput.backspace())
                    return true
                }
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                    this@ImeEditText.send(SystemImeInput.keyTap(0x28))
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }
}
