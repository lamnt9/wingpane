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
import androidx.compose.material3.Button
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("Trackpad", "Keyboard", "Numpad").forEach { name -> Button(onClick = { panel=name }) { Text(name) } }
    }
    if (panel != "Trackpad") {
        val rows = if(panel=="Keyboard") listOf("1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM") else listOf("789/", "456*", "123-", "0.=+", "↵   ")
        rows.forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceEvenly) {
            row.forEach { char -> Button(onClick = { performWingpaneHaptic(context,view); val usage=if(panel=="Numpad") numpadUsage(char) else hidUsage(char); if(usage>0) onEvents(keyboard.key(usage,true)+keyboard.key(usage,false)) }, modifier=Modifier.weight(1f).padding(1.dp).height(48.dp)) { Text(char.toString()) }
        } } }
        if (panel=="Keyboard") Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceEvenly) {
            listOf("⌘" to 0xE3, "⌥" to 0xE2, "⌃" to 0xE0, "⇧" to 0xE1).forEach { (label,usage) -> Button(onClick={ performWingpaneHaptic(context,view); keyboard.modifierTap(usage) }, modifier=Modifier.weight(1f).height(48.dp)) { Text(label) } }
            Button(onClick={ performWingpaneHaptic(context,view); onEvents(keyboard.key(0x2C,true)+keyboard.key(0x2C,false)) }, modifier=Modifier.weight(1f).height(48.dp)) { Text("Space") }
        }
        if (panel=="Keyboard") Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceEvenly) {
            listOf("Tab" to 0x2B, "↵" to 0x28, "⌫" to 0x2A).forEach { (label,usage) -> Button(onClick={ performWingpaneHaptic(context,view); onEvents(keyboard.key(usage,true)+keyboard.key(usage,false)) }, modifier=Modifier.weight(1f).height(48.dp)) { Text(label) } }
        }
    }
    }
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
    '/' -> 0x38; '.' -> 0x37; '=' -> 0x2E; '-' -> 0x2D; '*' -> 0x55; '+' -> 0x57
    else -> 0
}
