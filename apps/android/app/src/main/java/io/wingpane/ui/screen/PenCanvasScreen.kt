package io.wingpane.ui.screen

import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

/** Canvas overlay; the transparent input view leaves the underlying decoder SurfaceView alive. */
@Composable
fun PenCanvasScreen(onStylus: (MotionEvent, View) -> Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.6f).background(Color.Black))
        AndroidView(factory = { context -> View(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener { v, e -> onStylus(e, v) }
            setOnGenericMotionListener { v, e -> onStylus(e, v) }
        } }, modifier = Modifier.fillMaxSize())
    }
}
