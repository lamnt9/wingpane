package io.wingpane.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** Callback driven AVC decoder. All codec lifecycle and queue operations run on its HandlerThread. */
class VideoDecoder(
    private val requestKeyframe: () -> Unit,
    private val statsChanged: (Stats) -> Unit = {},
) : AutoCloseable {
    data class Stats(val fps: Float, val p50Us: Long, val p95Us: Long, val dropped: Long)
    private val thread = HandlerThread("Wingpane-VideoDecoder").apply { start() }
    private val handler = Handler(thread.looper)
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val queue = ArrayDeque<Pair<ByteArray, Long>>()
    private val inputSlots = ArrayDeque<Int>()
    private val latency = ArrayDeque<Long>()
    private val submittedAt = HashMap<Long, Long>()
    private val decoded = AtomicLong(0)
    private var intervalFrames = 0
    private var intervalStart = System.nanoTime()
    private var droppingUntilIdr = false
    @Volatile private var droppedCount = 0L

    fun setSurface(next: Surface?) { handler.post {
        if (surface === next) return@post
        surface = next
        stopCodec()
        maybeStart()
    } }

    fun configure(sizeWidth: Int, sizeHeight: Int) { handler.post {
        width = sizeWidth; height = sizeHeight
        maybeStart()
    } }

    fun queueAccessUnit(bytes: ByteArray, ptsUs: Long) = handler.post {
        val nals = AnnexB.split(bytes)
        nals.firstOrNull { it.isSps }?.let { sps = it.bytes }
        nals.firstOrNull { it.isPps }?.let { pps = it.bytes }
        val idr = nals.any { it.isIdr }
        if (codec == null) { maybeStart(); if (codec == null) return@post }
        if (droppingUntilIdr && !idr) { droppedCount++; publishStats(); return@post }
        if (idr) droppingUntilIdr = false
        if (queue.size >= 2) {
            droppedCount += queue.size
            queue.clear()
            requestKeyframe()
            if (!idr) {
                droppingUntilIdr = true
                droppedCount++
                publishStats()
                return@post
            }
        }
        queue.addLast(bytes to ptsUs)
        drain()
    }

    private fun maybeStart() {
        val s = sps ?: return; val p = pps ?: return
        val target = surface ?: return
        if (width <= 0 || height <= 0 || codec != null) return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(startCode + s))
                setByteBuffer("csd-1", ByteBuffer.wrap(startCode + p))
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            }
            val info = MediaCodecListCompat.avcDecoderInfo() ?: return
            val lowLatencySupported = Build.VERSION.SDK_INT >= 30 && info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            if (lowLatencySupported) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            // Vendor keys: output in decode order without DPB buffering. Ignored by codecs that don't know them.
            format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            format.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
            format.setInteger("vendor.low-latency.enable", 1)
            android.util.Log.i("WingpaneDecoder", "codec=${info.name} hw=${info.isHardwareAccelerated} featureLowLatency=$lowLatencySupported")
            codec = MediaCodec.createByCodecName(info.name).also { c ->
                c.setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(c: MediaCodec, index: Int) = drain(index)
                    override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        c.releaseOutputBuffer(index, true)
                        val now = System.nanoTime()
                        val submitted = submittedAt.remove(info.presentationTimeUs) ?: now
                        val delayUs = ((now - submitted).coerceAtLeast(0) / 1_000L)
                        latency.addLast(delayUs); while (latency.size > 120) latency.removeFirst()
                        intervalFrames++
                        if (now - intervalStart >= 1_000_000_000L) {
                            val fps = intervalFrames * 1_000_000_000f / (now - intervalStart)
                            intervalFrames = 0; intervalStart = now
                            publishStats(fps)
                        }
                    }
                    override fun onError(c: MediaCodec, e: MediaCodec.CodecException) { stopCodec() }
                    override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) = Unit
                }, handler)
                c.configure(format, target, null, 0)
                c.start()
            }
        } catch (_: Throwable) { stopCodec() }
    }

    private fun drain(index: Int? = null) {
        val c = codec ?: return
        if (index != null) inputSlots.addLast(index)
        while (queue.isNotEmpty() && inputSlots.isNotEmpty()) {
            val slot = inputSlots.removeFirst()
            val (bytes, pts) = queue.removeFirst()
            val buffer = c.getInputBuffer(slot) ?: continue
            buffer.clear(); buffer.put(bytes)
            submittedAt[pts] = System.nanoTime()
            c.queueInputBuffer(slot, 0, bytes.size, pts, 0)
        }
    }

    private fun publishStats(fps: Float = 0f) {
        val values = latency.toList().sorted()
        statsChanged(Stats(fps, percentile(values, .50), percentile(values, .95), droppedCount))
    }
    private fun percentile(a: List<Long>, p: Double) = if (a.isEmpty()) 0L else a[((a.size - 1) * p).toInt()]
    private fun stopCodec() { try { codec?.stop() } catch (_: Throwable) {}; try { codec?.release() } catch (_: Throwable) {}; codec = null; queue.clear(); inputSlots.clear(); submittedAt.clear() }
    override fun close() { handler.post { stopCodec(); thread.quitSafely() } }

    private object MediaCodecListCompat {
        /** Prefer a hardware AVC decoder; software decoders (c2.android.*) add latency. */
        fun avcDecoderInfo(): android.media.MediaCodecInfo? {
            val avc = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            return avc.firstOrNull { it.isHardwareAccelerated && !it.isSoftwareOnly } ?: avc.firstOrNull()
        }
    }
    companion object { private val startCode = byteArrayOf(0, 0, 0, 1) }
}
