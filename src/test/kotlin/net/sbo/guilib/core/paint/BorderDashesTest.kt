package net.sbo.guilib.core.paint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BorderDashesTest {
    private fun assertClose(expected: Float, actual: Float) = assertEquals(expected, actual, 0.001f)

    @Test
    fun dashesAtBothEndsFillTheSideExactly() {
        // 1px dashed: 3px dashes, 3px gaps. 39px = 7 dashes + 6 gaps exactly.
        val s = BorderDashes.slots(39f, 3f, 3f, dashAtStart = true, dashAtEnd = true)
        assertEquals(7, s.size / 2)
        assertClose(0f, s[0]); assertClose(3f, s[1])
        assertClose(36f, s[12]); assertClose(3f, s[13])
    }

    @Test
    fun dashesAndGapsStretchEvenlyWhenTheLengthDoesNotFit() {
        val s = BorderDashes.slots(41f, 3f, 3f, dashAtStart = true, dashAtEnd = true)
        val n = s.size / 2
        assertEquals(7, n)
        assertClose(0f, s[0])
        assertClose(41f, s[2 * n - 2] + s[2 * n - 1]) // the last dash ends at the corner
        val period = s[2] - s[0]
        for (i in 1 until n) assertClose(period, s[2 * i] - s[2 * i - 2])
    }

    @Test
    fun gapsAtTheEndsLeaveRoomForTheCorners() {
        // Between two rounded corners (or the dashes of the adjacent sides): gap, dash, gap, …, dash, gap.
        val s = BorderDashes.slots(27f, 3f, 3f, dashAtStart = false, dashAtEnd = false)
        assertEquals(4, s.size / 2)
        assertClose(3f, s[0])
        assertClose(24f, s[6] + s[7])
    }

    @Test
    fun aSideTooShortForAGapIsSolid() {
        val s = BorderDashes.slots(4f, 3f, 3f, dashAtStart = true, dashAtEnd = true)
        assertEquals(listOf(0f, 4f), s.toList())
        assertTrue(BorderDashes.slots(1f, 3f, 3f, dashAtStart = false, dashAtEnd = false).isEmpty())
    }
}
