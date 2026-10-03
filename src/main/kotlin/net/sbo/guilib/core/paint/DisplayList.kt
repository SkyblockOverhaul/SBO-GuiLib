package net.sbo.guilib.core.paint

import net.sbo.guilib.core.css.ObjectFit
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.Rect
import net.sbo.guilib.core.dom.Transform2D
import net.sbo.guilib.core.layout.TextStyle

/** Backend-independent drawing commands in absolute GUI coordinates. Colors are ARGB with opacity already applied. */
sealed interface PaintCommand {
    /**
     * Box with optional rounded corners and border. [radii] = top-left, top-right, bottom-right, bottom-left;
     * [borders] = top, right, bottom, left widths; [borderColors] in the same order.
     */
    class Box(
        val x: Float, val y: Float, val width: Float, val height: Float,
        val background: Int,
        val radii: FloatArray,
        val borders: FloatArray,
        val borderColors: IntArray,
    ) : PaintCommand {
        val hasRadius get() = radii.any { it > 0f }
        val hasBorder get() = borders.any { it > 0f }
    }

    /**
     * A `box-shadow` layer. The box [x], [y], [width], [height] with corner [radii] is the element's border box for
     * outer shadows (the shadow is never drawn inside it) and its padding box for [inset] shadows (drawn only inside).
     * The shadow shape is that box moved by [offsetX]/[offsetY] and grown by [spread] (shrunk for inset shadows),
     * blurred with a Gaussian of standard deviation [blur] / 2.
     *
     * [mode] [ShadowMode.PLAIN] and [ShadowMode.RING] are used by `filter`: the blurred shape itself, or the blurred
     * ring between the shape and the shape grown by [spread] (negative = the border width).
     */
    class Shadow(
        val x: Float, val y: Float, val width: Float, val height: Float, val radii: FloatArray,
        val offsetX: Float, val offsetY: Float, val blur: Float, val spread: Float, val color: Int, val inset: Boolean,
        val mode: ShadowMode = if (inset) ShadowMode.INSET else ShadowMode.OUTER,
    ) : PaintCommand {
        /** Area the shadow can paint (for outer shadows: the shifted, grown and blurred shape). */
        val bounds: Rect
            get() {
                if (mode == ShadowMode.INSET) return Rect(x, y, width, height)
                val e = spread.coerceAtLeast(0f) + blur * 1.5f + 1f
                return Rect(x + offsetX - e, y + offsetY - e, width + 2 * e, height + 2 * e)
            }
    }

    /** A run of text; [y] is the top of the glyph box (baseline − ascent). */
    class Text(val x: Float, val y: Float, val text: String, val style: TextStyle, val color: Int, val alpha: Float) : PaintCommand

    /**
     * Image from a `src` URL/resource location, fitted into the rect; [color] is `currentColor` for SVGs. [filters]
     * (from CSS `filter`) are applied to the image's pixels in order; blurred images paint beyond the rect.
     */
    class Image(
        val x: Float, val y: Float, val width: Float, val height: Float,
        val src: String, val fit: ObjectFit, val alpha: Float, val radii: FloatArray, val color: Int,
        val filters: List<ImageOp> = emptyList(),
    ) : PaintCommand

    /**
     * A gradient (or any smoothly colored shape) as colored triangles, clipped to the box [x], [y], [width], [height]
     * with rounded corners [radii]. The [mesh] is relative to the box's top-left corner (so it can be reused when the
     * box moves) and may be shared between commands.
     */
    class Gradient(
        val x: Float, val y: Float, val width: Float, val height: Float,
        val mesh: ColorMesh,
        val radii: FloatArray,
    ) : PaintCommand

    /** Replaced element the backend draws itself (e.g. `<item>`). */
    class Replaced(val element: Element, val x: Float, val y: Float, val width: Float, val height: Float, val alpha: Float) : PaintCommand

    /** Clip to [rect], always in screen coordinates (rotated clips use their bounding box). */
    class PushClip(val rect: Rect) : PaintCommand
    data object PopClip : PaintCommand

    /**
     * The following drawing commands are in local coordinates that [transform] maps to the screen
     * (`null` = screen coordinates). Emitted for rotated, skewed and mirrored elements.
     */
    class SetTransform(val transform: Transform2D?) : PaintCommand
}

/** How a [PaintCommand.Shadow] covers its box: see there. */
enum class ShadowMode { OUTER, INSET, PLAIN, RING }

/** A pixel operation on an image (from CSS `filter`), see [ImageFilters]. */
sealed interface ImageOp {
    data class Matrix(val matrix: net.sbo.guilib.core.css.ColorMatrix) : ImageOp

    /** Gaussian blur with standard deviation [sigma] in GUI px. */
    data class Blur(val sigma: Float) : ImageOp

    /** Every pixel becomes [color] with its alpha multiplied by the pixel's alpha (the shape of a `drop-shadow`). */
    data class Silhouette(val color: Int) : ImageOp
}

/**
 * A clickable area, in paint order. [element] is the event target. [local] is mapped to the screen by [transform];
 * [rect] is its screen bounding box.
 */
class HitRegion(val element: Element, val local: Rect, val clip: Rect?, val transform: Transform2D = Transform2D.IDENTITY) {
    private val inverse = if (transform.isAxisAligned) null else transform.inverse()
    val rect: Rect = transform.map(local)

    fun contains(x: Float, y: Float): Boolean {
        if (clip != null && !clip.contains(x, y)) return false
        if (inverse == null) return rect.contains(x, y)
        return local.contains(inverse.x(x, y), inverse.y(y, x))
    }
}
