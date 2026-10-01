package net.sbo.guilib.core.layout

import net.sbo.guilib.core.css.AlignItems
import net.sbo.guilib.core.css.AlignSelf
import net.sbo.guilib.core.css.BoxSizing
import net.sbo.guilib.core.css.ComputedStyle
import net.sbo.guilib.core.css.Dim
import net.sbo.guilib.core.css.Display
import net.sbo.guilib.core.css.FlexWrap
import net.sbo.guilib.core.css.JustifyContent
import net.sbo.guilib.core.css.Position
import net.sbo.guilib.core.css.WhiteSpace

/**
 * Computes boxes for a node tree: box model, block flow, inline paragraphs, flexbox (CSS Flexbox Level 1,
 * simplified: no `align-content`, baseline alignment approximated) and relative/absolute/fixed positioning.
 *
 * All sizes are in GUI pixels. `box-sizing` defaults to `border-box`.
 */
class LayoutEngine(val measurer: TextMeasurer) {

    private val inline = InlineLayout(this)
    private val grid = GridLayout(this)
    private val intrinsicCache = HashMap<LayoutNode, FloatArray>()
    internal val parentOf = HashMap<LayoutNode, LayoutNode>()

    private class Pending(val node: LayoutNode, val parent: LayoutNode, val staticX: Float, val staticY: Float)
    private val cbStack = ArrayDeque<LinkedHashMap<LayoutNode, Pending>>()
    private var root: LayoutNode? = null
    private var viewportWidth = 0f
    private var viewportHeight = 0f

    /** Lays out [root] so that it exactly fills the viewport (the root always has the viewport's size). */
    fun layout(root: LayoutNode, viewportWidth: Float, viewportHeight: Float) {
        intrinsicCache.clear()
        contentMinCache.clear()
        parentOf.clear()
        cbStack.clear()
        this.root = root
        this.viewportWidth = viewportWidth
        this.viewportHeight = viewportHeight
        layoutNode(root, viewportWidth, viewportHeight, WidthMode.FILL, viewportWidth, viewportWidth, viewportHeight)
        root.box.x = 0f
        root.box.y = 0f
    }

    internal enum class WidthMode { FILL, SHRINK }

    // ---- box model helpers ---------------------------------------------------------------------------------------

    private fun Dim.px(base: Float?): Float = resolve(base) ?: 0f

    internal fun computeEdges(node: LayoutNode, cbWidth: Float) {
        val s = node.style
        val b = node.box
        // Percent margins and paddings resolve against the containing block *width* on every side, like the web.
        b.margin.set(s.marginTop.px(cbWidth), s.marginRight.px(cbWidth), s.marginBottom.px(cbWidth), s.marginLeft.px(cbWidth))
        b.padding.set(s.paddingTop.px(cbWidth), s.paddingRight.px(cbWidth), s.paddingBottom.px(cbWidth), s.paddingLeft.px(cbWidth))
        if (node.textContent != null) {
            // Text nodes share their parent's style but have no box decorations of their own.
            b.margin.set(0f, 0f, 0f, 0f); b.padding.set(0f, 0f, 0f, 0f); b.border.set(0f, 0f, 0f, 0f)
            return
        }
        b.border.set(s.borderTopWidth, s.borderRightWidth, s.borderBottomWidth, s.borderLeftWidth)
    }

    /** Converts a specified width/height to a border-box size, honouring `box-sizing`. */
    internal fun toBorderBox(value: Float, s: ComputedStyle, pb: Float) = if (s.boxSizing == BoxSizing.CONTENT_BOX) value + pb else value

    private fun specifiedWidth(node: LayoutNode, cbWidth: Float?): Float? {
        if (node.textContent != null) return null
        val s = node.style
        val pb = node.box.padding.horizontal + node.box.border.horizontal
        return s.width.resolve(cbWidth)?.let { toBorderBox(it, s, pb) }
    }

    private fun specifiedHeight(node: LayoutNode, cbHeight: Float?): Float? {
        if (node.textContent != null) return null
        val s = node.style
        val pb = node.box.padding.vertical + node.box.border.vertical
        return s.height.resolve(cbHeight)?.let { toBorderBox(it, s, pb) }
    }

    internal fun clampWidth(node: LayoutNode, w: Float, cbWidth: Float?): Float {
        if (node.textContent != null) return w
        val s = node.style
        val pb = node.box.padding.horizontal + node.box.border.horizontal
        var r = w
        s.maxWidth.resolve(cbWidth)?.let { r = minOf(r, toBorderBox(it, s, pb)) }
        s.minWidth.resolve(cbWidth)?.let { r = maxOf(r, toBorderBox(it, s, pb)) }
        return maxOf(r, pb)
    }

