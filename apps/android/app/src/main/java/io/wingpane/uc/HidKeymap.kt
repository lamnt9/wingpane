package io.wingpane.uc

/** USB HID keyboard page 0x07, using the US ANSI printable layout. */
object HidKeymap {
    const val SHIFT = 0x22 // left/right HID modifier bits
    const val CTRL = 0x11
    const val ALT = 0x44
    const val META = 0x88
    const val META_SHIFT_ON = 0x1
    const val META_ALT_ON = 0x2
    const val META_CTRL_ON = 0x1000
    const val META_META_ON = 0x10000

    fun androidMeta(mods: Int): Int =
        (if (mods and SHIFT != 0) META_SHIFT_ON else 0) or
            (if (mods and ALT != 0) META_ALT_ON else 0) or
            (if (mods and CTRL != 0) META_CTRL_ON else 0) or
            (if (mods and META != 0) META_META_ON else 0)

    fun keycode(hid: Int): Int? = when (hid) {
        in 0x04..0x1d -> 29 + hid - 0x04
        in 0x1e..0x26 -> 8 + hid - 0x1e
        0x27 -> 7
        0x28 -> 66 // Enter
        0x29 -> 111 // Escape
        0x2a -> 67 // Backspace
        0x2b -> 61 // Tab
        0x2c -> 62 // Space
        0x4f -> 22 // Right
        0x50 -> 21 // Left
        0x51 -> 20 // Down
        0x52 -> 19 // Up
        0x49 -> 124 // Insert
        0x4a -> 122 // Home
        0x4b -> 92 // Page up
        0x4c -> 112 // Forward delete
        0x4d -> 123 // End
        0x4e -> 93 // Page down
        else -> null
    }

    fun char(hid: Int, mods: Int): Char? {
        val shifted = mods and SHIFT != 0
        if (hid in 0x04..0x1d) return ('a' + hid - 0x04).let { if (shifted) it.uppercaseChar() else it }
        if (hid in 0x1e..0x27) {
            val index = hid - 0x1e
            return (if (shifted) "!@#$%^&*()" else "1234567890")[index]
        }
        val pair = when (hid) {
            0x2c -> ' ' to ' '; 0x2d -> '-' to '_'; 0x2e -> '=' to '+'
            0x2f -> '[' to '{'; 0x30 -> ']' to '}'; 0x31 -> '\\' to '|'
            0x33 -> ';' to ':'; 0x34 -> '\'' to '"'; 0x35 -> '`' to '~'
            0x36 -> ',' to '<'; 0x37 -> '.' to '>'; 0x38 -> '/' to '?'
            else -> return null
        }
        return if (shifted) pair.second else pair.first
    }
}
