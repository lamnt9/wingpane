package io.wingpane.uc

sealed interface KeyAction {
    data class Commit(val edit: Edit) : KeyAction
    data class SendKey(val keycode: Int, val meta: Int = 0) : KeyAction
    data class Global(val action: io.wingpane.uc.Global) : KeyAction
    data class Context(val action: io.wingpane.uc.Context) : KeyAction
    data object DeleteWord : KeyAction
    data object ToggleVi : KeyAction
}

class KeyRouter(val composer: TelexComposer = TelexComposer()) {
    var viMode: Boolean = false

    fun onKey(hid: Int, mods: Int, down: Boolean, editorFocused: Boolean): List<KeyAction> {
        if (!down) return emptyList()
        if (hid == 0x2c && mods and HidKeymap.CTRL != 0 && mods and HidKeymap.META == 0) {
            composer.commit()
            viMode = !viMode
            return listOf(KeyAction.ToggleVi)
        }
        val shortcut = ShortcutMap.lookup(hid, mods, editorFocused)
        if (shortcut != null) {
            composer.commit()
            return listOf(when (shortcut) {
                is Shortcut.GlobalAction -> KeyAction.Global(shortcut.action)
                is Shortcut.ContextAction -> KeyAction.Context(shortcut.action)
                is Shortcut.KeyAction -> KeyAction.SendKey(shortcut.keycode, shortcut.meta)
                Shortcut.DeleteWord -> KeyAction.DeleteWord
            })
        }
        if (!editorFocused) { composer.commit(); return emptyList() }
        if (hid == 0x2a) {
            val edit = if (viMode) composer.onBackspace() else null
            return if (edit != null) listOf(KeyAction.Commit(edit)) else listOf(KeyAction.SendKey(67))
        }
        val meta = HidKeymap.androidMeta(mods)
        if (hid in listOf(0x28, 0x2b, 0x4f, 0x50, 0x51, 0x52)) {
            composer.commit()
            return HidKeymap.keycode(hid)?.let { listOf(KeyAction.SendKey(it, meta)) } ?: emptyList()
        }
        if (mods and (HidKeymap.CTRL or HidKeymap.ALT or HidKeymap.META) != 0) {
            composer.commit()
            return HidKeymap.keycode(hid)?.let { listOf(KeyAction.SendKey(it, meta)) } ?: emptyList()
        }
        val c = HidKeymap.char(hid, mods) ?: return emptyList()
        if (c.isWhitespace() || !c.isLetterOrDigit()) {
            composer.commit()
            return listOf(KeyAction.Commit(Edit(0, c.toString())))
        }
        return listOf(KeyAction.Commit(if (viMode) composer.onChar(c) else Edit(0, c.toString())))
    }

    fun boundary() { composer.commit() }
    fun reset() { composer.reset() }
}
