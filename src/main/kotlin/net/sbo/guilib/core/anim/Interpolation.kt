package net.sbo.guilib.core.anim

import net.sbo.guilib.core.css.BackgroundLayer
import net.sbo.guilib.core.css.BoxShadow
import net.sbo.guilib.core.css.CalcNode
import net.sbo.guilib.core.css.Colors
import net.sbo.guilib.core.css.Dim
import net.sbo.guilib.core.css.FilterFn
import net.sbo.guilib.core.css.Length
import net.sbo.guilib.core.css.LineHeight
import net.sbo.guilib.core.css.Prop
import net.sbo.guilib.core.css.TextShadow
import net.sbo.guilib.core.css.TransformFn
import net.sbo.guilib.core.css.TransformOrigin
import net.sbo.guilib.core.css.Visibility
import net.sbo.guilib.core.css.Z_INDEX_AUTO
import kotlin.math.roundToInt

/** Interpolation of computed values, following CSS "animation types" where it matters for UIs. */
object Interpolation {
    private val COLOR_PROPS = setOf(
        Prop.COLOR, Prop.BACKGROUND_COLOR,
        Prop.BORDER_TOP_COLOR, Prop.BORDER_RIGHT_COLOR, Prop.BORDER_BOTTOM_COLOR, Prop.BORDER_LEFT_COLOR, Prop.CARET_COLOR,
    )

