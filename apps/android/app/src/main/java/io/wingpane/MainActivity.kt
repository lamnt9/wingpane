package io.wingpane

import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.wingpane.net.*
import io.wingpane.input.TouchMapper
import io.wingpane.proto.*
import io.wingpane.video.VideoDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var client: WingpaneClient
    private lateinit var decoder: VideoDecoder
    private var state by mutableStateOf("Connecting")
    private var stats by mutableStateOf(VideoDecoder.Stats(0f, 0, 0, 0))
    private var touchMapper: TouchMapper? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        decoder = VideoDecoder({ lifecycleScope.launch { client.sendControl(ControlMessage.RequestKeyframe) } }) { stats = it }
        client = WingpaneClient()
        client.connect()
        lifecycleScope.launch {
            var wasActive = false
            client.state.collect {
                val activeNow = it is ClientState.Active
                if (wasActive && !activeNow) cancelTouch()
                wasActive = activeNow
                state = it.toString()
                if (it is ClientState.Active) it.accept.virtualDisplaySpec?.let { spec ->
                    touchMapper = TouchMapper(spec.first.toInt(), spec.second.toInt())
                    decoder.configure(spec.first.toInt(), spec.second.toInt())
                }
            }
        }
        lifecycleScope.launch {
            client.packets.collect { packet -> if (packet is Packet.Video) decoder.queueAccessUnit(packet.packet.payload, packet.packet.ptsUs.toLong()) }
        }
        setContent {
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { context -> SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { decoder.setSurface(holder.surface) }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { decoder.setSurface(holder.surface) }
                        override fun surfaceDestroyed(holder: SurfaceHolder) { decoder.setSurface(null) }
                    })
                    setOnTouchListener { view, event -> handleTouch(view, event) }
                } }, modifier = Modifier.fillMaxSize())
                Text("$state  ·  ${"%.1f".format(stats.fps)} fps  ·  p95 ${stats.p95Us} µs  ·  drop ${stats.dropped}", Modifier.align(Alignment.TopStart).padding(8.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        val mapper = touchMapper ?: return false
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchMapper.Action.Down
            MotionEvent.ACTION_MOVE -> TouchMapper.Action.Move
            MotionEvent.ACTION_UP -> TouchMapper.Action.Up
            MotionEvent.ACTION_POINTER_UP -> {
                val activeId = mapper.activePointerId() ?: return true
                if (event.getPointerId(event.actionIndex) != activeId) return true
                TouchMapper.Action.Up
            }
            MotionEvent.ACTION_CANCEL -> TouchMapper.Action.Cancel
            else -> return true // Ignore secondary pointers for the single-pointer MVP.
        }
        val pointerId = mapper.activePointerId()
        val pointerIndex = when (action) {
            TouchMapper.Action.Down -> event.actionIndex
            TouchMapper.Action.Cancel -> 0
            else -> pointerId?.let(event::findPointerIndex)?.takeIf { it >= 0 } ?: return true
        }
        val historical = if (action == TouchMapper.Action.Move) {
            val points = ArrayList<TouchMapper.Point>(event.historySize)
            for (index in 0 until event.historySize) {
                points += TouchMapper.Point(event.getHistoricalX(pointerIndex, index), event.getHistoricalY(pointerIndex, index))
            }
            points
        } else emptyList()
        val eventPointerId = if (action == TouchMapper.Action.Down) event.getPointerId(pointerIndex) else pointerId ?: event.getPointerId(pointerIndex)
        mapper.map(action, view.width, view.height, eventPointerId, event.getX(pointerIndex), event.getY(pointerIndex), historical)
            .forEach(client::sendInput)
        return true
    }
    private fun cancelTouch() {
        touchMapper?.cancel()?.forEach(client::sendInput)
    }
    override fun onPause() { cancelTouch(); super.onPause() }
    override fun onStop() { cancelTouch(); super.onStop() }
    override fun onDestroy() { cancelTouch(); client.cancel(); decoder.close(); super.onDestroy() }
}
