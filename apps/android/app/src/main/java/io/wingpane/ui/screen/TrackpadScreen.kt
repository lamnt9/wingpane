package io.wingpane.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.wingpane.input.TrackpadGestureDetector
import io.wingpane.proto.InputEvent
import io.wingpane.ui.theme.WingpaneTheme

@Composable
fun TrackpadScreen(detector: TrackpadGestureDetector, onEvents: (List<InputEvent>) -> Unit, modifier: Modifier = Modifier) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Box(modifier.fillMaxSize().background(WingpaneTheme.colors.bg).pointerInput(detector) {
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
}
