package io.wingpane.uc

sealed interface Shortcut {
    data class GlobalAction(val action: Global) : Shortcut
    data class ContextAction(val action: Context) : Shortcut
    data class KeyAction(val keycode: Int, val meta: Int = 0) : Shortcut
    data object DeleteWord : Shortcut
}

enum class Global { BACK, HOME, RECENTS, NOTIFICATIONS, TAKE_SCREENSHOT, LOCK_SCREEN }
enum class Context { COPY, CUT, PASTE, SELECT_ALL }

object ShortcutMap {
    fun lookup(hid: Int, mods: Int, editorFocused: Boolean): Shortcut? {
        val ctrl = mods and HidKeymap.CTRL != 0
        val shift = mods and HidKeymap.SHIFT != 0
        val alt = mods and HidKeymap.ALT != 0
        val cmd = mods and HidKeymap.META != 0
        if (hid == 0x29) return Shortcut.GlobalAction(Global.BACK)
        if (cmd && !ctrl && !alt && hid == 0x0b) return Shortcut.GlobalAction(Global.HOME)
        if (cmd && !ctrl && !alt && hid == 0x2b || ctrl && !cmd && !alt && hid == 0x52)
            return Shortcut.GlobalAction(Global.RECENTS)
        if (ctrl && !cmd && !alt && hid == 0x51) return Shortcut.GlobalAction(Global.NOTIFICATIONS)
        if (cmd && shift && !ctrl && hid == 0x20) return Shortcut.GlobalAction(Global.TAKE_SCREENSHOT)
        if (cmd && ctrl && hid == 0x14) return Shortcut.GlobalAction(Global.LOCK_SCREEN)
        if (!editorFocused) return null
        if (cmd && !ctrl && !alt) {
            when (hid) {
                0x06 -> return Shortcut.ContextAction(Context.COPY)
                0x1b -> return Shortcut.ContextAction(Context.CUT)
                0x19 -> return Shortcut.ContextAction(Context.PASTE)
                0x04 -> return Shortcut.ContextAction(Context.SELECT_ALL)
                0x1d -> return Shortcut.KeyAction(54, HidKeymap.META_CTRL_ON)
                0x50 -> return Shortcut.KeyAction(122)
                0x4f -> return Shortcut.KeyAction(123)
            }
        }
        if (alt && !cmd && hid == 0x2a) return Shortcut.DeleteWord
        return null
    }
}
