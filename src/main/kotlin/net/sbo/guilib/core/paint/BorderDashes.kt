package net.sbo.guilib.core.paint

import kotlin.math.roundToInt

/** Dash layout for `border-style: dashed` / `dotted`, like browsers: whole dashes, the gaps stretched to fit. */
internal object BorderDashes {
    /**
     * Dash slots along a side of [length]: `[start0, length0, start1, length1, …]`. With [dashAtStart] / [dashAtEnd] a
     * dash touches that end (a square corner); otherwise the side starts/ends with a gap (a rounded corner or the
     * corner dash of the adjacent side comes there). Dashes and gaps are scaled by the same factor so the pattern ends
     * exactly at both ends; a side too short for one gap is drawn solid.
     */
    fun slots(length: Float, dash: Float, gap: Float, dashAtStart: Boolean, dashAtEnd: Boolean): FloatArray {
        if (length <= 0f) return FloatArray(0)
        val endGaps = (if (dashAtStart) 0 else 1) + (if (dashAtEnd) 0 else 1)
        // length = n * dash + (n - 1 + endGaps) * gap
        val n = ((length - (endGaps - 1) * gap) / (dash + gap)).roundToInt().coerceAtLeast(if (endGaps == 0) 1 else 0)
        if (n == 0) return FloatArray(0)
        if (endGaps == 0 && n == 1) return floatArrayOf(0f, length)
        val f = length / (n * dash + (n - 1 + endGaps) * gap)
        val out = FloatArray(2 * n)
        var pos = if (dashAtStart) 0f else gap * f
        for (i in 0 until n) {
            out[2 * i] = pos
            out[2 * i + 1] = dash * f
            pos += (dash + gap) * f
        }
        return out
    }
}
