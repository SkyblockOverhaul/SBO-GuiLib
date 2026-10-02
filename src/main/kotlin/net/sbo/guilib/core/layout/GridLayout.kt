package net.sbo.guilib.core.layout

import net.sbo.guilib.core.css.AlignItems
import net.sbo.guilib.core.css.AlignSelf
import net.sbo.guilib.core.css.ComputedStyle
import net.sbo.guilib.core.css.Dim
import net.sbo.guilib.core.css.GridLine
import net.sbo.guilib.core.css.JustifyContent
import net.sbo.guilib.core.css.TrackList
import net.sbo.guilib.core.css.TrackSize
import kotlin.math.floor

/**
 * CSS Grid layout (a practical subset of CSS Grid Level 1): explicit and implicit tracks, `fr`, `minmax()`,
 * `repeat()` incl. `auto-fill`/`auto-fit`, line/span/area placement, auto-placement (row/column, dense),
 * gaps, `justify-items/self`, `align-items/self` and `justify-content`.
 *
 * Simplifications: `auto-fit` doesn't collapse empty tracks (behaves like `auto-fill`), no `align-content`,
 * no subgrid, no baseline alignment, no negative implicit lines (items can't be placed before line 1).
 */
internal class GridLayout(private val e: LayoutEngine) {

    private class Item(val node: LayoutNode) {
        var row = 0
        var rowSpan = 1
        var col = 0
        var colSpan = 1
    }

    private class Placement(val items: List<Item>, val rows: Int, val cols: Int)

    // ---- placement ---------------------------------------------------------------------------------------------

    /** Number of explicit tracks after expanding `repeat(auto-fill, …)` for the available size. */
    private fun expand(list: TrackList, avail: Float?, gap: Float): List<TrackSize> {
        val repeat = list.autoRepeat ?: return list.tracks
        fun fixedSize(t: TrackSize): Float? = when (t) {
            is TrackSize.Fixed -> (t.size as Dim).resolve(avail)
            is TrackSize.MinMax -> fixedSize(t.max) ?: fixedSize(t.min)
            else -> null
        }
        var count = 1
        if (avail != null) {
            val unit = repeat.sumOf { (fixedSize(it) ?: 0f).toDouble() }.toFloat() + gap * repeat.size
            val others = list.tracks.sumOf { (fixedSize(it) ?: 0f).toDouble() }.toFloat() + gap * list.tracks.size
            if (unit > 0f) count = floor((avail - others + gap) / unit).toInt().coerceAtLeast(1)
        }
        val out = ArrayList<TrackSize>(list.tracks)
        val insert = ArrayList<TrackSize>()
        repeat(count) { insert += repeat }
        out.addAll(list.autoRepeatIndex.coerceIn(0, out.size), insert)
        return out
    }

