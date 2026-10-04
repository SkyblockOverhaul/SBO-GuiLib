package net.sbo.guilib.fabric.render

import kotlin.math.roundToInt

/**
 * Edges of boxes on whole GUI pixels. Plain and rounded boxes use the same edges, so a rounded box lines up with the
 * plain boxes around it (e.g. the top part of a stacked bar), and boxes that touch share their edge.
 */
internal object PixelSnap {
    /** Start and end of [start] + [size] on whole GUI pixels. */
    fun span(start: Float, size: Float): Pair<Int, Int> = start.roundToInt() to (start + size).roundToInt()
}
