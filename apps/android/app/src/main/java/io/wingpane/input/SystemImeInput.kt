package io.wingpane.input

import io.wingpane.proto.InputEvent

/** Pure conversion of committed system-IME input to the existing input protocol. */
object SystemImeInput {
    private const val BACKSPACE = 0x2A
    private const val ENTER = 0x28

    fun committedText(value: CharSequence): List<InputEvent> {
        val result = mutableListOf<InputEvent>()
        var text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) {
                result += InputEvent.Text(text.toString())
                text = StringBuilder()
            }
        }
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\n' || char == '\r') {
                flush()
                if (char == '\n') result += keyTap(ENTER)
                index++
            } else {
                val charCount = if (Character.isHighSurrogate(char) && index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) 2 else 1
                val piece = value.subSequence(index, index + charCount)
                val candidate = text.toString() + piece
                if (candidate.toByteArray(Charsets.UTF_8).size > 256) flush()
                text.append(piece)
                index += charCount
            }
        }
        flush()
        return result
    }

    fun backspace(): List<InputEvent> = keyTap(BACKSPACE)

    fun keyTap(usage: Int): List<InputEvent> = listOf(
        InputEvent.Keyboard(usage.toUShort(), true, 0u),
        InputEvent.Keyboard(usage.toUShort(), false, 0u),
    )
}
