package net.sbo.guilib.core.dom

import net.sbo.guilib.core.css.ComputedStyle
import net.sbo.guilib.core.css.TransformFn
import kotlin.math.abs

/**
 * Axis-aligned 2D transform `p' = (sx·x + tx, sy·y + ty)`: what CSS `transform` lists of translate/scale reduce to.
 * Used by the painter (drawing and hit regions) and by [Element.getBoundingClientRect].
 */
data class Transform2D(val sx: Float, val sy: Float, val tx: Float, val ty: Float) {
    val isIdentity get() = sx == 1f && sy == 1f && tx == 0f && ty == 0f

    /** True if the scale collapses everything to nothing (e.g. `scale(0)`). */
    val isDegenerate get() = abs(sx) < 1e-4f || abs(sy) < 1e-4f

    /** Factor for sizes that must stay round (corner radii): the smaller absolute scale. */
    val scale get() = minOf(abs(sx), abs(sy))

    fun x(v: Float) = sx * v + tx
    fun y(v: Float) = sy * v + ty

    /** Maps [r]; negative scales flip, so the result is normalized to a positive size. */
    fun map(r: Rect): Rect {
        if (isIdentity) return r
        val x0 = x(r.x)
        val x1 = x(r.right)
        val y0 = y(r.y)
        val y1 = y(r.bottom)
        return Rect(minOf(x0, x1), minOf(y0, y1), abs(x1 - x0), abs(y1 - y0))
    }

    /** `this ∘ other`: applies [other] first, then this. */
    operator fun times(o: Transform2D): Transform2D =
        if (o.isIdentity) this else if (isIdentity) o else Transform2D(sx * o.sx, sy * o.sy, sx * o.tx + tx, sy * o.ty + ty)

    companion object {
        val IDENTITY = Transform2D(1f, 1f, 0f, 0f)

        /**
         * The element's own `transform` for a border box at ([x], [y]) with size [w]×[h] (all before transforming),
         * applied around its `transform-origin`. CSS order: `translate(10px) scale(2)` scales first, then translates.
         */
        fun of(style: ComputedStyle, x: Float, y: Float, w: Float, h: Float): Transform2D {
            val fns = style.transform
            if (fns.isEmpty()) return IDENTITY
            var m = IDENTITY
            for (f in fns) {
                m *= when (f) {
                    is TransformFn.Translate -> Transform2D(1f, 1f, f.x.resolve(w) ?: 0f, f.y.resolve(h) ?: 0f)
                    is TransformFn.Scale -> Transform2D(f.x, f.y, 0f, 0f)
                }
            }
            if (m.isIdentity) return IDENTITY
            val origin = style.transformOrigin
            val ox = x + (origin.x.resolve(w) ?: (w / 2f))
            val oy = y + (origin.y.resolve(h) ?: (h / 2f))
            return Transform2D(1f, 1f, ox, oy) * m * Transform2D(1f, 1f, -ox, -oy)
        }
    }
}
