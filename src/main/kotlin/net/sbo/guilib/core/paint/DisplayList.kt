package net.sbo.guilib.core.paint

import net.sbo.guilib.core.css.ObjectFit
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.Rect
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

    /** A run of text; [y] is the top of the glyph box (baseline − ascent). */
    class Text(val x: Float, val y: Float, val text: String, val style: TextStyle, val color: Int, val alpha: Float) : PaintCommand

    /** Image from a `src` URL/resource location, fitted into the rect. */
    class Image(
        val x: Float, val y: Float, val width: Float, val height: Float,
        val src: String, val fit: ObjectFit, val alpha: Float, val radii: FloatArray,
    ) : PaintCommand

    /** Replaced element the backend draws itself (e.g. `<item>`). */
    class Replaced(val element: Element, val x: Float, val y: Float, val width: Float, val height: Float, val alpha: Float) : PaintCommand

    class PushClip(val rect: Rect) : PaintCommand
    data object PopClip : PaintCommand
}

/** A clickable area, in paint order. [element] is the event target. */
class HitRegion(val element: Element, val rect: Rect, val clip: Rect?)
