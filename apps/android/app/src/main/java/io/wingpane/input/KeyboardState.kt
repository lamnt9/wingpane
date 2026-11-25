package io.wingpane.input

import io.wingpane.proto.InputEvent

/** Pure HID keyboard state. Modifier taps are one-shot; a second tap locks until tapped again. */
class KeyboardState {
    private val sticky = mutableMapOf<Int, Int>() // usage -> 0 off, 1 one-shot, 2 locked
    private val held = linkedMapOf<Int, Int>()

    fun modifierTap(usage: Int) {
        sticky[usage] = when (sticky[usage] ?: 0) { 0 -> 1; 1 -> 2; else -> 0 }
    }
    fun key(usage: Int, down: Boolean): List<InputEvent> {
        val mask = modifierMask()
        if (down) held[usage] = mask else held.remove(usage)
        val result = mutableListOf<InputEvent>(InputEvent.Keyboard(usage.toUShort(), down, mask.toUByte()))
        if (down) sticky.entries.filter { it.value == 1 }.forEach { it.setValue(0) }
        return result
    }
    fun releaseAll(): List<InputEvent> = held.entries.toList().asReversed().map { InputEvent.Keyboard(it.key.toUShort(), false, it.value.toUByte()) }.also { held.clear() }
    private fun modifierMask(): Int = sticky.filterValues { it > 0 }.keys.fold(0) { bits, usage -> bits or (1 shl (usage - 0xE0)) }
}
