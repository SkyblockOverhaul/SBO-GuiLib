package net.sbo.guilib.core.paint

import net.sbo.guilib.core.css.BackgroundLayer
import net.sbo.guilib.core.css.Colors
import net.sbo.guilib.core.css.Length
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Triangles with per-vertex colors (absolute GUI coordinates). Vertex `i` is `(x[i], y[i], color[i])`;
 * every 3 vertices form one triangle.
 */
class ColorMesh(val x: FloatArray, val y: FloatArray, val color: IntArray) {
    val triangleCount get() = x.size / 3
}

/**
 * Turns CSS gradients into [ColorMesh]es.
 *
 * Linear gradients are exact: between two color stops the color is an affine function of the position, which is
 * exactly what the GPU's per-vertex color interpolation over a triangle computes. The box is cut into one polygon
 * per stop interval. Radial gradients use rings of [RADIAL_SEGMENTS] segments (exact along the radius).
 */
object GradientMesh {
    const val RADIAL_SEGMENTS = 64

    private class Builder {
        val x = ArrayList<Float>()
        val y = ArrayList<Float>()
        val c = ArrayList<Int>()
        fun tri(ax: Float, ay: Float, ac: Int, bx: Float, by: Float, bc: Int, cx: Float, cy: Float, cc: Int) {
            x += ax; y += ay; c += ac
            x += bx; y += by; c += bc
            x += cx; y += cy; c += cc
        }
        fun build() = ColorMesh(x.toFloatArray(), y.toFloatArray(), c.toIntArray())
    }

    /** A polygon vertex with its gradient parameter `t` (0..1 along the gradient line/ray). */
    private data class V(val x: Float, val y: Float, val t: Float)

    fun build(g: BackgroundLayer.Gradient, x: Float, y: Float, w: Float, h: Float, alpha: Float): ColorMesh =
        if (g.radial) radial(g, x, y, w, h, alpha) else linear(g, x, y, w, h, alpha)

    // ---- stops ---------------------------------------------------------------------------------------------------

    private class ResolvedStop(val color: Int, val t: Float)

    /** Resolves stop positions (CSS Images §3.5.3): px/% of [lineLength], missing ones spread evenly, monotonic. */
    private fun resolveStops(stops: List<BackgroundLayer.Stop>, lineLength: Float, alpha: Float): List<ResolvedStop> {
        val pos = arrayOfNulls<Float>(stops.size)
        fun toT(l: Length): Float = if (l.isPercent) l.value / 100f else if (lineLength > 0f) l.value / lineLength else 0f
        stops.forEachIndexed { i, s -> pos[i] = s.position?.let(::toT) }
        if (pos[0] == null) pos[0] = 0f
        if (pos[pos.lastIndex] == null) pos[pos.lastIndex] = 1f
        // A stop may not come before an earlier one (CSS clamps it to the largest previous position).
        var maxSoFar = pos[0]!!
        for (i in 1 until pos.size) pos[i]?.let { p -> if (p < maxSoFar) pos[i] = maxSoFar else maxSoFar = p }
        var i = 1
        while (i < pos.size) {
            if (pos[i] == null) {
                val start = i - 1
                var end = i
                while (pos[end] == null) end++
                val a = pos[start]!!
                val b = maxOf(pos[end]!!, a)
                for (k in start + 1 until end) pos[k] = a + (b - a) * (k - start) / (end - start)
                i = end
            }
            i++
        }
        return stops.mapIndexed { idx, s -> ResolvedStop(Colors.withOpacity(s.color as Int, alpha), pos[idx]!!) }
    }

