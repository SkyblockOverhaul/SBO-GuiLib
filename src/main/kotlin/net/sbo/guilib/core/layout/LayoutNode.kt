package net.sbo.guilib.core.layout

import net.sbo.guilib.core.css.ComputedStyle

/** What the layout engine needs from a node. Implemented by DOM elements/text nodes (and by test fakes). */
interface LayoutNode {
    /** Computed style. Text nodes return their parent's style. */
    val style: ComputedStyle
    val layoutChildren: List<LayoutNode>
    /** Non-null for text nodes. May contain `§` formatting codes. */
    val textContent: String? get() = null
    /** Natural size of replaced content (images, items); `null` for normal elements. */
    val intrinsicWidth: Float? get() = null
    val intrinsicHeight: Float? get() = null
    /** True for `<br>`: forces a line break inside inline content. */
    val isLineBreak: Boolean get() = false
    /** Where the layout result is written. */
    val box: LayoutBox
}

data class Edges(var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f, var left: Float = 0f) {
    val horizontal get() = left + right
    val vertical get() = top + bottom

    fun set(t: Float, r: Float, b: Float, l: Float) {
        top = t; right = r; bottom = b; left = l
    }
}

/**
 * Layout result of one node. [x]/[y] is the border box position relative to the parent's border box
 * (not including the parent's scroll offset), [width]/[height] the border box size.
 */
class LayoutBox {
    var x = 0f
    var y = 0f
    var width = 0f
    var height = 0f
    val margin = Edges()
    val border = Edges()
    val padding = Edges()

    /** Size of the content including overflowing children, relative to the padding box. Used for scrolling. */
    var scrollWidth = 0f
    var scrollHeight = 0f

    /** Distance from the top of the border box to the first text baseline, or `null` if there is none. */
    var baseline: Float? = null

    /** Inline content (text and inline elements) laid out inside this box. */
    val paragraphs = ArrayList<Paragraph>()

    /** True for inline elements/text nodes whose content was placed into an ancestor's [paragraphs]. */
    var inParagraph = false

    /** True if this node takes part in layout (not `display: none`). */
    var visible = true

    val contentX get() = border.left + padding.left
    val contentY get() = border.top + padding.top
    val contentWidth get() = (width - border.horizontal - padding.horizontal).coerceAtLeast(0f)
    val contentHeight get() = (height - border.vertical - padding.vertical).coerceAtLeast(0f)
    val paddingBoxWidth get() = width - border.horizontal
    val paddingBoxHeight get() = height - border.vertical
    val marginBoxWidth get() = width + margin.horizontal
    val marginBoxHeight get() = height + margin.vertical

    internal fun reset() {
        x = 0f; y = 0f; width = 0f; height = 0f
        paragraphs.clear()
        inParagraph = false
        baseline = null
        visible = true
    }

    override fun toString() = "LayoutBox(x=$x, y=$y, w=$width, h=$height)"
}

/** A block of wrapped inline content. Coordinates are relative to the owning box's border box. */
class Paragraph(val x: Float, val y: Float, val width: Float, val lines: List<Line>) {
    val height get() = lines.sumOf { it.height.toDouble() }.toFloat()
}

class Line(val y: Float, val width: Float, val height: Float, val baseline: Float, val fragments: List<Fragment>)

/** Positioned piece of a line. [x] is relative to the paragraph, [y] (for boxes: top) relative to the line top. */
sealed interface Fragment {
    val x: Float
    val width: Float
    /** The DOM node this fragment comes from (a text node or an atomic inline element). */
    val owner: LayoutNode

    class Text(override val x: Float, override val width: Float, val text: String, val style: TextStyle, override val owner: LayoutNode) : Fragment
    class Box(override val x: Float, override val width: Float, val y: Float, override val owner: LayoutNode) : Fragment
}