    /** Properties that are never animated (they configure animations themselves). */
    val NOT_ANIMATABLE = setOf(
        Prop.TRANSITION_PROPERTY, Prop.TRANSITION_DURATION, Prop.TRANSITION_TIMING_FUNCTION, Prop.TRANSITION_DELAY,
        Prop.ANIMATION_NAME, Prop.ANIMATION_DURATION, Prop.ANIMATION_TIMING_FUNCTION, Prop.ANIMATION_DELAY,
        Prop.ANIMATION_ITERATION_COUNT, Prop.ANIMATION_DIRECTION, Prop.ANIMATION_FILL_MODE, Prop.ANIMATION_PLAY_STATE,
        Prop.DISPLAY,
    )

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Color mix like CSS (premultiplied: a fully transparent end takes the other end's RGB). */
    fun color(a: Int, b: Int, t: Float): Int {
        var ca = a
        var cb = b
        if (Colors.alpha(ca) == 0) ca = cb and 0x00FFFFFF
        if (Colors.alpha(cb) == 0) cb = ca and 0x00FFFFFF
        fun ch(shift: Int) = lerp(((ca ushr shift) and 0xFF).toFloat(), ((cb ushr shift) and 0xFF).toFloat(), t).roundToInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun calcNode(d: Dim): CalcNode? = when (d) {
        is Dim.Px -> CalcNode.Px(d.px)
        is Dim.Pct -> CalcNode.Pct(d.pct)
        is Dim.Calc -> d.node
        else -> null
    }

    private fun dim(a: Dim, b: Dim, t: Float): Dim? = when {
        a is Dim.Px && b is Dim.Px -> Dim.Px(lerp(a.px, b.px, t))
        a is Dim.Pct && b is Dim.Pct -> Dim.Pct(lerp(a.pct, b.pct, t))
        else -> {
            val na = calcNode(a) ?: return null
            val nb = calcNode(b) ?: return null
            Dim.Calc(CalcNode.Op('+', CalcNode.Op('*', na, CalcNode.Num(1f - t)), CalcNode.Op('*', nb, CalcNode.Num(t))))
        }
    }

    private fun length(a: Length?, b: Length?, t: Float): Length? {
        if (a == null || b == null) return if (a == b) a else null
        if (a.unit != b.unit || a.isCalc || b.isCalc) return null
        return Length(lerp(a.value, b.value, t), a.unit)
    }

    private fun backgrounds(a: List<*>, b: List<*>, t: Float): List<BackgroundLayer>? {
        if (a.size != b.size) return null
        return a.indices.map { i ->
            val la = a[i] as BackgroundLayer
            val lb = b[i] as BackgroundLayer
            when {
                la == lb -> la
                la is BackgroundLayer.Gradient && lb is BackgroundLayer.Gradient &&
                    la.radial == lb.radial && la.conic == lb.conic && la.repeating == lb.repeating && la.stops.size == lb.stops.size && la.toCorner == lb.toCorner -> la.copy(
                    angle = lerp(la.angle, lb.angle, t),
                    fromAngle = lerp(la.fromAngle, lb.fromAngle, t),
                    stops = la.stops.indices.map { s ->
                        val sa = la.stops[s]
                        val sb = lb.stops[s]
                        BackgroundLayer.Stop(color(sa.color as Int, sb.color as Int, t), length(sa.position, sb.position, t) ?: (if (t < 0.5f) sa.position else sb.position))
                    },
                )
                else -> return null
            }
        }
    }

    /**
     * Transform lists interpolate function by function when both have the same shape; `none` acts as the identity
     * of the other list (like CSS). Other combinations switch discretely.
     */
    private fun transforms(a: List<*>, b: List<*>, t: Float): List<TransformFn>? {
        fun identity(f: Any?): TransformFn = when (f) {
            is TransformFn.Scale -> TransformFn.Scale(1f, 1f)
            is TransformFn.Rotate -> TransformFn.Rotate(0f)
            is TransformFn.Skew -> TransformFn.Skew(0f, 0f)
            is TransformFn.Matrix -> TransformFn.Matrix(1f, 0f, 0f, 1f, 0f, 0f)
            else -> TransformFn.Translate(Dim.ZERO, Dim.ZERO)
        }
        val la = if (a.isEmpty()) b.map(::identity) else a
        val lb = if (b.isEmpty()) a.map(::identity) else b
        if (la.size != lb.size) return null
        return la.indices.map { i ->
            val fa = la[i]
            val fb = lb[i]
            when {
                fa is TransformFn.Translate && fb is TransformFn.Translate ->
                    TransformFn.Translate(dim(fa.x, fb.x, t) ?: return null, dim(fa.y, fb.y, t) ?: return null)
                fa is TransformFn.Scale && fb is TransformFn.Scale -> TransformFn.Scale(lerp(fa.x, fb.x, t), lerp(fa.y, fb.y, t))
                fa is TransformFn.Rotate && fb is TransformFn.Rotate -> TransformFn.Rotate(lerp(fa.deg, fb.deg, t))
                fa is TransformFn.Skew && fb is TransformFn.Skew -> TransformFn.Skew(lerp(fa.x, fb.x, t), lerp(fa.y, fb.y, t))
                fa is TransformFn.Matrix && fb is TransformFn.Matrix -> TransformFn.Matrix(
                    lerp(fa.a, fb.a, t), lerp(fa.b, fb.b, t), lerp(fa.c, fb.c, t),
                    lerp(fa.d, fb.d, t), lerp(fa.tx, fb.tx, t), lerp(fa.ty, fb.ty, t),
                )
                else -> return null
            }
        }
    }

    /**
     * Shadow lists interpolate pairwise; the shorter list is padded with transparent zero shadows (like CSS).
     * Pairs where one is `inset` and the other isn't can't be interpolated.
     */
    private fun boxShadows(a: List<*>, b: List<*>, t: Float): List<BoxShadow>? {
        val n = maxOf(a.size, b.size)
        return (0 until n).map { i ->
            val sa = a.getOrNull(i) as BoxShadow?
            val sb = b.getOrNull(i) as BoxShadow?
            val x = sa ?: BoxShadow(0f, 0f, 0f, 0f, Colors.TRANSPARENT, sb!!.inset)
            val y = sb ?: BoxShadow(0f, 0f, 0f, 0f, Colors.TRANSPARENT, sa!!.inset)
            if (x.inset != y.inset) return null
            BoxShadow(
                lerp(x.offsetX, y.offsetX, t), lerp(x.offsetY, y.offsetY, t), lerp(x.blur, y.blur, t).coerceAtLeast(0f),
                lerp(x.spread, y.spread, t), color(x.color, y.color, t), x.inset,
            )
        }
    }

    /**
     * Filter lists interpolate function by function when the functions match pairwise; the shorter list is padded
     * with the functions' "no effect" values (so `none` ↔ `blur(4px)` works). Different functions switch discretely.
     */
    private fun filters(a: List<*>, b: List<*>, t: Float): List<FilterFn>? {
        val n = maxOf(a.size, b.size)
        return (0 until n).map { i ->
            val x = (a.getOrNull(i) ?: (b[i] as FilterFn).identity()) as FilterFn
            val y = (b.getOrNull(i) ?: x.identity()) as FilterFn
            when {
                x is FilterFn.ColorFn && y is FilterFn.ColorFn && x.kind == y.kind -> FilterFn.ColorFn(x.kind, lerp(x.amount, y.amount, t).coerceAtLeast(0f))
                x is FilterFn.Blur && y is FilterFn.Blur -> FilterFn.Blur(lerp(x.radius, y.radius, t).coerceAtLeast(0f))
                x is FilterFn.DropShadow && y is FilterFn.DropShadow -> FilterFn.DropShadow(
                    lerp(x.offsetX, y.offsetX, t), lerp(x.offsetY, y.offsetY, t), lerp(x.blur, y.blur, t).coerceAtLeast(0f), color(x.color, y.color, t),
                )
                else -> return null
            }
        }
    }

    /**
     * Value of [p] at [t] (0..1) between [a] and [b], or `null` if the values can't be interpolated
     * (callers then switch discretely).
     */
    fun interpolate(p: Prop, a: Any?, b: Any?, t: Float): Any? {
        if (p in NOT_ANIMATABLE) return null
        if (a == b) return a
        return when {
            p in COLOR_PROPS && a is Int && b is Int -> color(a, b, t)
            p == Prop.Z_INDEX && a is Int && b is Int -> if (a == Z_INDEX_AUTO || b == Z_INDEX_AUTO) null else lerp(a.toFloat(), b.toFloat(), t).roundToInt()
            a is Int && b is Int -> lerp(a.toFloat(), b.toFloat(), t).roundToInt()
            a is Float && b is Float -> lerp(a, b, t)
            a is Dim && b is Dim -> dim(a, b, t)
            a is LineHeight.Px && b is LineHeight.Px -> LineHeight.Px(lerp(a.px, b.px, t))
            a is LineHeight.Multiplier && b is LineHeight.Multiplier -> LineHeight.Multiplier(lerp(a.factor, b.factor, t))
            p == Prop.TEXT_SHADOW && (a is TextShadow || b is TextShadow) -> {
                val sa = a as TextShadow? ?: (b as TextShadow).copy(color = Colors.TRANSPARENT)
                val sb = b as TextShadow? ?: sa.copy(color = Colors.TRANSPARENT)
                TextShadow(lerp(sa.offsetX, sb.offsetX, t), lerp(sa.offsetY, sb.offsetY, t), color(sa.color as Int, sb.color as Int, t))
            }
            p == Prop.BOX_SHADOW && a is List<*> && b is List<*> -> boxShadows(a, b, t)
            p == Prop.FILTER && a is List<*> && b is List<*> -> filters(a, b, t)
            p == Prop.SCROLLBAR_COLOR && a is Pair<*, *> && b is Pair<*, *> ->
                Pair(color(a.first as Int, b.first as Int, t), color(a.second as Int, b.second as Int, t))
            p == Prop.BACKGROUND_IMAGE && a is List<*> && b is List<*> -> backgrounds(a, b, t)
            p == Prop.TRANSFORM && a is List<*> && b is List<*> -> transforms(a, b, t)
            a is TransformOrigin && b is TransformOrigin -> TransformOrigin(dim(a.x, b.x, t) ?: return null, dim(a.y, b.y, t) ?: return null)
            // Like CSS: visibility is "visible" during the whole transition if either end is visible.
            p == Prop.VISIBILITY && a is Visibility && b is Visibility -> when {
                t <= 0f -> a
                t >= 1f -> b
                else -> Visibility.VISIBLE
            }
            else -> null
        }
    }

    fun canInterpolate(p: Prop, a: Any?, b: Any?): Boolean = a != b && interpolate(p, a, b, 0.5f) != null

    /** Interpolates, or switches at the halfway point for discrete values (keyframe animations). */
    fun interpolateOrSwitch(p: Prop, a: Any?, b: Any?, t: Float): Any? = interpolate(p, a, b, t) ?: if (t < 0.5f) a else b
}
