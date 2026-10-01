package net.sbo.guilib.core.dom

import net.sbo.guilib.core.css.ComputedStyle
import net.sbo.guilib.core.css.TransformFn
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 2D affine transform `x' = a·x + c·y + tx`, `y' = b·x + d·y + ty` (the CSS `matrix(a, b, c, d, tx, ty)`):
 * what CSS `transform` lists reduce to. Used by the painter (drawing and hit regions) and by
 * [Element.getBoundingClientRect].
 *
 * The painter treats [isAxisAligned] transforms (translate and positive scale) specially: they are applied to the
 * coordinates directly, so boxes stay pixel-exact and text is re-rasterized sharp. Everything else (rotate, skew,
 * mirroring) is handed to the backend as a matrix.
 */
data class Transform2D(
    val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float,
) {
    /** Axis-aligned constructor `(sx·x + tx, sy·y + ty)`. */
    constructor(sx: Float, sy: Float, tx: Float, ty: Float) : this(sx, 0f, 0f, sy, tx, ty)

    val sx get() = a
    val sy get() = d

    val isIdentity get() = a == 1f && b == 0f && c == 0f && d == 1f && tx == 0f && ty == 0f

    /** Only translation and positive scaling: rectangles map to rectangles with the same orientation. */
    val isAxisAligned get() = abs(b) < EPS && abs(c) < EPS && a > 0f && d > 0f

    /** True if the transform collapses everything to (almost) nothing, e.g. `scale(0)`. */
    val isDegenerate get() = abs(a * d - b * c) < 1e-8f || scaleX < 1e-4f || scaleY < 1e-4f

    /** Length of a unit step along x / y after transforming. */
    val scaleX get() = sqrt(a * a + b * b)
    val scaleY get() = sqrt(c * c + d * d)

    /** Factor for sizes that must stay round (corner radii): the smaller axis scale. */
    val scale get() = minOf(scaleX, scaleY)

    /** Mapped x / y of the point ([px], [py]). */
    fun x(px: Float, py: Float = 0f) = a * px + c * py + tx
    fun y(py: Float, px: Float = 0f) = b * px + d * py + ty

    /** Axis-aligned bounding box of [r] after mapping (exact for axis-aligned transforms). */
    fun map(r: Rect): Rect {
        if (isIdentity) return r
        val xs = floatArrayOf(x(r.x, r.y), x(r.right, r.y), x(r.right, r.bottom), x(r.x, r.bottom))
        val ys = floatArrayOf(y(r.y, r.x), y(r.y, r.right), y(r.bottom, r.right), y(r.bottom, r.x))
        val x0 = xs.min()
        val y0 = ys.min()
        return Rect(x0, y0, xs.max() - x0, ys.max() - y0)
    }

    /** The inverse transform, or `null` if it collapses the plane. */
    fun inverse(): Transform2D? {
        val det = a * d - b * c
        if (abs(det) < 1e-12f) return null
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Transform2D(ia, ib, ic, id, -(ia * tx + ic * ty), -(ib * tx + id * ty))
    }

    /** `this ∘ other`: applies [o] first, then this. */
    operator fun times(o: Transform2D): Transform2D =
        if (o.isIdentity) this else if (isIdentity) o else Transform2D(
            a * o.a + c * o.b, b * o.a + d * o.b,
            a * o.c + c * o.d, b * o.c + d * o.d,
            a * o.tx + c * o.ty + tx, b * o.tx + d * o.ty + ty,
        )

    companion object {
        val IDENTITY = Transform2D(1f, 0f, 0f, 1f, 0f, 0f)
        private const val EPS = 1e-5f

        private fun rad(deg: Float) = deg * PI.toFloat() / 180f

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
                    is TransformFn.Rotate -> {
                        val r = rad(f.deg)
                        val cs = cos(r)
                        val sn = sin(r)
                        // Snap the exact multiples of 90° so e.g. rotate(180deg) has no -8e-8 noise.
                        fun clean(v: Float) = if (abs(v) < 1e-6f) 0f else v
                        Transform2D(clean(cs), clean(sn), clean(-sn), clean(cs), 0f, 0f)
                    }
                    is TransformFn.Skew -> Transform2D(1f, tan(rad(f.y)), tan(rad(f.x)), 1f, 0f, 0f)
                    is TransformFn.Matrix -> Transform2D(f.a, f.b, f.c, f.d, f.tx, f.ty)
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