    internal fun clampHeight(node: LayoutNode, h: Float, cbHeight: Float?): Float {
        if (node.textContent != null) return h
        val s = node.style
        val pb = node.box.padding.vertical + node.box.border.vertical
        var r = h
        s.maxHeight.resolve(cbHeight)?.let { r = minOf(r, toBorderBox(it, s, pb)) }
        s.minHeight.resolve(cbHeight)?.let { r = maxOf(r, toBorderBox(it, s, pb)) }
        return maxOf(r, pb)
    }

    internal fun isOutOfFlow(node: LayoutNode) =
        node.textContent == null && (node.style.position == Position.ABSOLUTE || node.style.position == Position.FIXED)

    internal fun isHidden(node: LayoutNode) = node.textContent == null && node.style.display == Display.NONE

    internal fun isWhitespaceText(node: LayoutNode) =
        node.textContent?.let { it.isBlank() && (node.style.whiteSpace == WhiteSpace.NORMAL || node.style.whiteSpace == WhiteSpace.NOWRAP) } == true

    // ---- main entry --------------------------------------------------------------------------------------------

    /**
     * Lays out [node]: decides its border-box size and positions its descendants. The caller positions the node itself.
     * [avail] is the space available for the margin box in the inline direction.
     */
    internal fun layoutNode(
        node: LayoutNode,
        cbWidth: Float,
        cbHeight: Float?,
        mode: WidthMode,
        avail: Float,
        forcedWidth: Float? = null,
        forcedHeight: Float? = null,
    ) {
        val box = node.box
        box.reset()
        computeEdges(node, cbWidth)
        val s = node.style
        val pbH = box.padding.horizontal + box.border.horizontal
        val pbV = box.padding.vertical + box.border.vertical

        // Width.
        val iw = node.intrinsicWidth
        val ih = node.intrinsicHeight
        val specH = specifiedHeight(node, cbHeight)
        var width = forcedWidth ?: specifiedWidth(node, cbWidth) ?: when {
            iw != null -> {
                // Replaced element: keep the aspect ratio if only the height is given.
                val contentH = specH?.let { it - pbV }
                (if (contentH != null && ih != null && ih > 0f) contentH * iw / ih else iw) + pbH
            }
            mode == WidthMode.FILL -> avail - box.margin.horizontal
            else -> {
                val (min, max) = intrinsic(node)
                minOf(maxOf(min, avail - box.margin.horizontal), max)
            }
        }
        if (forcedWidth == null) width = clampWidth(node, width, cbWidth)
        box.width = maxOf(width, pbH)
        val contentWidth = box.contentWidth

        val definiteHeight = forcedHeight ?: specH
        val contentHeightDef = definiteHeight?.let { maxOf(it - pbV, 0f) }

        val establishesCb = node === root || (node.textContent == null && s.position != Position.STATIC)
        if (establishesCb) cbStack.addLast(LinkedHashMap())

        // Content.
        val contentHeight = when {
            node.textContent != null -> layoutText(node, contentWidth)
            iw != null -> if (ih != null && iw > 0f) contentWidth * ih / iw else 0f
            s.display.isFlex -> layoutFlex(node, contentWidth, contentHeightDef)
            s.display.isGrid -> grid.layout(node, contentWidth, contentHeightDef)
            else -> layoutBlockChildren(node, contentWidth, contentHeightDef)
        }

        var height = definiteHeight ?: (contentHeight + pbV)
        if (forcedHeight == null) height = clampHeight(node, height, cbHeight)
        box.height = maxOf(height, pbV)

        computeScrollSize(node)

        if (establishesCb) {
            val pending = cbStack.removeLast()
            for (p in pending.values) layoutAbsolute(p, node)
        }
    }

    /** Shrink-to-fit layout (inline-block, floats, absolutely positioned boxes). */
    internal fun layoutShrinkToFit(node: LayoutNode, avail: Float) = layoutNode(node, avail, null, WidthMode.SHRINK, avail)

    private fun layoutText(node: LayoutNode, width: Float): Float {
        val items = ArrayList<InlineLayout.Item>()
        inline.collect(listOf(node), items)
        node.box.inParagraph = false
        val p = inline.layout(node.style, items, width, 0f, 0f)
        node.box.paragraphs += p
        node.box.baseline = p.lines.firstOrNull()?.baseline
        return p.height
    }

