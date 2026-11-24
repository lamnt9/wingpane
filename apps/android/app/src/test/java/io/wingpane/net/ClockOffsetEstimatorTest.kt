package io.wingpane.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockOffsetEstimatorTest {
    @Test fun picksMinimumRttInTenSecondWindowAndExpiresOldSamples() {
        val estimator = ClockOffsetEstimator()
        val base = 50_000_000_000L
        assertEquals(ClockOffsetEstimator.Estimate(2_000_000, 40_000), estimator.add(base, base + 40_000_000, 52_020_000uL))
        assertEquals(ClockOffsetEstimator.Estimate(3_000_000, 20_000), estimator.add(base + 1_000_000_000, base + 1_020_000_000, 54_010_000uL))
        assertEquals(ClockOffsetEstimator.Estimate(3_000_000, 20_000), estimator.current(base + 9_999_000_000))
        assertEquals(ClockOffsetEstimator.Estimate(3_000_000, 20_000), estimator.current(base + 10_001_000_000))
        assertNull(estimator.current(base + 11_021_000_001))
    }
}
