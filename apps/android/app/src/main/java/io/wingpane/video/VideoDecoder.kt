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
    data class Stats(val fps: Float, val p50Us: Long, val p95Us: Long, val e2eP50Us: Long = 0, val e2eP95Us: Long = 0, val e2eMaxUs: Long = 0, val dropped: Long = 0, val offsetUs: Long = 0, val rttUs: Long = 0,
        val hostRecvP50Us: Long = 0, val hostRecvP95Us: Long = 0, val hostRecvMaxUs: Long = 0,
        val recvQueueP50Us: Long = 0, val recvQueueP95Us: Long = 0, val recvQueueMaxUs: Long = 0,
        val decodeP50Us: Long = 0, val decodeP95Us: Long = 0, val decodeMaxUs: Long = 0, val dropPolicyTriggers: Long = 0)
    private val thread = HandlerThread("Wingpane-VideoDecoder").apply { start() }
    private val handler = Handler(thread.looper)
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private data class QueuedFrame(val bytes: ByteArray, val ptsUs: Long, val receivedAtNs: Long)
    private data class SubmittedFrame(val submittedAtNs: Long, val receivedAtNs: Long)
    private val queue = ArrayDeque<QueuedFrame>()
    private val inputSlots = ArrayDeque<Int>()
    private val latency = ArrayDeque<Long>()
    private val e2eLatency = ArrayDeque<Long>()
    private val hostRecvLatency = ArrayDeque<Long>()
    private val recvQueueLatency = ArrayDeque<Long>()
    private val submittedAt = HashMap<Long, SubmittedFrame>()
    private val decoded = AtomicLong(0)
    private var intervalFrames = 0
    private var intervalStart = System.nanoTime()
    private var droppingUntilIdr = false
    @Volatile private var droppedCount = 0L
    @Volatile private var dropPolicyTriggers = 0L
    private var offsetUs = 0L
    private var rttUs = 0L
    private var lastLatencyLogNs = 0L

    fun updateClockSync(offset: Long, rtt: Long) { handler.post { offsetUs = offset; rttUs = rtt } }

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

    fun queueAccessUnit(bytes: ByteArray, ptsUs: Long, receivedAtNs: Long = System.nanoTime()) = handler.post {
        val nals = AnnexB.split(bytes)
        nals.firstOrNull { it.isSps }?.let { sps = it.bytes }
        nals.firstOrNull { it.isPps }?.let { pps = it.bytes }
        val idr = nals.any { it.isIdr }
        if (codec == null) { maybeStart(); if (codec == null) return@post }
        if (droppingUntilIdr && !idr) { droppedCount++; publishStats(); return@post }
        if (idr) droppingUntilIdr = false
        if (queue.size >= 2) {
            dropPolicyTriggers++
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
        queue.addLast(QueuedFrame(bytes, ptsUs, receivedAtNs))
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
                        val submitted = submittedAt.remove(info.presentationTimeUs) ?: SubmittedFrame(now, now)
                        val delayUs = ((now - submitted.submittedAtNs).coerceAtLeast(0) / 1_000L)
                        latency.addLast(delayUs); while (latency.size > 120) latency.removeFirst()
                        val hostRecvUs = submitted.receivedAtNs / 1_000L + offsetUs - info.presentationTimeUs
                        hostRecvLatency.addLast(hostRecvUs); while (hostRecvLatency.size > 120) hostRecvLatency.removeFirst()
                        val recvQueueUs = ((submitted.submittedAtNs - submitted.receivedAtNs).coerceAtLeast(0) / 1_000L)
                        recvQueueLatency.addLast(recvQueueUs); while (recvQueueLatency.size > 120) recvQueueLatency.removeFirst()
                        e2eLatency.addLast(now / 1_000L + offsetUs - info.presentationTimeUs)
                        while (e2eLatency.size > 120) e2eLatency.removeFirst()
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
            val frame = queue.removeFirst()
            val bytes = frame.bytes
            val pts = frame.ptsUs
            val buffer = c.getInputBuffer(slot) ?: continue
            buffer.clear(); buffer.put(bytes)
            submittedAt[pts] = SubmittedFrame(System.nanoTime(), frame.receivedAtNs)
            c.queueInputBuffer(slot, 0, bytes.size, pts, 0)
        }
    }

    private fun publishStats(fps: Float = 0f) {
        val values = latency.toList().sorted()
        val e2e = e2eLatency.toList().sorted()
        val hostRecv = hostRecvLatency.toList().sorted()
        val recvQueue = recvQueueLatency.toList().sorted()
        val stats = Stats(fps, percentile(values, .50), percentile(values, .95), percentile(e2e, .50), percentile(e2e, .95), e2e.maxOrNull() ?: 0, droppedCount, offsetUs, rttUs,
            percentile(hostRecv, .50), percentile(hostRecv, .95), hostRecv.maxOrNull() ?: 0,
            percentile(recvQueue, .50), percentile(recvQueue, .95), recvQueue.maxOrNull() ?: 0,
            percentile(values, .50), percentile(values, .95), values.maxOrNull() ?: 0, dropPolicyTriggers)
        val logNow = System.nanoTime()
        if (logNow - lastLatencyLogNs >= 1_000_000_000L) {
            lastLatencyLogNs = logNow
            android.util.Log.i("WingpaneLatency", "fps=${"%.1f".format(fps)} host_recv_p50_us=${stats.hostRecvP50Us} host_recv_p95_us=${stats.hostRecvP95Us} host_recv_max_us=${stats.hostRecvMaxUs} recv_queue_p50_us=${stats.recvQueueP50Us} recv_queue_p95_us=${stats.recvQueueP95Us} recv_queue_max_us=${stats.recvQueueMaxUs} queue_output_p50_us=${stats.decodeP50Us} queue_output_p95_us=${stats.decodeP95Us} queue_output_max_us=${stats.decodeMaxUs} e2e_p50_us=${stats.e2eP50Us} e2e_p95_us=${stats.e2eP95Us} e2e_max_us=${stats.e2eMaxUs} dropped=${stats.dropped} drop_idr_triggers=${stats.dropPolicyTriggers} offset_us=${stats.offsetUs} rtt_us=${stats.rttUs}")
            latency.clear(); e2eLatency.clear(); hostRecvLatency.clear(); recvQueueLatency.clear()
        }
        statsChanged(stats)
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