    internal fun applyRelative(child: LayoutNode, cbWidth: Float, cbHeight: Float?) {
        if (child.textContent != null || child.style.position != Position.RELATIVE) return
        val s = child.style
        val dx = s.left.resolve(cbWidth) ?: s.right.resolve(cbWidth)?.let { -it } ?: 0f
        val dy = s.top.resolve(cbHeight) ?: s.bottom.resolve(cbHeight)?.let { -it } ?: 0f
        child.box.x += dx
        child.box.y += dy
    }

    internal fun registerAbsolute(child: LayoutNode, parent: LayoutNode, staticX: Float, staticY: Float) {
        val p = Pending(child, parent, staticX, staticY)
        if (child.style.position == Position.FIXED) cbStack.first()[child] = p else cbStack.last()[child] = p
    }

    // ---- block flow --------------------------------------------------------------------------------------------

    private fun layoutBlockChildren(node: LayoutNode, contentWidth: Float, contentHeight: Float?): Float {
        val box = node.box
        val cx = box.contentX
        val cy = box.contentY
        var y = cy
        val group = ArrayList<LayoutNode>()

        fun flush() {
            if (group.isEmpty()) return
            val items = ArrayList<InlineLayout.Item>()
            inline.collect(group, items)
            group.clear()
            val meaningful = items.any { it !is InlineLayout.Item.Text || it.text.isNotBlank() || node.style.whiteSpace == WhiteSpace.PRE || node.style.whiteSpace == WhiteSpace.PRE_WRAP }
            if (!meaningful) return
            val p = inline.layout(node.style, items, contentWidth, cx, y)
            if (box.baseline == null) p.lines.firstOrNull()?.let { box.baseline = y + it.baseline }
            box.paragraphs += p
            y += p.height
        }

        for (child in node.layoutChildren) {
            parentOf[child] = node
            if (isHidden(child)) {
                child.box.reset(); child.box.visible = false; continue
            }
            if (isOutOfFlow(child)) {
                registerAbsolute(child, node, cx, y); continue
            }
            if (InlineLayout.isInlineLevel(child)) {
                group += child; continue
            }
            flush()
            layoutNode(child, contentWidth, contentHeight, WidthMode.FILL, contentWidth)
            val cb = child.box
            val s = child.style
            val free = contentWidth - cb.marginBoxWidth
            val mlAuto = s.marginLeft == Dim.Auto
            val mrAuto = s.marginRight == Dim.Auto
            if (free > 0f) when {
                mlAuto && mrAuto -> { cb.margin.left = free / 2f; cb.margin.right = free / 2f }
                mlAuto -> cb.margin.left = free
                mrAuto -> cb.margin.right = free
            }
            cb.x = cx + cb.margin.left
            cb.y = y + cb.margin.top
            if (box.baseline == null) cb.baseline?.let { box.baseline = cb.y + it }
            applyRelative(child, contentWidth, contentHeight)
            y += cb.marginBoxHeight
        }
        flush()
        return y - cy
    }

    // ---- flexbox -----------------------------------------------------------------------------------------------

    private class FlexItem(val node: LayoutNode) {
        var base = 0f          // flex base size (border box, main axis)
        var hypo = 0f          // hypothetical main size (clamped)
        var minMain = 0f
        var maxMain = Float.POSITIVE_INFINITY
        var target = 0f
        var frozen = false
        var marginMainStart = 0f
        var marginMainEnd = 0f
        var marginCrossStart = 0f
        var marginCrossEnd = 0f
        val outerHypo get() = hypo + marginMainStart + marginMainEnd
        val outerTarget get() = target + marginMainStart + marginMainEnd
        var cross = 0f
        val outerCross get() = cross + marginCrossStart + marginCrossEnd
    }

