package net.sbo.guilib.core.paint

import net.sbo.guilib.core.css.BgPosition
import net.sbo.guilib.core.css.BgRepeat
import net.sbo.guilib.core.css.BgRepeatXY
import net.sbo.guilib.core.css.BgSize
import net.sbo.guilib.core.css.Dim
import net.sbo.guilib.core.dom.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Where the copies of one background layer go (CSS Backgrounds 3): the image size from `background-size`, the first
 * copy placed by `background-position` inside the positioning [area] (`background-origin`), and copies repeated by
 * `background-repeat` until they cover the [clip] box (`background-clip`). Pure geometry, so it is unit-tested.
 */
internal object BackgroundTiles {
    /** More copies than this (a tiny size on a huge box) draws nothing instead of flooding the renderer. */
    const val MAX_TILES = 4096

    /**
     * Rects of the copies, in the same coordinates as [area] and [clip]; they may stick out of [clip] (the caller clips).
     * [natural] is the image's intrinsic size, or `null` for gradients (which then size to the area).
     */
    fun tiles(area: Rect, clip: Rect, natural: Pair<Float, Float>?, size: BgSize?, position: BgPosition?, repeat: BgRepeatXY?): List<Rect> {
        val rep = repeat ?: BgRepeatXY(BgRepeat.REPEAT, BgRepeat.REPEAT)
        val sz = size ?: BgSize.Explicit(Dim.Auto, Dim.Auto)
        val ratio = natural?.takeIf { it.first > 0f && it.second > 0f }
        var (w, h) = imageSize(area, ratio, sz)

        // round: squeeze or stretch so a whole number of copies fits the area; an auto other side keeps the ratio.
        val autoW = sz is BgSize.Explicit && sz.width == Dim.Auto
        val autoH = sz is BgSize.Explicit && sz.height == Dim.Auto
        if (rep.x == BgRepeat.ROUND && w > 0f) {
            val nw = area.width / maxOf(1, (area.width / w).roundToInt())
            if (autoH && rep.y != BgRepeat.ROUND) h *= nw / w
            w = nw
        }
        if (rep.y == BgRepeat.ROUND && h > 0f) {
            val nh = area.height / maxOf(1, (area.height / h).roundToInt())
            if (autoW && rep.x != BgRepeat.ROUND) w *= nh / h
            h = nh
        }
        if (w < 0.01f || h < 0.01f) return emptyList()

        val pos = position ?: BgPosition(Dim.ZERO, Dim.ZERO)
        var ox = pos.x.resolve(area.width - w) ?: 0f
        var oy = pos.y.resolve(area.height - h) ?: 0f
        if (pos.fromRight) ox = area.width - w - ox
        if (pos.fromBottom) oy = area.height - h - oy

        val xs = axis(rep.x, area.x, area.width, w, ox, clip.x, clip.right) ?: return emptyList()
        val ys = axis(rep.y, area.y, area.height, h, oy, clip.y, clip.bottom) ?: return emptyList()
        if (xs.size.toLong() * ys.size > MAX_TILES) return emptyList()
        val out = ArrayList<Rect>(xs.size * ys.size)
        for (y in ys) for (x in xs) out += Rect(x, y, w, h)
        return out
    }

    /** The size of one copy. */
    fun imageSize(area: Rect, natural: Pair<Float, Float>?, size: BgSize): Pair<Float, Float> = when (size) {
        BgSize.Cover, BgSize.Contain -> if (natural == null) area.width to area.height else {
            val sx = area.width / natural.first
            val sy = area.height / natural.second
            val s = if (size == BgSize.Cover) maxOf(sx, sy) else minOf(sx, sy)
            natural.first * s to natural.second * s
        }
        is BgSize.Explicit -> {
            val w = size.width.takeIf { it != Dim.Auto }?.resolve(area.width)
            val h = size.height.takeIf { it != Dim.Auto }?.resolve(area.height)
            when {
                w != null && h != null -> w to h
                w != null -> w to (natural?.let { w * it.second / it.first } ?: area.height)
                h != null -> (natural?.let { h * it.first / it.second } ?: area.width) to h
                else -> natural ?: (area.width to area.height)
            }
        }
    }

    /** Start coordinates of the copies along one axis, or `null` when there would be too many. */
    private fun axis(rep: BgRepeat, start: Float, areaLen: Float, len: Float, offset: Float, clipStart: Float, clipEnd: Float): List<Float>? {
        var first = start + offset
        var step = len
        when (rep) {
            BgRepeat.NO_REPEAT -> return listOf(first)
            BgRepeat.SPACE -> {
                // As many whole copies as fit, the first and last touching the area's edges, the rest spaced evenly.
                val n = floor(areaLen / len).toInt()
                if (n <= 1) return listOf(first)
                first = start
                step = len + (areaLen - n * len) / (n - 1)
            }
            BgRepeat.REPEAT, BgRepeat.ROUND -> {}
        }
        if ((clipEnd - clipStart) / step > MAX_TILES) return null
        first -= ceil((first - clipStart) / step) * step
        val out = ArrayList<Float>()
        var x = first
        while (x < clipEnd) {
            if (x + len > clipStart) out += x
            x += step
        }
        return out
    }
}
