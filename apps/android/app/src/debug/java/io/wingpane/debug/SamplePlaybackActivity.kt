package io.wingpane.debug

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.wingpane.video.AnnexB
import io.wingpane.video.VideoDecoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SamplePlaybackActivity : ComponentActivity() {
    private lateinit var decoder: VideoDecoder
    private var stats by mutableStateOf(VideoDecoder.Stats(0f, 0, 0, 0))
    private var file = "sample-2560x1600.h264"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = intent.getStringExtra("file") ?: file
        window.decorView.systemUiVisibility = 5894 or 1024 or 512
        decoder = VideoDecoder({}) { stats = it }
        decoder.configure(2560, 1600)
        setContent {
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { context -> SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = decoder.setSurface(holder.surface)
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = decoder.setSurface(holder.surface)
                        override fun surfaceDestroyed(holder: SurfaceHolder) = decoder.setSurface(null)
                    })
                } }, Modifier.fillMaxSize())
                Text("Sample: $file  ·  ${"%.1f".format(stats.fps)} fps  ·  p95 ${stats.p95Us} µs  ·  drop ${stats.dropped}", Modifier.align(Alignment.TopStart).padding(8.dp), color = Color.White)
            }
        }
        lifecycleScope.launch {
            val packets = withContext(kotlinx.coroutines.Dispatchers.IO) { assets.open(file).use { AnnexB.split(it.readBytes()) } }
            val units = mutableListOf<ByteArray>()
            val pending = mutableListOf<ByteArray>()
            for (nal in packets) {
                if (nal.isSps || nal.isPps) { pending += nal.bytes; continue }
                if (nal.isIdr || nal.isNonIdr) {
                    val all = pending + nal.bytes; pending.clear()
                    units += all.fold(ByteArray(0)) { acc, b -> acc + byteArrayOf(0, 0, 0, 1) + b }
                }
            }
            if (units.isEmpty()) return@launch
            var index = 0
            while (isActive) {
                decoder.queueAccessUnit(units[index], index * 16_667L)
                index = (index + 1) % units.size
                delay(16)
            }
        }
    }
    override fun onDestroy() { decoder.close(); super.onDestroy() }
}