    private fun layoutFlex(node: LayoutNode, contentWidth: Float, contentHeight: Float?): Float {
        val s = node.style
        val box = node.box
        val row = s.flexDirection.isRow
        val mainSize: Float? = if (row) contentWidth else contentHeight
        val crossSize: Float? = if (row) contentHeight else contentWidth
        val gapMain = (if (row) s.columnGap else s.rowGap).resolve(mainSize) ?: 0f
        val gapCross = (if (row) s.rowGap else s.columnGap).resolve(crossSize) ?: 0f
        val cx = box.contentX
        val cy = box.contentY

        val items = ArrayList<FlexItem>()
        for (child in node.layoutChildren) {
            parentOf[child] = node
            if (isHidden(child)) {
                child.box.reset(); child.box.visible = false; continue
            }
            if (isOutOfFlow(child)) {
                registerAbsolute(child, node, cx, cy); continue
            }
            if (isWhitespaceText(child)) {
                child.box.reset(); child.box.visible = false; continue
            }
            items += FlexItem(child)
        }
        items.sortBy { if (it.node.textContent != null) 0 else it.node.style.order } // stable

        // 1. Flex base size and hypothetical main size.
        for (item in items) {
            val n = item.node
            val ns = n.style
            computeEdges(n, contentWidth)
            val b = n.box
            val pbMain = if (row) b.padding.horizontal + b.border.horizontal else b.padding.vertical + b.border.vertical
            if (row) {
                item.marginMainStart = b.margin.left; item.marginMainEnd = b.margin.right
                item.marginCrossStart = b.margin.top; item.marginCrossEnd = b.margin.bottom
            } else {
                item.marginMainStart = b.margin.top; item.marginMainEnd = b.margin.bottom
                item.marginCrossStart = b.margin.left; item.marginCrossEnd = b.margin.right
            }
            val isText = n.textContent != null
            val basis = if (isText) Dim.Auto else ns.flexBasis
            val sizeProp = if (isText) Dim.Auto else if (row) ns.width else ns.height
            val definiteBasis = basis.resolve(mainSize)?.let { toBorderBox(it, ns, pbMain) }
                ?: if (basis == Dim.Auto) sizeProp.resolve(mainSize)?.let { toBorderBox(it, ns, pbMain) } else null
            item.base = definiteBasis ?: contentMainSize(item, row, contentWidth, crossSize)

            // min/max in the main axis; `min-*: auto` is content-based for flex items (if overflow is visible).
            val minProp = if (isText) Dim.Auto else if (row) ns.minWidth else ns.minHeight
            val maxProp = if (isText) Dim.None else if (row) ns.maxWidth else ns.maxHeight
            item.maxMain = maxProp.resolve(mainSize)?.let { toBorderBox(it, ns, pbMain) } ?: Float.POSITIVE_INFINITY
            item.minMain = minProp.resolve(mainSize)?.let { toBorderBox(it, ns, pbMain) } ?: run {
                val overflowVisible = isText || !(if (row) ns.overflowX else ns.overflowY).clips
                if (minProp == Dim.Auto && overflowVisible) {
                    val contentMin = if (row) intrinsicContentMin(n) else contentMainSize(item, false, contentWidth, crossSize)
                    val specified = sizeProp.resolve(mainSize)?.let { toBorderBox(it, ns, pbMain) }
                    minOf(contentMin, specified ?: Float.POSITIVE_INFINITY, item.maxMain)
                } else pbMain
            }
            item.hypo = item.base.coerceIn(item.minMain, maxOf(item.minMain, item.maxMain))
        }

        // 2. Collect lines.
        val wrap = s.flexWrap != FlexWrap.NOWRAP && mainSize != null
        val lines = ArrayList<MutableList<FlexItem>>()
        run {
            var cur = ArrayList<FlexItem>()
            var used = 0f
            for (item in items) {
                val add = item.outerHypo + if (cur.isEmpty()) 0f else gapMain
                if (wrap && cur.isNotEmpty() && used + add > mainSize + 0.01f) {
                    lines += cur; cur = ArrayList(); used = 0f
                    cur += item; used = item.outerHypo
                } else {
                    cur += item; used += add
                }
            }
            if (cur.isNotEmpty() || lines.isEmpty()) lines += cur
        }

        // 3. Resolve flexible lengths.
        for (line in lines) resolveFlexibleLengths(line, mainSize, gapMain)

        // 4. Cross sizes (hypothetical), then line cross sizes, then stretch.
        val singleLine = s.flexWrap == FlexWrap.NOWRAP
        val lineCross = FloatArray(lines.size)
        for ((li, line) in lines.withIndex()) {
            var maxBaseline = 0f
            for (item in line) {
                layoutFlexItem(item, row, contentWidth, contentHeight, null)
                if (alignOf(item.node, s) == AlignItems.BASELINE) maxBaseline = maxOf(maxBaseline, item.marginCrossStart + (item.node.box.baseline ?: item.cross))
            }
            lineCross[li] = if (singleLine && crossSize != null) crossSize else {
                var m = 0f
                for (item in line) {
                    val extra = if (alignOf(item.node, s) == AlignItems.BASELINE) maxBaseline - (item.marginCrossStart + (item.node.box.baseline ?: item.cross)) else 0f
                    m = maxOf(m, item.outerCross + extra)
                }
                m
            }
            for (item in line) {
                val ns = item.node.style
                val crossAuto = item.node.textContent != null || (if (row) ns.height else ns.width) == Dim.Auto
                val crossMarginAuto = item.node.textContent == null && (if (row) ns.marginTop == Dim.Auto || ns.marginBottom == Dim.Auto else ns.marginLeft == Dim.Auto || ns.marginRight == Dim.Auto)
                if (alignOf(item.node, s) == AlignItems.STRETCH && crossAuto && !crossMarginAuto) {
                    val stretched = lineCross[li] - item.marginCrossStart - item.marginCrossEnd
                    val clamped = if (row) clampHeight(item.node, stretched, contentHeight) else clampWidth(item.node, stretched, contentWidth)
                    if (kotlin.math.abs(clamped - item.cross) > 0.001f || row) layoutFlexItem(item, row, contentWidth, contentHeight, clamped)
                }
            }
        }

        // 5. Main-axis alignment and positioning.
        val reverse = s.flexDirection.isReverse
        var crossOffset = 0f
        var usedMainMax = 0f
        for ((li, line) in lines.withIndex()) {
            val used = line.sumOf { it.outerTarget.toDouble() }.toFloat() + gapMain * (line.size - 1).coerceAtLeast(0)
            usedMainMax = maxOf(usedMainMax, used)
            val container = mainSize ?: used
            var free = container - used

            val autoMargins = line.sumOf { item -> mainAutoMargins(item.node, row) }
            var start = 0f
            var between = gapMain
            if (free > 0f && autoMargins > 0) {
                val each = free / autoMargins
                for (item in line) {
                    val ns = item.node.style
                    if (item.node.textContent != null) continue
                    if ((if (row) ns.marginLeft else ns.marginTop) == Dim.Auto) item.marginMainStart += each
                    if ((if (row) ns.marginRight else ns.marginBottom) == Dim.Auto) item.marginMainEnd += each
                }
                free = 0f
            } else {
                val n = line.size
                when (s.justifyContent) {
                    JustifyContent.FLEX_START -> {}
                    JustifyContent.FLEX_END -> start = free
                    JustifyContent.CENTER -> start = free / 2f
                    JustifyContent.SPACE_BETWEEN -> if (free > 0f && n > 1) between += free / (n - 1)
                    JustifyContent.SPACE_AROUND -> if (free > 0f) { between += free / n; start = free / n / 2f } else start = free / 2f
                    JustifyContent.SPACE_EVENLY -> if (free > 0f) { between += free / (n + 1); start = free / (n + 1) } else start = free / 2f
                }
            }

            var pos = if (reverse) container - start else start
            var maxBaseline = 0f
            for (item in line) if (alignOf(item.node, s) == AlignItems.BASELINE) maxBaseline = maxOf(maxBaseline, item.marginCrossStart + (item.node.box.baseline ?: item.cross))

            for (item in line) {
                val b = item.node.box
                val mainPos = if (reverse) {
                    pos -= item.outerTarget
                    val p = pos + item.marginMainStart
                    pos -= between
                    p
                } else {
                    val p = pos + item.marginMainStart
                    pos += item.outerTarget + between
                    p
                }
                // Cross axis.
                val ns = item.node.style
                val lc = lineCross[li]
                val freeCross = lc - item.outerCross
                val startAuto = item.node.textContent == null && (if (row) ns.marginTop else ns.marginLeft) == Dim.Auto
                val endAuto = item.node.textContent == null && (if (row) ns.marginBottom else ns.marginRight) == Dim.Auto
                val crossInLine = when {
                    startAuto && endAuto -> freeCross / 2f
                    startAuto -> freeCross
                    endAuto -> 0f
                    else -> when (alignOf(item.node, s)) {
                        AlignItems.FLEX_START, AlignItems.STRETCH -> 0f
                        AlignItems.FLEX_END -> freeCross
                        AlignItems.CENTER -> freeCross / 2f
                        AlignItems.BASELINE -> maxBaseline - (item.marginCrossStart + (b.baseline ?: item.cross))
                    }
                } + item.marginCrossStart
                if (row) {
                    b.x = cx + mainPos; b.y = cy + crossOffset + crossInLine
                } else {
                    b.x = cx + crossOffset + crossInLine; b.y = cy + mainPos
                }
                // Keep auto margins as resolved by the flex algorithm (layoutNode reset them to 0).
                if (row) {
                    b.margin.left = item.marginMainStart; b.margin.right = item.marginMainEnd
                } else {
                    b.margin.top = item.marginMainStart; b.margin.bottom = item.marginMainEnd
                }
                applyRelative(item.node, contentWidth, contentHeight)
                if (box.baseline == null && li == 0) b.baseline?.let { box.baseline = b.y + it }
            }
            crossOffset += lineCross[li] + gapCross
        }
        val totalCross = (lineCross.sum() + gapCross * (lines.size - 1).coerceAtLeast(0))
        return if (row) totalCross else usedMainMax
    }

