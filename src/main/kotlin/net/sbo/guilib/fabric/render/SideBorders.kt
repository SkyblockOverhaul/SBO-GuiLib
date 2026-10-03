package net.sbo.guilib.fabric.render

import kotlin.math.roundToInt

/**
 * Different border widths per side of a rounded box, packed into the rounded-rect shader's `LineWidth` float: the four
 * widths (top, right, bottom, left) in half pixels with 5 bits each, the side the quad paints in 2 bits, plus [OFFSET]
 * so the shader tells this mode apart from the fill (≥ 0) and ring (< 0) modes. The result stays below 2^24, where
 * floats hold every integer exactly. Wider borders than [MAX_WIDTH] fall back to straight strips.
 */
internal object SideBorders {
    const val OFFSET = 4_194_304f // 2^22, above every packed value
    const val MAX_WIDTH = 15.5f

    fun fits(widths: FloatArray): Boolean = widths.all { it in 0f..MAX_WIDTH }

    fun pack(widths: FloatArray, side: Int): Float {
        var v = side and 3
        for (i in 0 until 4) v = v or ((widths[i] * 2f).roundToInt().coerceIn(0, 31) shl (2 + 5 * i))
        return OFFSET + v
    }

    /** What the vertex shader reads back: the widths and the side. */
    fun unpack(value: Float): Pair<FloatArray, Int> {
        val v = (value - OFFSET + 0.5f).toInt()
        return FloatArray(4) { i -> ((v shr (2 + 5 * i)) and 31) / 2f } to (v and 3)
    }
}
