package net.sbo.guilib.fabric.render

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SideBordersTest {
    @Test
    fun widthsAndSideSurviveThePacking() {
        val widths = floatArrayOf(1f, 0f, 2.5f, 15.5f)
        for (side in 0 until 4) {
            val (w, s) = SideBorders.unpack(SideBorders.pack(widths, side))
            assertArrayEquals(widths, w)
            assertEquals(side, s)
        }
    }

    @Test
    fun packedValuesStayExactFloatsAboveTheOtherModes() {
        val max = SideBorders.pack(floatArrayOf(15.5f, 15.5f, 15.5f, 15.5f), 3)
        assertTrue(max < 16_777_216f)
        assertTrue(SideBorders.pack(FloatArray(4), 0) >= SideBorders.OFFSET)
    }

    @Test
    fun onlyWidthsUpToTheMaximumFit() {
        assertTrue(SideBorders.fits(floatArrayOf(1f, 1f, 1f, 2f)))
        assertFalse(SideBorders.fits(floatArrayOf(1f, 1f, 1f, 16f)))
    }
}