    /**
     * Color at [t] between stops [a] and [b]. Interpolation follows CSS (premultiplied): when one end is fully
     * transparent it takes the other end's RGB, so "transparent → white" doesn't fade through grey.
     */
    private fun colorAt(a: ResolvedStop, b: ResolvedStop, t: Float): Int {
        val f = if (b.t > a.t) ((t - a.t) / (b.t - a.t)).coerceIn(0f, 1f) else 0f
        var ca = a.color
        var cb = b.color
        if (Colors.alpha(ca) == 0) ca = cb and 0x00FFFFFF
        if (Colors.alpha(cb) == 0) cb = ca and 0x00FFFFFF
        fun ch(shift: Int) = (((ca ushr shift) and 0xFF) + (((cb ushr shift) and 0xFF) - ((ca ushr shift) and 0xFF)) * f).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // ---- linear --------------------------------------------------------------------------------------------------

    private fun linear(g: BackgroundLayer.Gradient, x: Float, y: Float, w: Float, h: Float, alpha: Float): ColorMesh {
        val angleDeg = g.toCorner?.let { (dx, dy) ->
            // "to top right" etc.: the gradient line is perpendicular to the diagonal through the other two corners.
            val a = Math.toDegrees(atan2(w.toDouble(), h.toDouble())).toFloat()
            when {
                dx > 0 && dy < 0 -> a
                dx > 0 && dy > 0 -> 180f - a
                dx < 0 && dy > 0 -> 180f + a
                else -> 360f - a
            }
        } ?: g.angle
        val rad = angleDeg * PI.toFloat() / 180f
        val dx = sin(rad)
        val dy = -cos(rad)
        val length = abs(w * dx) + abs(h * dy)
        val cx = x + w / 2f
        val cy = y + h / 2f
        val sx = cx - dx * length / 2f
        val sy = cy - dy * length / 2f
        fun tOf(px: Float, py: Float) = if (length > 0f) ((px - sx) * dx + (py - sy) * dy) / length else 0f

        val stops = resolveStops(g.stops, length, alpha)
        val rect = listOf(V(x, y, tOf(x, y)), V(x + w, y, tOf(x + w, y)), V(x + w, y + h, tOf(x + w, y + h)), V(x, y + h, tOf(x, y + h)))
        val out = Builder()

        // Before the first stop and after the last: solid colors.
        emitSlab(out, rect, Float.NEGATIVE_INFINITY, stops.first().t, stops.first(), stops.first())
        for (i in 0 until stops.size - 1) emitSlab(out, rect, stops[i].t, stops[i + 1].t, stops[i], stops[i + 1])
        emitSlab(out, rect, stops.last().t, Float.POSITIVE_INFINITY, stops.last(), stops.last())
        return out.build()
    }

    /** Emits the part of [poly] with `t0 <= t <= t1`, colored by interpolating between [a] and [b]. */
    private fun emitSlab(out: Builder, poly: List<V>, t0: Float, t1: Float, a: ResolvedStop, b: ResolvedStop) {
        if (t1 <= t0 && !(t0 == Float.NEGATIVE_INFINITY || t1 == Float.POSITIVE_INFINITY)) return
        var p = clipT(poly, t0, keepAbove = true)
        p = clipT(p, t1, keepAbove = false)
        if (p.size < 3) return
        fun col(v: V) = colorAt(a, b, v.t)
        for (i in 1 until p.size - 1) {
            out.tri(p[0].x, p[0].y, col(p[0]), p[i].x, p[i].y, col(p[i]), p[i + 1].x, p[i + 1].y, col(p[i + 1]))
        }
    }

    /** Sutherland–Hodgman clip against the half-plane `t >= limit` ([keepAbove]) or `t <= limit`. */
    private fun clipT(poly: List<V>, limit: Float, keepAbove: Boolean): List<V> {
        if (limit.isInfinite() || poly.isEmpty()) return poly
        fun inside(v: V) = if (keepAbove) v.t >= limit else v.t <= limit
        val out = ArrayList<V>()
        for (i in poly.indices) {
            val cur = poly[i]
            val prev = poly[(i + poly.size - 1) % poly.size]
            val ci = inside(cur)
            val pi = inside(prev)
            if (ci != pi) {
                val f = (limit - prev.t) / (cur.t - prev.t)
                out += V(prev.x + (cur.x - prev.x) * f, prev.y + (cur.y - prev.y) * f, limit)
            }
            if (ci) out += cur
        }
        return out
    }

    // ---- radial --------------------------------------------------------------------------------------------------

    private fun radial(g: BackgroundLayer.Gradient, x: Float, y: Float, w: Float, h: Float, alpha: Float): ColorMesh {
        fun lenX(l: Length) = if (l.isPercent) w * l.value / 100f else l.value
        fun lenY(l: Length) = if (l.isPercent) h * l.value / 100f else l.value
        val cx = x + lenX(g.centerX)
        val cy = y + lenY(g.centerY)
        val left = cx - x
        val right = x + w - cx
        val top = cy - y
        val bottom = y + h - cy
        var rx: Float
        var ry: Float
        val explicit = g.explicitSize
        if (explicit != null) {
            rx = lenX(explicit.first); ry = if (g.circle) rx else lenY(explicit.second)
        } else if (g.circle) {
            val r = when (g.size) {
                BackgroundLayer.RadialSize.CLOSEST_SIDE -> min(min(left, right), min(top, bottom))
                BackgroundLayer.RadialSize.FARTHEST_SIDE -> max(max(left, right), max(top, bottom))
                BackgroundLayer.RadialSize.CLOSEST_CORNER -> hypot(min(left, right), min(top, bottom))
                BackgroundLayer.RadialSize.FARTHEST_CORNER -> hypot(max(left, right), max(top, bottom))
            }
            rx = r; ry = r
        } else {
            val closest = BackgroundLayer.RadialSize.CLOSEST_SIDE == g.size || BackgroundLayer.RadialSize.CLOSEST_CORNER == g.size
            rx = if (closest) min(left, right) else max(left, right)
            ry = if (closest) min(top, bottom) else max(top, bottom)
            if (g.size == BackgroundLayer.RadialSize.CLOSEST_CORNER || g.size == BackgroundLayer.RadialSize.FARTHEST_CORNER) {
                rx *= sqrt(2f); ry *= sqrt(2f)
            }
        }
        rx = max(rx, 0.0001f)
        ry = max(ry, 0.0001f)

        val stops = resolveStops(g.stops, rx, alpha)
        // Rings at every stop plus one ring far enough out to cover the whole box.
        val cover = max(
            max(hypot(left / rx, top / ry), hypot(right / rx, top / ry)),
            max(hypot(left / rx, bottom / ry), hypot(right / rx, bottom / ry)),
        ) * 1.01f
        val ringTs = (listOf(0f) + stops.map { it.t } + listOf(max(cover, stops.last().t + 0.0001f))).distinct().sorted()

        fun color(t: Float): Int {
            if (t <= stops.first().t) return stops.first().color
            if (t >= stops.last().t) return stops.last().color
            for (i in 0 until stops.size - 1) if (t <= stops[i + 1].t) return colorAt(stops[i], stops[i + 1], t)
            return stops.last().color
        }

        val box = listOf(x to y, x + w to y, x + w to y + h, x to y + h)
        val out = Builder()
        val n = RADIAL_SEGMENTS
        for (ri in 0 until ringTs.size - 1) {
            val t0 = ringTs[ri]
            val t1 = ringTs[ri + 1]
            val c0 = color(t0)
            val c1 = color(t1)
            for (s in 0 until n) {
                val a0 = 2f * PI.toFloat() * s / n
                val a1 = 2f * PI.toFloat() * (s + 1) / n
                val p00 = cx + cos(a0) * rx * t0 to cy + sin(a0) * ry * t0
                val p01 = cx + cos(a1) * rx * t0 to cy + sin(a1) * ry * t0
                val p10 = cx + cos(a0) * rx * t1 to cy + sin(a0) * ry * t1
                val p11 = cx + cos(a1) * rx * t1 to cy + sin(a1) * ry * t1
                if (t0 == 0f) {
                    // Innermost ring: a fan around the center.
                    clippedTri(out, box, p00, c0, p10, c1, p11, c1)
                } else {
                    clippedTri(out, box, p00, c0, p10, c1, p11, c1)
                    clippedTri(out, box, p00, c0, p11, c1, p01, c0)
                }
            }
        }
        return out.build()
    }

    private class CV(val x: Float, val y: Float, val c: Int)

    /** Clips a colored triangle to the axis-aligned [box] (colors are interpolated at new vertices). */
    private fun clippedTri(out: Builder, box: List<Pair<Float, Float>>, a: Pair<Float, Float>, ac: Int, b: Pair<Float, Float>, bc: Int, c: Pair<Float, Float>, cc: Int) {
        val minX = box[0].first
        val minY = box[0].second
        val maxX = box[2].first
        val maxY = box[2].second
        var poly = listOf(CV(a.first, a.second, ac), CV(b.first, b.second, bc), CV(c.first, c.second, cc))
        if (poly.all { it.x >= minX && it.x <= maxX && it.y >= minY && it.y <= maxY }) {
            out.tri(poly[0].x, poly[0].y, poly[0].c, poly[1].x, poly[1].y, poly[1].c, poly[2].x, poly[2].y, poly[2].c)
            return
        }
        poly = clipAxis(poly, minX, true, true)
        poly = clipAxis(poly, maxX, true, false)
        poly = clipAxis(poly, minY, false, true)
        poly = clipAxis(poly, maxY, false, false)
        for (i in 1 until poly.size - 1) out.tri(poly[0].x, poly[0].y, poly[0].c, poly[i].x, poly[i].y, poly[i].c, poly[i + 1].x, poly[i + 1].y, poly[i + 1].c)
    }

    private fun clipAxis(poly: List<CV>, limit: Float, isX: Boolean, keepAbove: Boolean): List<CV> {
        if (poly.isEmpty()) return poly
        fun v(p: CV) = if (isX) p.x else p.y
        fun inside(p: CV) = if (keepAbove) v(p) >= limit else v(p) <= limit
        val out = ArrayList<CV>()
        for (i in poly.indices) {
            val cur = poly[i]
            val prev = poly[(i + poly.size - 1) % poly.size]
            if (inside(cur) != inside(prev)) {
                val f = (limit - v(prev)) / (v(cur) - v(prev))
                out += CV(prev.x + (cur.x - prev.x) * f, prev.y + (cur.y - prev.y) * f, lerpColor(prev.c, cur.c, f))
            }
            if (inside(cur)) out += cur
        }
        return out
    }

    private fun lerpColor(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int) = (((a ushr shift) and 0xFF) + (((b ushr shift) and 0xFF) - ((a ushr shift) and 0xFF)) * f).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
