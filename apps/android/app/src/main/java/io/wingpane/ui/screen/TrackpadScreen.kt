package io.wingpane.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.wingpane.input.TrackpadGestureDetector
import io.wingpane.input.KeyboardState
import io.wingpane.proto.InputEvent
import io.wingpane.ui.theme.WingpaneTheme
import io.wingpane.ui.util.performWingpaneHaptic

@Composable
fun TrackpadScreen(detector: TrackpadGestureDetector, onEvents: (List<InputEvent>) -> Unit, modifier: Modifier = Modifier) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    var panel by remember { mutableStateOf("Trackpad") }
    var imeOpen by remember { mutableStateOf(false) }
    val keyboard = remember { KeyboardState() }
    Column(modifier.fillMaxSize().background(WingpaneTheme.colors.bg)) {
    Box(Modifier.fillMaxWidth().weight(1f).pointerInput(detector) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var active = true
            onEvents(detector.onTouch(TrackpadGestureDetector.Action.Down, listOf(TrackpadGestureDetector.PointerSample(down.id.value.toInt(), down.position.x, down.position.y)), android.os.SystemClock.uptimeMillis()))
            try {
                while (active) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointers = event.changes.filter { it.pressed }.map { TrackpadGestureDetector.PointerSample(it.id.value.toInt(), it.position.x, it.position.y) }
                    val time = android.os.SystemClock.uptimeMillis()
                    if (pointers.isEmpty()) {
                        onEvents(detector.onTouch(TrackpadGestureDetector.Action.Up, emptyList(), time)); active = false
                        scope.launch { while (true) { delay(16); val inertia = detector.tick(android.os.SystemClock.uptimeMillis()); if (inertia.isEmpty()) break; onEvents(inertia) } }
                    } else {
                        onEvents(detector.onTouch(TrackpadGestureDetector.Action.Move, pointers, time))
                        event.changes.forEach { it.consume() }
                    }
                }
            } finally {
                if (active) onEvents(detector.onTouch(TrackpadGestureDetector.Action.Cancel, emptyList(), android.os.SystemClock.uptimeMillis()))
            }
        }
    })
    var modTick by remember { mutableStateOf(0) }
    fun tap(usage: Int) {
        performWingpaneHaptic(context, view)
        onEvents(keyboard.key(usage, true) + keyboard.key(usage, false))
        modTick++
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Trackpad", "Keyboard", "Numpad").forEach { name ->
            Key(name, Modifier.weight(1f), selected = panel == name) { panel = name }
        }
        Key(if (imeOpen) "Done" else "IME", Modifier.weight(1f), selected = imeOpen) { imeOpen = !imeOpen }
    }
    SystemImeBridge(onEvents = onEvents, requestFocus = imeOpen)
    if (panel == "Keyboard") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            @Composable
            fun KRow(keys: List<MacKey>) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                keys.forEach { k ->
                    if (k.spacer) { Box(Modifier.weight(k.weight).height(48.dp)); return@forEach }
                    if (k.modifier) {
                        val state = remember(modTick) { keyboard.modifierState(k.usage) }
                        Key(if (state == 2) "${k.label}🔒" else k.label, Modifier.weight(k.weight), selected = state > 0) {
                            performWingpaneHaptic(context, view); keyboard.modifierTap(k.usage); modTick++
                        }
                    } else Key(k.label, Modifier.weight(k.weight)) { tap(k.usage) }
                }
            }
            KRow(listOf(MacKey("esc", 0x29, 1.4f)) + "1234567890-=".map { MacKey(it.toString(), hidUsage(it)) } + MacKey("⌫", 0x2A, 1.6f))
            KRow(listOf(MacKey("tab", 0x2B, 1.6f)) + "QWERTYUIOP".map { MacKey(it.toString(), hidUsage(it)) } +
                listOf(MacKey("[", 0x2F), MacKey("]", 0x30), MacKey("\\", 0x31)))
            KRow(listOf(MacKey("", 0x00, 1.0f, spacer = true)) + "ASDFGHJKL".map { MacKey(it.toString(), hidUsage(it)) } +
                listOf(MacKey(";", 0x33), MacKey("'", 0x34), MacKey("return", 0x28, 1.9f)))
            KRow(listOf(MacKey("⇧", 0xE1, 2.2f, modifier = true)) + "ZXCVBNM".map { MacKey(it.toString(), hidUsage(it)) } +
                listOf(MacKey(",", 0x36), MacKey(".", 0x37), MacKey("/", 0x38), MacKey("⇧", 0xE5, 2.2f, modifier = true)))
            KRow(listOf(
                MacKey("⌃", 0xE0, 1.2f, modifier = true), MacKey("⌥", 0xE2, 1.2f, modifier = true), MacKey("⌘", 0xE3, 1.5f, modifier = true),
                MacKey("space", 0x2C, 6.0f),
                MacKey("⌘", 0xE7, 1.5f, modifier = true), MacKey("⌥", 0xE6, 1.2f, modifier = true),
                MacKey("←", 0x50, 1.0f), MacKey("↓", 0x51, 1.0f), MacKey("↑", 0x52, 1.0f), MacKey("→", 0x4F, 1.0f),
            ))
        }
    } else if (panel == "Numpad") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("789/", "456*", "123-", "0.=+").forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { c -> Key(c.toString(), Modifier.weight(1f)) { tap(numpadUsage(c)) } }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Key("↵", Modifier.weight(1f)) { tap(0x58) }
                Key("⌫", Modifier.weight(1f)) { tap(0x2A) }
            }
        }
    }
    }
}

private data class MacKey(val label: String, val usage: Int, val weight: Float = 1f, val modifier: Boolean = false, val spacer: Boolean = false)

@Composable
private fun Key(label: String, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val colors = WingpaneTheme.colors
    Box(
        modifier.height(48.dp)
            .background(if (selected) colors.primary else colors.surface2, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) { Text(label, color = if (selected) colors.onPrimary else colors.text, maxLines = 1) }
}

private fun numpadUsage(c: Char): Int = when(c) {
    in '1'..'9' -> 0x59 + (c - '1')
    '0' -> 0x62; '/' -> 0x54; '*' -> 0x55; '-' -> 0x56; '+' -> 0x57; '.' -> 0x63; '=' -> 0x67; '↵' -> 0x58
    else -> 0
}

private fun hidUsage(c: Char): Int = when(c.uppercaseChar()) {
    in 'A'..'Z' -> c.uppercaseChar()-'A'+0x04
    in '1'..'9' -> c-'1'+0x1E
    '0' -> 0x27
    '/' -> 0x38; '.' -> 0x37; ',' -> 0x36; ';' -> 0x33; '=' -> 0x2E; '-' -> 0x2D
    else -> 0
}
