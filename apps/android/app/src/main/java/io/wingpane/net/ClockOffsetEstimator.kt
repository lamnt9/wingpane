package io.wingpane.net

/** Selects the lowest-RTT host clock sample observed in the trailing window. */
class ClockOffsetEstimator(private val windowNs: Long = 10_000_000_000L) {
    data class Estimate(val offsetUs: Long, val rttUs: Long)
    private data class Sample(val receivedNs: Long, val offsetUs: Long, val rttUs: Long)
    private val samples = ArrayDeque<Sample>()

    @Synchronized
    fun add(sentNs: Long, receivedNs: Long, hostUs: ULong): Estimate? {
        if (receivedNs < sentNs) return estimate(receivedNs)
        val midpointUs = sentNs / 2_000L + receivedNs / 2_000L + ((sentNs % 2_000L + receivedNs % 2_000L) / 2_000L)
        samples.addLast(Sample(receivedNs, hostUs.toLong() - midpointUs, (receivedNs - sentNs) / 1_000L))
        return estimate(receivedNs)
    }

    @Synchronized
    fun current(nowNs: Long): Estimate? = estimate(nowNs)

    private fun estimate(nowNs: Long): Estimate? {
        while (samples.isNotEmpty() && nowNs - samples.first().receivedNs > windowNs) samples.removeFirst()
        return samples.minByOrNull { it.rttUs }?.let { Estimate(it.offsetUs, it.rttUs) }
    }
}
