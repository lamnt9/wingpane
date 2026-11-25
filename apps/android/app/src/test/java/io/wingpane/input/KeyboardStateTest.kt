package io.wingpane.input

import io.wingpane.proto.InputEvent
import org.junit.Assert.*
import org.junit.Test

class KeyboardStateTest {
    @Test fun stickyModifierIsOneShotThenCanLock() {
        val state=KeyboardState(); state.modifierTap(0xE3)
        assertEquals(8u.toUByte(), (state.key(0x06,true).single() as InputEvent.Keyboard).modifiers)
        assertEquals(0u.toUByte(), (state.key(0x06,false).single() as InputEvent.Keyboard).modifiers)
        state.modifierTap(0xE3); state.modifierTap(0xE3)
        assertEquals(8u.toUByte(), (state.key(0x06,true).single() as InputEvent.Keyboard).modifiers)
        state.modifierTap(0xE3)
        assertEquals(0u.toUByte(), (state.key(0x06,true).single() as InputEvent.Keyboard).modifiers)
    }
    @Test fun heldKeysAlwaysGetMatchingUp() {
        val state=KeyboardState(); state.key(0x2C,true)
        assertEquals(listOf(InputEvent.Keyboard(0x2Cu.toUShort(),false,0u.toUByte())), state.releaseAll())
    }
}