    private fun mainAutoMargins(node: LayoutNode, row: Boolean): Int {
        if (node.textContent != null) return 0
        val s = node.style
        val (a, b) = if (row) s.marginLeft to s.marginRight else s.marginTop to s.marginBottom
        return (if (a == Dim.Auto) 1 else 0) + (if (b == Dim.Auto) 1 else 0)
    }

    internal fun alignOf(node: LayoutNode, container: ComputedStyle): AlignItems {
        if (node.textContent != null) return container.alignItems
        return when (node.style.alignSelf) {
            AlignSelf.AUTO -> container.alignItems
            AlignSelf.STRETCH -> AlignItems.STRETCH
            AlignSelf.FLEX_START -> AlignItems.FLEX_START
            AlignSelf.FLEX_END -> AlignItems.FLEX_END
            AlignSelf.CENTER -> AlignItems.CENTER
            AlignSelf.BASELINE -> AlignItems.BASELINE
        }
    }

    /** Content-based main size of a flex item (max-content width for rows, laid-out height for columns). */
    private fun contentMainSize(item: FlexItem, row: Boolean, contentWidth: Float, crossSize: Float?): Float {
        val n = item.node
        return if (row) intrinsic(n)[1]
        else {
            // Column: height depends on the width the item will get.
            layoutFlexCrossForColumn(n, contentWidth)
            n.box.height
        }
    }