    private fun place(style: ComputedStyle, nodes: List<LayoutNode>, explicitCols: Int, explicitRows: Int): Placement {
        val areas = style.gridTemplateAreas.areas
        val areaRows = style.gridTemplateAreas.rows.size
        val areaCols = style.gridTemplateAreas.rows.firstOrNull()?.size ?: 0
        val exCols = maxOf(explicitCols, areaCols)
        val exRows = maxOf(explicitRows, areaRows)
        val columnFlow = style.gridAutoFlow.isColumn
        val dense = style.gridAutoFlow.isDense

        /** Resolves start/end lines of one axis to (start index or null, span). */
        fun resolve(start: GridLine, end: GridLine, explicit: Int, isRow: Boolean): Pair<Int?, Int> {
            fun named(name: String, isStart: Boolean): Int? {
                val base = name.removeSuffix("-start").removeSuffix("-end")
                val wantStart = if (name.endsWith("-end")) false else if (name.endsWith("-start")) true else isStart
                val a = areas[base] ?: return null
                return if (isRow) (if (wantStart) a[0] else a[1]) else (if (wantStart) a[2] else a[3])
            }
            fun lineIndex(l: GridLine, isStart: Boolean): Int? = when {
                l.span > 0 -> null
                l.number > 0 -> l.number - 1
                l.number < 0 -> (explicit + 1 + l.number).coerceAtLeast(0)
                l.name != null -> named(l.name, isStart)
                else -> null
            }
            val s = lineIndex(start, true)
            val en = lineIndex(end, false)
            return when {
                s != null && en != null -> minOf(s, en) to maxOf(1, kotlin.math.abs(en - s))
                s != null -> s to maxOf(1, end.span)
                en != null -> maxOf(0, en - maxOf(1, start.span)) to maxOf(1, minOf(en, maxOf(1, start.span)))
                else -> null to maxOf(1, maxOf(start.span, end.span))
            }
        }

        val occupied = HashSet<Long>()
        fun key(r: Int, c: Int) = (r.toLong() shl 32) or c.toLong()
        fun free(r: Int, c: Int, rs: Int, cs: Int): Boolean {
            for (rr in r until r + rs) for (cc in c until c + cs) if (key(rr, cc) in occupied) return false
            return true
        }
        fun mark(it: Item) {
            for (rr in it.row until it.row + it.rowSpan) for (cc in it.col until it.col + it.colSpan) occupied += key(rr, cc)
        }

        val items = nodes.map { Item(it) }
        var cols = exCols
        var rows = exRows
        val pending = ArrayList<Pair<Item, Pair<Pair<Int?, Int>, Pair<Int?, Int>>>>()
        for (it in items) {
            val s = it.node.style
            val isText = it.node.textContent != null
            val r = if (isText) null to 1 else resolve(s.gridRowStart, s.gridRowEnd, exRows, true)
            val c = if (isText) null to 1 else resolve(s.gridColumnStart, s.gridColumnEnd, exCols, false)
            it.rowSpan = r.second; it.colSpan = c.second
            if (r.first != null && c.first != null) {
                it.row = r.first!!; it.col = c.first!!
                mark(it)
                rows = maxOf(rows, it.row + it.rowSpan); cols = maxOf(cols, it.col + it.colSpan)
            } else pending += it to (r to c)
        }

        // Auto-placement. Written for row flow; column flow swaps the axes.
        var cursorMajor = 0
        var cursorMinor = 0
        for ((it, rc) in pending) {
            val (r, c) = rc
            val majorFixed = if (columnFlow) c.first else r.first
            val minorFixed = if (columnFlow) r.first else c.first
            val majorSpan = if (columnFlow) it.colSpan else it.rowSpan
            val minorSpan = if (columnFlow) it.rowSpan else it.colSpan
            var minorLimit = if (columnFlow) rows else cols
            if (minorFixed == null && minorSpan > minorLimit) {
                minorLimit = minorSpan
                if (columnFlow) rows = minorLimit else cols = minorLimit
            }
            fun fits(major: Int, minor: Int) = if (columnFlow) free(minor, major, minorSpan, majorSpan) else free(major, minor, majorSpan, minorSpan)
            var placedMajor: Int
            var placedMinor: Int
            if (majorFixed != null) {
                // Fixed row (row flow): first free column in that row.
                placedMajor = majorFixed
                placedMinor = 0
                while (!fits(placedMajor, placedMinor)) placedMinor++
            } else {
                if (dense) {
                    cursorMajor = 0; cursorMinor = 0
                }
                var major = cursorMajor
                var minor: Int
                if (minorFixed != null) {
                    // Fixed column (row flow): the first row at/after the cursor where that column is free.
                    if (minorFixed < cursorMinor && !dense) major++
                    while (!fits(major, minorFixed)) major++
                    minor = minorFixed
                } else {
                    minor = cursorMinor
                    while (true) {
                        if (minor + minorSpan > minorLimit) {
                            major++; minor = 0; continue
                        }
                        if (fits(major, minor)) break
                        minor++
                    }
                }
                placedMajor = major
                placedMinor = minor
                cursorMajor = placedMajor
                cursorMinor = placedMinor + minorSpan
            }
            if (columnFlow) {
                it.col = placedMajor; it.row = placedMinor
            } else {
                it.row = placedMajor; it.col = placedMinor
            }
            mark(it)
            rows = maxOf(rows, it.row + it.rowSpan)
            cols = maxOf(cols, it.col + it.colSpan)
        }
        return Placement(items, maxOf(rows, 0), maxOf(cols, 0))
    }

    // ---- track sizing ------------------------------------------------------------------------------------------

    private class Contribution(val start: Int, val span: Int, val min: Float, val max: Float)

    /** Track definition for index [i]: explicit tracks first, then the implicit `grid-auto-*` tracks. */
    private fun def(explicit: List<TrackSize>, auto: TrackList, i: Int): TrackSize =
        if (i < explicit.size) explicit[i] else auto.tracks[(i - explicit.size) % auto.tracks.size]

