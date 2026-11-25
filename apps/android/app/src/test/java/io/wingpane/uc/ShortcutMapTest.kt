package io.wingpane.uc

import org.junit.Assert.*
import org.junit.Test

class ShortcutMapTest {
    @Test fun globalActionsWorkWithOrWithoutEditor() {
        val cases = listOf(
            Triple(0x29, 0, Global.BACK), Triple(0x0b, HidKeymap.META, Global.HOME),
            Triple(0x2b, HidKeymap.META, Global.RECENTS),
            Triple(0x52, HidKeymap.CTRL, Global.RECENTS),
            Triple(0x51, HidKeymap.CTRL, Global.NOTIFICATIONS),
            Triple(0x20, HidKeymap.META or HidKeymap.SHIFT, Global.TAKE_SCREENSHOT),
            Triple(0x14, HidKeymap.META or HidKeymap.CTRL, Global.LOCK_SCREEN)
        )
        cases.forEach { (hid, mods, action) ->
            assertEquals(Shortcut.GlobalAction(action), ShortcutMap.lookup(hid, mods, false))
            assertEquals(Shortcut.GlobalAction(action), ShortcutMap.lookup(hid, mods, true))
        }
    }
    @Test fun editorActionsRequireFocus() {
        mapOf(0x06 to Context.COPY, 0x1b to Context.CUT, 0x19 to Context.PASTE,
            0x04 to Context.SELECT_ALL).forEach { (hid, action) ->
            assertNull(ShortcutMap.lookup(hid, HidKeymap.META, false))
            assertEquals(Shortcut.ContextAction(action), ShortcutMap.lookup(hid, HidKeymap.META, true))
        }
        assertEquals(Shortcut.KeyAction(54, HidKeymap.META_CTRL_ON), ShortcutMap.lookup(0x1d, HidKeymap.META, true))
        assertEquals(Shortcut.KeyAction(122), ShortcutMap.lookup(0x50, HidKeymap.META, true))
        assertEquals(Shortcut.KeyAction(123), ShortcutMap.lookup(0x4f, HidKeymap.META, true))
        assertEquals(Shortcut.DeleteWord, ShortcutMap.lookup(0x2a, HidKeymap.ALT, true))
    }
}