    private fun layoutFlexCrossForColumn(n: LayoutNode, contentWidth: Float) {
        val parentStyle = parentOf[n]?.style
        val stretch = parentStyle != null && alignOf(n, parentStyle) == AlignItems.STRETCH &&
            (n.textContent != null || (n.style.width == Dim.Auto && n.style.marginLeft != Dim.Auto && n.style.marginRight != Dim.Auto))
        if (stretch) {
            computeEdges(n, contentWidth)
            val w = clampWidth(n, contentWidth - n.box.margin.horizontal, contentWidth)
            layoutNode(n, contentWidth, null, WidthMode.FILL, contentWidth, forcedWidth = w)
        } else {
            layoutNode(n, contentWidth, null, WidthMode.SHRINK, contentWidth)
        }
    }

    private fun layoutFlexItem(item: FlexItem, row: Boolean, contentWidth: Float, contentHeight: Float?, forcedCross: Float?) {
        val n = item.node
        if (row) {
            layoutNode(n, contentWidth, contentHeight, WidthMode.FILL, contentWidth, forcedWidth = item.target, forcedHeight = forcedCross)
            item.cross = n.box.height
        } else {
            val forcedW = forcedCross ?: run {
                val parentStyle = parentOf[n]?.style
                val stretch = parentStyle != null && alignOf(n, parentStyle) == AlignItems.STRETCH &&
                    (n.textContent != null || (n.style.width == Dim.Auto && n.style.marginLeft != Dim.Auto && n.style.marginRight != Dim.Auto))
                if (stretch) clampWidth(n, contentWidth - n.box.margin.horizontal, contentWidth) else null
            }
            if (forcedW != null) layoutNode(n, contentWidth, contentHeight, WidthMode.FILL, contentWidth, forcedWidth = forcedW, forcedHeight = item.target)
            else layoutNode(n, contentWidth, contentHeight, WidthMode.SHRINK, contentWidth, forcedHeight = item.target)
            item.cross = n.box.width
        }
        // layoutNode recomputed the edges; auto margins must stay as resolved by the flex algorithm.
    }