    private fun sizeTracks(defs: List<TrackSize>, contributions: List<Contribution>, avail: Float?, gap: Float, stretchAuto: Boolean): FloatArray {
        val n = defs.size
        val base = FloatArray(n)
        val limit = FloatArray(n)
        val frContent = FloatArray(n)
        fun minDef(d: TrackSize) = if (d is TrackSize.MinMax) d.min else d
        fun maxDef(d: TrackSize) = if (d is TrackSize.MinMax) d.max else d
        fun fixed(d: TrackSize): Float? = (d as? TrackSize.Fixed)?.let { (it.size as Dim).resolve(avail) }

        for (i in 0 until n) {
            base[i] = fixed(minDef(defs[i])) ?: 0f
            limit[i] = when (val mx = maxDef(defs[i])) {
                is TrackSize.Fixed -> fixed(mx) ?: 0f
                is TrackSize.Fr -> Float.POSITIVE_INFINITY
                else -> 0f
            }
        }
        // Single-span contributions.
        for (c in contributions) if (c.span == 1 && c.start < n) {
            val i = c.start
            when (minDef(defs[i])) {
                is TrackSize.Fixed -> if (fixed(minDef(defs[i])) == null) base[i] = maxOf(base[i], c.min)
                TrackSize.MaxContent -> base[i] = maxOf(base[i], c.max)
                else -> base[i] = maxOf(base[i], c.min)
            }
            when (val mx = maxDef(defs[i])) {
                TrackSize.Auto, TrackSize.MaxContent -> limit[i] = maxOf(limit[i], c.max)
                TrackSize.MinContent -> limit[i] = maxOf(limit[i], c.min)
                is TrackSize.Fixed -> if (fixed(mx) == null) limit[i] = maxOf(limit[i], c.max)
                is TrackSize.Fr -> frContent[i] = maxOf(frContent[i], c.max)
                else -> {}
            }
        }
        // Spanning contributions: grow the content-sized tracks they cover by the missing amount.
        for (c in contributions) if (c.span > 1) {
            val range = c.start until minOf(c.start + c.span, n)
            val gaps = gap * (range.count() - 1).coerceAtLeast(0)
            val growable = range.filter { fixed(minDef(defs[it])) == null }
            if (growable.isEmpty()) continue
            val missingMin = c.min - (range.sumOf { base[it].toDouble() }.toFloat() + gaps)
            if (missingMin > 0f) growable.forEach { base[it] += missingMin / growable.size }
            val missingMax = c.max - (range.sumOf { (if (limit[it].isInfinite()) base[it] else limit[it]).toDouble() }.toFloat() + gaps)
            if (missingMax > 0f) growable.filter { !limit[it].isInfinite() }.forEach { limit[it] += missingMax / growable.size }
            growable.filter { defs[it].isFlexible }.forEach { frContent[it] = maxOf(frContent[it], c.max / c.span) }
        }
        for (i in 0 until n) if (!limit[i].isInfinite() && limit[i] < base[i]) limit[i] = base[i]

        val sizes = base.copyOf()
        val gaps = gap * (n - 1).coerceAtLeast(0)
        val flex = (0 until n).filter { defs[it].isFlexible }

        if (avail == null) {
            // Indefinite size: tracks take their max-content size, flexible ones their content.
            for (i in 0 until n) sizes[i] = if (i in flex) maxOf(base[i], frContent[i]) else maxOf(base[i], limit[i])
            return sizes
        }

        // Grow non-flexible tracks toward their limits (equal shares).
        var free = avail - sizes.sum() - gaps
        var growing = (0 until n).filter { it !in flex && limit[it] > sizes[it] }
        while (free > 0.01f && growing.isNotEmpty()) {
            val share = free / growing.size
            for (i in growing) {
                val add = minOf(share, limit[i] - sizes[i])
                sizes[i] += add
                free -= add
            }
            growing = growing.filter { limit[it] > sizes[it] + 0.01f }
        }

        if (flex.isNotEmpty()) {
            // Find the size of 1fr: tracks whose base exceeds their share become inflexible.
            var flexible = flex
            var frSize: Float
            while (true) {
                val leftover = avail - gaps - (0 until n).filter { it !in flexible }.sumOf { sizes[it].toDouble() }.toFloat()
                val frSum = flexible.sumOf { frOf(defs[it]).toDouble() }.toFloat().coerceAtLeast(1f)
                frSize = (leftover / frSum).coerceAtLeast(0f)
                val tooSmall = flexible.filter { base[it] > frOf(defs[it]) * frSize }
                if (tooSmall.isEmpty()) break
                flexible = flexible - tooSmall.toSet()
                if (flexible.isEmpty()) break
            }
            for (i in flexible) sizes[i] = maxOf(base[i], frOf(defs[i]) * frSize)
        } else if (stretchAuto) {
            // justify-content: normal stretches auto tracks over the remaining space.
            free = avail - sizes.sum() - gaps
            val autos = (0 until n).filter { maxDef(defs[it]) == TrackSize.Auto }
            if (free > 0f && autos.isNotEmpty()) autos.forEach { sizes[it] += free / autos.size }
        }
        return sizes
    }

