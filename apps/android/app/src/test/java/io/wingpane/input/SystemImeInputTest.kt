package io.wingpane.input

import io.wingpane.proto.InputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemImeInputTest {
    @Test fun committedVietnameseTextBecomesTextEventOnly() {
        assertEquals(listOf(InputEvent.Text("Tiếng Việt có dấu")), SystemImeInput.committedText("Tiếng Việt có dấu"))
    }

    @Test fun enterAndBackspaceUseKeyboardEvents() {
        assertEquals(listOf(InputEvent.Text("x")) + SystemImeInput.keyTap(0x28), SystemImeInput.committedText("x\n"))
        assertEquals(SystemImeInput.keyTap(0x2A), SystemImeInput.backspace())
    }

    @Test fun longCommittedTextIsSplitOnUtf8CharacterBoundaries() {
        val source = "ế".repeat(200) + "😀".repeat(100)
        val events = SystemImeInput.committedText(source)
        assertTrue(events.all { it is InputEvent.Text && it.text.toByteArray(Charsets.UTF_8).size <= 256 })
        assertEquals(source, events.filterIsInstance<InputEvent.Text>().joinToString("") { it.text })
    }
}