    /** CSS Flexbox §9.7 "Resolving Flexible Lengths". */
    private fun resolveFlexibleLengths(line: List<FlexItem>, mainSize: Float?, gap: Float) {
        if (mainSize == null) {
            for (i in line) i.target = i.hypo
            return
        }
        val gaps = gap * (line.size - 1).coerceAtLeast(0)
        val sumHypo = line.sumOf { it.outerHypo.toDouble() }.toFloat() + gaps
        val growing = sumHypo < mainSize
        fun factor(i: FlexItem) = if (i.node.textContent != null) (if (growing) 0f else 1f) else if (growing) i.node.style.flexGrow else i.node.style.flexShrink

        for (i in line) {
            i.target = i.hypo
            i.frozen = factor(i) == 0f || (growing && i.base > i.hypo) || (!growing && i.base < i.hypo)
        }
        fun freeSpace() = mainSize - gaps - line.sumOf { (if (it.frozen) it.outerTarget else it.base + it.marginMainStart + it.marginMainEnd).toDouble() }.toFloat()
        val initialFree = freeSpace()

        repeat(line.size + 1) {
            val unfrozen = line.filter { !it.frozen }
            if (unfrozen.isEmpty()) return
            var free = freeSpace()
            val sumFactors = unfrozen.sumOf { factor(it).toDouble() }.toFloat()
            if (sumFactors < 1f) {
                val scaled = initialFree * sumFactors
                if (kotlin.math.abs(scaled) < kotlin.math.abs(free)) free = scaled
            }
            if (growing) {
                for (i in unfrozen) i.target = i.base + if (sumFactors > 0f) free * factor(i) / sumFactors else 0f
            } else {
                val sumScaled = unfrozen.sumOf { (factor(it) * it.base).toDouble() }.toFloat()
                for (i in unfrozen) i.target = i.base + if (sumScaled > 0f) free * (factor(i) * i.base) / sumScaled else 0f
            }
            // Fix min/max violations.
            var total = 0f
            val violation = HashMap<FlexItem, Float>()
            for (i in unfrozen) {
                val clamped = i.target.coerceIn(i.minMain, maxOf(i.minMain, i.maxMain))
                violation[i] = clamped - i.target
                total += clamped - i.target
                i.target = clamped
            }
            when {
                kotlin.math.abs(total) < 0.001f -> unfrozen.forEach { it.frozen = true }
                total > 0f -> unfrozen.filter { violation.getValue(it) > 0f }.forEach { it.frozen = true }
                else -> unfrozen.filter { violation.getValue(it) < 0f }.forEach { it.frozen = true }
            }
        }
    }

    // ---- absolute positioning ----------------------------------------------------------------------------------