    private fun frOf(d: TrackSize): Float = when (d) {
        is TrackSize.Fr -> d.fr
        is TrackSize.MinMax -> frOf(d.max)
        else -> 0f
    }

    // ---- layout ------------------------------------------------------------------------------------------------

    private fun gridItems(node: LayoutNode): List<LayoutNode> {
        val out = ArrayList<LayoutNode>()
        val box = node.box
        for (child in node.layoutChildren) {
            e.parentOf[child] = node
            if (e.isHidden(child) || e.isWhitespaceText(child)) {
                child.box.reset(); child.box.visible = false; continue
            }
            if (e.isOutOfFlow(child)) {
                e.registerAbsolute(child, node, box.contentX, box.contentY); continue
            }
            out += child
        }
        return out.sortedBy { if (it.textContent != null) 0 else it.style.order }
    }

    /** Lays out the grid's children inside [contentWidth] (and [contentHeight] if definite); returns the content height. */
    fun layout(node: LayoutNode, contentWidth: Float, contentHeight: Float?): Float {
        val s = node.style
        val box = node.box
        val gapC = s.columnGap.resolve(contentWidth) ?: 0f
        val gapR = s.rowGap.resolve(contentHeight) ?: 0f
        val explicitCols = expand(s.gridTemplateColumns, contentWidth, gapC)
        val explicitRows = expand(s.gridTemplateRows, contentHeight, gapR)
        val placement = place(s, gridItems(node), explicitCols.size, explicitRows.size)
        val items = placement.items

        // Columns.
        val colDefs = List(placement.cols) { def(explicitCols, s.gridAutoColumns, it) }
        val colContrib = items.map { it ->
            val (mn, mx) = e.intrinsicOuter(it.node)
            Contribution(it.col, it.colSpan, mn, mx)
        }
        val stretch = s.justifyContent == JustifyContent.FLEX_START // CSS "normal" (our default) stretches auto tracks
        val colSizes = sizeTracks(colDefs, colContrib, contentWidth, gapC, stretch)
        val colStart = offsets(colSizes, gapC, contentWidth, s.justifyContent)

        // Lay out every item in the width of its area to learn its height.
        fun areaWidth(it: Item) = (it.col until it.col + it.colSpan).sumOf { colSizes[it].toDouble() }.toFloat() + gapC * (it.colSpan - 1)
        for (it in items) layoutItemInWidth(it.node, areaWidth(it), s, null)

        // Rows.
        val rowDefs = List(placement.rows) { def(explicitRows, s.gridAutoRows, it) }
        val rowContrib = items.map { val h = it.node.box.marginBoxHeight; Contribution(it.row, it.rowSpan, h, h) }
        val rowSizes = sizeTracks(rowDefs, rowContrib, contentHeight, gapR, s.alignContent.stretches)
        val rowStart = offsets(rowSizes, gapR, contentHeight, s.alignContent.distribution)

        // Final sizes with stretch alignment, then position inside the area.
        for (it in items) {
            val n = it.node
            val b = n.box
            val aw = areaWidth(it)
            val ah = (it.row until it.row + it.rowSpan).sumOf { rowSizes[it].toDouble() }.toFloat() + gapR * (it.rowSpan - 1)
            val align = alignOf(n, s, vertical = true)
            val isText = n.textContent != null
            if (align == AlignItems.STRETCH && (isText || n.style.height == Dim.Auto)) {
                layoutItemInWidth(n, aw, s, e.clampHeight(n, ah - b.margin.vertical, ah))
            } else layoutItemInWidth(n, aw, s, null, ah)
            val justify = alignOf(n, s, vertical = false)
            val freeX = aw - b.marginBoxWidth
            val freeY = ah - b.marginBoxHeight
            val dx = when (justify) {
                AlignItems.CENTER -> freeX / 2f
                AlignItems.FLEX_END -> freeX
                else -> 0f
            }
            val dy = when (align) {
                AlignItems.CENTER -> freeY / 2f
                AlignItems.FLEX_END -> freeY
                else -> 0f
            }
            b.x = box.contentX + colStart[it.col] + dx + b.margin.left
            b.y = box.contentY + rowStart[it.row] + dy + b.margin.top
            e.applyRelative(n, contentWidth, contentHeight)
            if (box.baseline == null && it.row == 0) b.baseline?.let { bl -> box.baseline = b.y + bl }
        }
        return rowSizes.sum() + gapR * (rowSizes.size - 1).coerceAtLeast(0)
    }

