package io.wingpane.uc

import org.junit.Assert.*
import org.junit.Test

class HidKeymapTest {
    @Test fun lettersDigitsAndUsSymbols() {
        (0x04..0x1d).forEach { hid ->
            assertEquals(('a' + hid - 0x04), HidKeymap.char(hid, 0))
            assertEquals(('A' + hid - 0x04), HidKeymap.char(hid, HidKeymap.SHIFT))
            assertEquals(29 + hid - 0x04, HidKeymap.keycode(hid))
        }
        assertEquals("1234567890", (0x1e..0x27).map { HidKeymap.char(it, 0) }.joinToString(""))
        assertEquals("!@#$%^&*()", (0x1e..0x27).map { HidKeymap.char(it, HidKeymap.SHIFT) }.joinToString(""))
        val pairs = mapOf(0x2d to ('-' to '_'), 0x2e to ('=' to '+'), 0x2f to ('[' to '{'),
            0x30 to (']' to '}'), 0x31 to ('\\' to '|'), 0x33 to (';' to ':'),
            0x34 to ('\'' to '"'), 0x35 to ('`' to '~'), 0x36 to (',' to '<'),
            0x37 to ('.' to '>'), 0x38 to ('/' to '?'))
        pairs.forEach { (hid, pair) ->
            assertEquals(pair.first, HidKeymap.char(hid, 0))
            assertEquals(pair.second, HidKeymap.char(hid, HidKeymap.SHIFT))
        }
    }
    @Test fun navigationAndModifiers() {
        mapOf(0x28 to 66, 0x2b to 61, 0x29 to 111, 0x2a to 67,
            0x4f to 22, 0x50 to 21, 0x51 to 20, 0x52 to 19).forEach { (hid, key) ->
            assertEquals(key, HidKeymap.keycode(hid))
        }
        assertEquals(0x11003, HidKeymap.androidMeta(0x11 or 0x22 or 0x44 or 0x88))
    }
}
