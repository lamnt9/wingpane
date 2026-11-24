package io.wingpane.video

/** Annex-B access-unit helpers for H.264 byte streams. */
object AnnexB {
    data class Nal(val bytes: ByteArray, val type: Int) {
        val isSps get() = type == 7
        val isPps get() = type == 8
        val isIdr get() = type == 5
        val isNonIdr get() = type == 1
    }

    fun split(data: ByteArray): List<Nal> {
        fun start(at: Int): Pair<Int, Int>? {
            var i = at
            while (i + 3 <= data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                    if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return i to 4
                    if (data[i + 2] == 1.toByte()) return i to 3
                }
                i++
            }
            return null
        }
        val result = mutableListOf<Nal>()
        var marker = start(0) ?: return emptyList()
        while (true) {
            val payloadStart = marker.first + marker.second
            val next = start(payloadStart)
            var end = next?.first ?: data.size
            while (end > payloadStart && data[end - 1] == 0.toByte()) end--
            if (end > payloadStart) {
                val bytes = data.copyOfRange(payloadStart, end)
                result += Nal(bytes, bytes[0].toInt() and 0x1f)
            }
            if (next == null) break
            marker = next
        }
        return result
    }
}