    private fun offsetWithin(node: LayoutNode, ancestor: LayoutNode): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var cur: LayoutNode? = node
        while (cur != null && cur !== ancestor) {
            x += cur.box.x; y += cur.box.y
            cur = parentOf[cur]
        }
        return x to y
    }

    private fun layoutAbsolute(p: Pending, cb: LayoutNode) {
        val node = p.node
        val s = node.style
        val fixed = s.position == Position.FIXED
        val cbBox = cb.box
        val cbW = if (fixed) viewportWidth else cbBox.paddingBoxWidth
        val cbH = if (fixed) viewportHeight else cbBox.paddingBoxHeight
        computeEdges(node, cbW)
        val m = node.box.margin
        val left = s.left.resolve(cbW)
        val right = s.right.resolve(cbW)
        val top = s.top.resolve(cbH)
        val bottom = s.bottom.resolve(cbH)

        val forcedW = if (s.width == Dim.Auto && left != null && right != null && node.intrinsicWidth == null)
            clampWidth(node, cbW - left - right - m.horizontal, cbW) else null
        val forcedH = if (s.height == Dim.Auto && top != null && bottom != null && node.intrinsicHeight == null)
            clampHeight(node, cbH - top - bottom - m.vertical, cbH) else null
        val availW = cbW - (left ?: 0f) - (right ?: 0f)
        layoutNode(node, cbW, cbH, WidthMode.SHRINK, availW, forcedW, forcedH)
        val b = node.box

        val (parentX, parentY) = if (fixed) offsetWithin(p.parent, root!!) else offsetWithin(p.parent, cb)
        val originX = if (fixed) 0f else cbBox.border.left
        val originY = if (fixed) 0f else cbBox.border.top
        val xInCb = when {
            left != null -> originX + left + m.left
            right != null -> originX + cbW - right - b.width - m.right
            else -> parentX + p.staticX + m.left
        }
        val yInCb = when {
            top != null -> originY + top + m.top
            bottom != null -> originY + cbH - bottom - b.height - m.bottom
            else -> parentY + p.staticY + m.top
        }
        b.x = xInCb - parentX
        b.y = yInCb - parentY
    }

    // ---- scroll size -------------------------------------------------------------------------------------------

    private fun computeScrollSize(node: LayoutNode) {
        val b = node.box
        var maxX = b.paddingBoxWidth
        var maxY = b.paddingBoxHeight
        for (child in node.layoutChildren) {
            val cb = child.box
            if (!cb.visible || cb.inParagraph) continue
            maxX = maxOf(maxX, cb.x + cb.width + cb.margin.right - b.border.left + b.padding.right)
            maxY = maxOf(maxY, cb.y + cb.height + cb.margin.bottom - b.border.top + b.padding.bottom)
        }
        for (p in b.paragraphs) {
            val w = p.lines.maxOfOrNull { it.width } ?: 0f
            maxX = maxOf(maxX, p.x + w - b.border.left + b.padding.right)
            maxY = maxOf(maxY, p.y + p.height - b.border.top + b.padding.bottom)
        }
        b.scrollWidth = maxX
        b.scrollHeight = maxY
    }

    // ---- intrinsic sizes ---------------------------------------------------------------------------------------

    /** Min-content and max-content border-box width of [node]. */
    internal fun intrinsic(node: LayoutNode): FloatArray = intrinsicCache.getOrPut(node) { computeIntrinsic(node) }

    /** Intrinsic sizes including (non-percentage) margins. */
    internal fun intrinsicOuter(node: LayoutNode): Pair<Float, Float> {
        val (min, max) = intrinsic(node)
        val s = node.style
        val m = if (node.textContent != null) 0f else ((s.marginLeft as? Dim.Px)?.px ?: 0f) + ((s.marginRight as? Dim.Px)?.px ?: 0f)
        return (min + m) to (max + m)
    }

    private operator fun FloatArray.component1() = this[0]
    private operator fun FloatArray.component2() = this[1]

    private val contentMinCache = HashMap<LayoutNode, Float>()

    /** Min-content width ignoring the node's own `width` (for the automatic minimum size of flex items). */
    private fun intrinsicContentMin(node: LayoutNode): Float = contentMinCache.getOrPut(node) { computeIntrinsic(node, ignoreWidth = true)[0] }

    private fun computeIntrinsic(node: LayoutNode, ignoreWidth: Boolean = false): FloatArray {
        val s = node.style
        if (node.textContent != null) {
            val items = ArrayList<InlineLayout.Item>()
            inline.collect(listOf(node), items, mark = false)
            val (min, max) = inline.intrinsic(s, items)
            return floatArrayOf(min, max)
        }
        fun px(d: Dim) = (d as? Dim.Px)?.px ?: 0f
        val pb = px(s.paddingLeft) + px(s.paddingRight) + s.borderLeftWidth + s.borderRightWidth
        if (!ignoreWidth) (s.width as? Dim.Px)?.let {
            val w = maxOf(toBorderBox(it.px, s, pb), pb)
            return floatArrayOf(w, w)
        }
        var min: Float
        var max: Float
        val iw = node.intrinsicWidth
        if (iw != null) {
            min = iw; max = iw
        } else if (s.display.isGrid) {
            val (a, b) = grid.intrinsic(node)
            min = a; max = b
        } else if (s.display.isFlex) {
            val children = node.layoutChildren.filter { !isHidden(it) && !isOutOfFlow(it) && !isWhitespaceText(it) }
            val outer = children.map { intrinsicOuter(it) }
            if (s.flexDirection.isRow) {
                val gap = px(s.columnGap) * (children.size - 1).coerceAtLeast(0)
                max = outer.sumOf { it.second.toDouble() }.toFloat() + gap
                min = if (s.flexWrap == FlexWrap.NOWRAP) outer.sumOf { it.first.toDouble() }.toFloat() + gap else outer.maxOfOrNull { it.first } ?: 0f
            } else {
                max = outer.maxOfOrNull { it.second } ?: 0f
                min = outer.maxOfOrNull { it.first } ?: 0f
            }
        } else {
            min = 0f; max = 0f
            val group = ArrayList<LayoutNode>()
            fun flush() {
                if (group.isEmpty()) return
                val items = ArrayList<InlineLayout.Item>()
                inline.collect(group, items, mark = false)
                group.clear()
                val (a, b) = inline.intrinsic(s, items)
                min = maxOf(min, a); max = maxOf(max, b)
            }
            for (child in node.layoutChildren) {
                if (isHidden(child) || isOutOfFlow(child)) continue
                if (InlineLayout.isInlineLevel(child)) {
                    group += child; continue
                }
                flush()
                val (a, b) = intrinsicOuter(child)
                min = maxOf(min, a); max = maxOf(max, b)
            }
            flush()
        }
        min += pb; max += pb
        (s.maxWidth as? Dim.Px)?.let { val m = toBorderBox(it.px, s, pb); min = minOf(min, m); max = minOf(max, m) }
        (s.minWidth as? Dim.Px)?.let { val m = toBorderBox(it.px, s, pb); min = maxOf(min, m); max = maxOf(max, m) }
        return floatArrayOf(min, maxOf(min, max))
    }
}
