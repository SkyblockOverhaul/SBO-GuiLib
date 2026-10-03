package net.sbo.guilib.core.layout

import net.sbo.guilib.core.css.ComputedStyle

/** What the layout engine needs from a node. Implemented by DOM elements/text nodes (and by test fakes). */
interface LayoutNode {
    /** Computed style. Text nodes return their parent's style. */
    val style: ComputedStyle
    val layoutChildren: List<LayoutNode>
    /** Non-null for text nodes. May contain `§` formatting codes. */
    val textContent: String? get() = null
    /** False when [textContent] is shown literally, `§` included (text inputs). */
    val formattingCodes: Boolean get() = true
    /** Natural size of replaced content (images, items); `null` for normal elements. */
    val intrinsicWidth: Float? get() = null
    val intrinsicHeight: Float? get() = null
    /** Auto height in lines of text (`<textarea rows>`) instead of the children's height; `null` = normal. */
    val rows: Int? get() = null
    /** Table cells: columns spanned (`colspan`); `<col>` / `<colgroup>`: columns covered (`span`). */
    val colSpan: Int get() = 1
    /** Table cells: rows spanned (`rowspan`); 0 = to the end of the row group, like HTML. */
    val rowSpan: Int get() = 1
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

    /** For `display: inline` elements: baseline raise (px) from their own and their inline ancestors' `vertical-align`. */
    var baselineShift = 0f

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
        baselineShift = 0f
        baseline = null
        visible = true
    }

    // ---- incremental layout (owned by LayoutEngine) ----

    /** True when this node, its style or something inside it changed since it was last laid out. */
    internal var dirty = true

    /** Results of earlier layouts of this (clean) node, by their inputs. */
    internal var cache: LayoutCache? = null

    /** Min-/max-content widths (`[min, max]`) and the content-min of flex items, valid for [intrinsicEpoch]. */
    internal var intrinsic: FloatArray? = null
    internal var contentMin = Float.NaN
    internal var intrinsicEpoch = -1

    /** Marks this box as changed: its cached layout and intrinsic sizes are no longer valid. */
    internal fun markDirty() {
        dirty = true
        cache = null
        intrinsic = null
        contentMin = Float.NaN
    }

    override fun toString() = "LayoutBox(x=$x, y=$y, w=$width, h=$height)"
}

/** The inputs of one `layoutNode` call; equal inputs on an unchanged subtree give the same result. */
internal data class LayoutKey(
    val cbWidth: Float, val cbHeight: Float?, val shrink: Boolean, val avail: Float,
    val forcedWidth: Float?, val forcedHeight: Float?, val cellShift: Float?,
)

/** What a `layoutNode` call decides for the node's own box (its descendants are in their own boxes). */
internal class LayoutSnapshot(box: LayoutBox) {
    private val width = box.width
    private val height = box.height
    private val baseline = box.baseline
    private val scrollWidth = box.scrollWidth
    private val scrollHeight = box.scrollHeight
    private val edges = floatArrayOf(
        box.margin.top, box.margin.right, box.margin.bottom, box.margin.left,
        box.padding.top, box.padding.right, box.padding.bottom, box.padding.left,
        box.border.top, box.border.right, box.border.bottom, box.border.left,
    )

    fun restore(box: LayoutBox) {
        box.x = 0f; box.y = 0f
        box.width = width; box.height = height
        box.baseline = baseline
        box.scrollWidth = scrollWidth; box.scrollHeight = scrollHeight
        box.margin.set(edges[0], edges[1], edges[2], edges[3])
        box.padding.set(edges[4], edges[5], edges[6], edges[7])
        box.border.set(edges[8], edges[9], edges[10], edges[11])
        box.inParagraph = false
        box.baselineShift = 0f
        box.visible = true
    }
}

internal class LayoutCache(val epoch: Int) {
    /** The inputs the node's descendants are currently laid out for. */
    var subtreeKey: LayoutKey? = null
    /** Own-box results per inputs (a flex item is measured and then laid out with other inputs every pass). */
    val results = LinkedHashMap<LayoutKey, LayoutSnapshot>()
    /** Set when a size-only hit left the descendants laid out for [subtreeKey] instead; fixed up after the pass. */
    var staleKey: LayoutKey? = null

    fun remember(key: LayoutKey, snapshot: LayoutSnapshot) {
        results.remove(key)
        results[key] = snapshot
        if (results.size > MAX_RESULTS) results.remove(results.keys.first())
    }

    companion object {
        const val MAX_RESULTS = 6
    }
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

    /** [shift]: how far the baseline is raised (px, negative = lowered) by `vertical-align` of inline ancestors. */
    class Text(
        override val x: Float, override val width: Float, val text: String, val style: TextStyle, override val owner: LayoutNode,
        val shift: Float = 0f,
    ) : Fragment
    class Box(override val x: Float, override val width: Float, val y: Float, override val owner: LayoutNode) : Fragment
    /**
     * Horizontal margin + border + padding at the [start] (or end) of the `display: inline` element [owner]; only
     * present on the line where that element starts (ends).
     */
    class Edge(override val x: Float, override val width: Float, override val owner: LayoutNode, val start: Boolean) : Fragment
}
