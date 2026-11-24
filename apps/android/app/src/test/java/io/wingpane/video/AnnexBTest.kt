package io.wingpane.video

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AnnexBTest {
    @Test fun splitsMixedThreeAndFourByteStartCodesAndDetectsTypes() {
        val data = byteArrayOf(0,0,1,0x67,1, 0,0,0,1,0x68,2, 0,0,1,0x65,3, 0,0,0,1,0x41,4)
        val nals = AnnexB.split(data)
        assertEquals(listOf(7, 8, 5, 1), nals.map { it.type })
        assertTrue(nals[0].isSps); assertTrue(nals[1].isPps)
        assertTrue(nals[2].isIdr); assertTrue(nals[3].isNonIdr)
    }

    @Test fun parsesRealEncoderAssetSlice() {
        val file = listOf(File("app/src/debug/assets/sample-1280x800.h264"), File("src/debug/assets/sample-1280x800.h264"))
            .firstOrNull { it.isFile } ?: error("sample-1280x800.h264 not found from ${File(".").absolutePath}")
        val nals = AnnexB.split(file.readBytes())
        assertTrue("expected SPS", nals.any { it.isSps })
        assertTrue("expected PPS", nals.any { it.isPps })
        assertTrue("expected IDR", nals.any { it.isIdr })
        assertTrue("expected non-IDR slices", nals.any { it.isNonIdr })
    }
}
