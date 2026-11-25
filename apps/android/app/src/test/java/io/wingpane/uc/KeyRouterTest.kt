package io.wingpane.uc

import org.junit.Assert.*
import org.junit.Test

class KeyRouterTest {
    @Test fun noEditorDropsLettersButAllowsGlobalShortcut() {
        val router = KeyRouter()
        assertEquals(emptyList<KeyAction>(), router.onKey(0x04, 0, true, false))
        assertEquals(listOf(KeyAction.Global(Global.BACK)), router.onKey(0x29, 0, true, false))
    }
    @Test fun ctrlSpaceTogglesVietnameseMode() {
        val router = KeyRouter()
        assertEquals(listOf(KeyAction.ToggleVi), router.onKey(0x2c, HidKeymap.CTRL, true, true))
        assertTrue(router.viMode)
        assertEquals(listOf(KeyAction.ToggleVi), router.onKey(0x2c, HidKeymap.CTRL, true, true))
        assertFalse(router.viMode)
    }
    @Test fun contextAndComposer() {
        val router = KeyRouter()
        assertEquals(listOf(KeyAction.Context(Context.COPY)), router.onKey(0x06, HidKeymap.META, true, true))
        router.viMode = true
        assertEquals(listOf(KeyAction.Commit(Edit(0, "a"))), router.onKey(0x04, 0, true, true))
        assertEquals(listOf(KeyAction.Commit(Edit(1, "á"))), router.onKey(0x16, 0, true, true))
        assertEquals(listOf(KeyAction.Commit(Edit(1, "a"))), router.onKey(0x2a, 0, true, true))
        assertEquals(listOf(KeyAction.Commit(Edit(1, ""))), router.onKey(0x2a, 0, true, true))
        assertEquals(listOf(KeyAction.SendKey(67)), router.onKey(0x2a, 0, true, true))
    }
    @Test fun keyUpDoesNothingAndBoundaryClearsBuffer() {
        val router = KeyRouter()
        router.viMode = true
        assertTrue(router.onKey(0x04, 0, false, true).isEmpty())
        router.onKey(0x04, 0, true, true)
        assertEquals(listOf(KeyAction.Commit(Edit(0, " "))), router.onKey(0x2c, 0, true, true))
        assertEquals(listOf(KeyAction.Commit(Edit(0, "a"))), router.onKey(0x04, 0, true, true))
    }
}