    /** Lays out [n] for an area [areaWidth] wide: stretched (default) or shrink-to-fit per `justify-self`. */
    private fun layoutItemInWidth(n: LayoutNode, areaWidth: Float, container: ComputedStyle, forcedHeight: Float?, areaHeight: Float? = null) {
        val stretch = alignOf(n, container, vertical = false) == AlignItems.STRETCH &&
            (n.textContent != null || (n.style.width == Dim.Auto && n.style.marginLeft != Dim.Auto && n.style.marginRight != Dim.Auto))
        if (stretch) {
            e.computeEdges(n, areaWidth)
            val w = e.clampWidth(n, areaWidth - n.box.margin.horizontal, areaWidth)
            e.layoutNode(n, areaWidth, areaHeight ?: forcedHeight, LayoutEngine.WidthMode.FILL, areaWidth, forcedWidth = w, forcedHeight = forcedHeight)
        } else {
            e.layoutNode(n, areaWidth, areaHeight ?: forcedHeight, LayoutEngine.WidthMode.SHRINK, areaWidth, forcedHeight = forcedHeight)
        }
    }

    private fun alignOf(n: LayoutNode, container: ComputedStyle, vertical: Boolean): AlignItems {
        if (n.textContent != null) return if (vertical) container.alignItems else container.justifyItems
        val self = if (vertical) n.style.alignSelf else n.style.justifySelf
        return when (self) {
            AlignSelf.AUTO -> if (vertical) container.alignItems else container.justifyItems
            AlignSelf.STRETCH -> AlignItems.STRETCH
            AlignSelf.FLEX_START -> AlignItems.FLEX_START
            AlignSelf.FLEX_END -> AlignItems.FLEX_END
            AlignSelf.CENTER -> AlignItems.CENTER
            AlignSelf.BASELINE -> AlignItems.FLEX_START
        }
    }

    /** Start offsets of each track, distributing free space per `justify-content` when [avail] is known. */
    private fun offsets(sizes: FloatArray, gap: Float, avail: Float?, justify: JustifyContent): FloatArray {
        val n = sizes.size
        val used = sizes.sum() + gap * (n - 1).coerceAtLeast(0)
        val free = if (avail != null) (avail - used).coerceAtLeast(0f) else 0f
        var start = 0f
        var extra = 0f
        when (justify) {
            JustifyContent.FLEX_START -> {}
            JustifyContent.FLEX_END -> start = free
            JustifyContent.CENTER -> start = free / 2f
            JustifyContent.SPACE_BETWEEN -> if (n > 1) extra = free / (n - 1)
            JustifyContent.SPACE_AROUND -> if (n > 0) { extra = free / n; start = extra / 2f }
            JustifyContent.SPACE_EVENLY -> { extra = free / (n + 1); start = extra }
        }
        val out = FloatArray(n)
        var pos = start
        for (i in 0 until n) {
            out[i] = pos
            pos += sizes[i] + gap + extra
        }
        return out
    }

    /** Min-content and max-content width of a grid container's content (without its padding/border). */
    fun intrinsic(node: LayoutNode): Pair<Float, Float> {
        val s = node.style
        val gapC = (s.columnGap as? Dim.Px)?.px ?: 0f
        val explicitCols = expand(s.gridTemplateColumns, null, gapC)
        val explicitRows = expand(s.gridTemplateRows, null, 0f)
        val items = node.layoutChildren.filter { !e.isHidden(it) && !e.isOutOfFlow(it) && !e.isWhitespaceText(it) }
        val placement = place(s, items, explicitCols.size, explicitRows.size)
        val defs = List(placement.cols) { def(explicitCols, s.gridAutoColumns, it) }
        val contrib = placement.items.map { val (mn, mx) = e.intrinsicOuter(it.node); Contribution(it.col, it.colSpan, mn, mx) }
        val gaps = gapC * (placement.cols - 1).coerceAtLeast(0)
        val max = sizeTracks(defs, contrib, null, gapC, false).sum() + gaps
        val min = sizeTracks(defs, contrib.map { Contribution(it.start, it.span, it.min, it.min) }, null, gapC, false).sum() + gaps
        return min to maxOf(min, max)
    }
}
