package net.sbo.guilib.core.layout

import net.sbo.guilib.core.css.BorderCollapse
import net.sbo.guilib.core.css.CaptionSide
import net.sbo.guilib.core.css.Dim
import net.sbo.guilib.core.css.Display
import net.sbo.guilib.core.css.TableLayoutMode
import net.sbo.guilib.core.css.VerticalAlign

/**
 * CSS table layout (`display: table` and friends), close to what browsers do:
 * - structure: captions, the first header group on top, the first footer group at the bottom, row groups and rows
 *   in between; `colspan` / `rowspan` (0 = to the end of the group) with HTML's slot placement; `<col>` / `<colgroup>`
 *   widths. Rows or cells without their wrapper (a `tr` straight in the table, a `td` straight in a group) get an
 *   implicit one, without a box.
 * - auto layout: column min/max widths from the cells' min-/max-content (a fixed `width` on a cell fixes its column,
 *   a percentage asks for that share), spanning cells spread over their columns, and the available width is handed
 *   out like CSS Tables 3 (min → percentages → fixed → auto columns). `table-layout: fixed` (with a table width)
 *   uses only `<col>` and first-row widths and splits the rest evenly.
 * - rows are as high as their tallest cell (and their own `height`); cells fill the row and place their content with
 *   `vertical-align` (`top`, `middle`, `bottom`, `baseline` = shared baseline of the row).
 * - `border-spacing`, or `border-collapse: collapse` (no spacing, the table's padding is ignored, adjacent borders
 *   overlap so the wider one shows; colors are not resolved – the cell painted last wins).
 *
 * Row groups and rows get real boxes spanning their cells, so their backgrounds, `:hover` and `:nth-child()` work.
 * Simplifications: a rowspan cell is painted before later rows, so their
 * backgrounds cover it; `<col>` backgrounds are not painted; no `visibility: collapse`, `empty-cells`.
 */
internal class TableLayout(private val e: LayoutEngine) {

    private class Cell(val node: LayoutNode, val row: Int, val col: Int, var rowSpan: Int, val colSpan: Int) {
        var height = 0f
        var baseline = 0f
        var align = VerticalAlign.BASELINE
    }

    /** A row of the grid; [node] is null for an implicit row. [parent] is the DOM parent of its cells. */
    private class Row(val node: LayoutNode?, val parent: LayoutNode, val group: Group) {
        var y = 0f
        var height = 0f
        var baseline = 0f
        var hasBaseline = false
    }

    /** A row group; [node] is null for the implicit group around rows straight in the table. */
    private class Group(val node: LayoutNode?) {
        var first = 0
        var count = 0
    }

    private class Structure(
        val captions: List<LayoutNode>,
        val groups: List<Group>,
        val rows: List<Row>,
        val cells: List<Cell>,
        val columns: Int,
        /** Specified width per column from `<col>` / `<colgroup>`. */
        val colWidths: Array<Dim?>,
        /** Children without a slot in the grid: `<col>`, `<colgroup>`, whitespace, hidden and out-of-flow children. */
        val absolutes: List<Pair<LayoutNode, LayoutNode>>,
    )

    // ---- structure ---------------------------------------------------------------------------------------------

    private fun skip(child: LayoutNode, parent: LayoutNode, absolutes: MutableList<Pair<LayoutNode, LayoutNode>>): Boolean {
        e.parentOf[child] = parent
        when {
            e.isHidden(child) -> { child.box.reset(); child.box.visible = false }
            e.isWhitespaceText(child) -> { child.box.reset(); child.box.visible = false }
            e.isOutOfFlow(child) -> absolutes += child to parent
            else -> return false
        }
        return true
    }

    private fun structure(table: LayoutNode): Structure {
        val captions = ArrayList<LayoutNode>()
        val absolutes = ArrayList<Pair<LayoutNode, LayoutNode>>()
        val colWidths = ArrayList<Dim?>()
        // Sources: each group lists its rows as (row node or null, parent, cell nodes).
        class RowSrc(val node: LayoutNode?, val parent: LayoutNode, val cells: MutableList<LayoutNode> = ArrayList())
        class GroupSrc(val node: LayoutNode?, val rows: MutableList<RowSrc> = ArrayList())
        var head: GroupSrc? = null
        var foot: GroupSrc? = null
        val bodies = ArrayList<GroupSrc>()

        fun hideBox(n: LayoutNode) { n.box.reset(); n.box.visible = false }
        fun addColumn(n: LayoutNode, width: Dim?) = repeat(n.colSpan) { colWidths += width?.takeIf { it != Dim.Auto } }

        /** Rows of a group (or of the table): `tr`s, and runs of other children wrapped in an implicit row. */
        fun collectRows(parent: LayoutNode, into: GroupSrc) {
            var implicit: RowSrc? = null
            for (child in parent.layoutChildren) {
                if (skip(child, parent, absolutes)) continue
                if (child.textContent == null && child.style.display == Display.TABLE_ROW) {
                    implicit = null
                    val row = RowSrc(child, child)
                    for (c in child.layoutChildren) if (!skip(c, child, absolutes)) row.cells += c
                    into.rows += row
                } else {
                    if (implicit == null) implicit = RowSrc(null, parent).also { into.rows += it }
                    implicit.cells += child
                }
            }
        }

        var loose: GroupSrc? = null // implicit group for rows/cells straight in the table
        var looseCells: RowSrc? = null
        for (child in table.layoutChildren) {
            if (skip(child, table, absolutes)) continue
            val d = if (child.textContent != null) Display.TABLE_CELL else child.style.display
            when (d) {
                Display.TABLE_CAPTION -> captions += child
                Display.TABLE_COLUMN_GROUP -> {
                    hideBox(child)
                    val cols = child.layoutChildren.filter { it.textContent == null && it.style.display == Display.TABLE_COLUMN && !e.isHidden(it) }
                    for (c in child.layoutChildren) { e.parentOf[c] = child; hideBox(c) }
                    if (cols.isEmpty()) addColumn(child, child.style.width) else cols.forEach { addColumn(it, it.style.width) }
                }
                Display.TABLE_COLUMN -> { hideBox(child); addColumn(child, child.style.width) }
                Display.TABLE_HEADER_GROUP, Display.TABLE_FOOTER_GROUP, Display.TABLE_ROW_GROUP -> {
                    loose = null; looseCells = null
                    val g = GroupSrc(child)
                    collectRows(child, g)
                    when {
                        d == Display.TABLE_HEADER_GROUP && head == null -> head = g
                        d == Display.TABLE_FOOTER_GROUP && foot == null -> foot = g
                        else -> bodies += g
                    }
                }
                Display.TABLE_ROW -> {
                    looseCells = null
                    val g = loose ?: GroupSrc(null).also { loose = it; bodies += it }
                    val row = RowSrc(child, child)
                    for (c in child.layoutChildren) if (!skip(c, child, absolutes)) row.cells += c
                    g.rows += row
                }
                else -> {
                    val g = loose ?: GroupSrc(null).also { loose = it; bodies += it }
                    val r = looseCells ?: RowSrc(null, table).also { looseCells = it; g.rows += it }
                    r.cells += child
                }
            }
        }

        val groups = ArrayList<Group>()
        val rows = ArrayList<Row>()
        val cells = ArrayList<Cell>()
        val occupied = ArrayList<BooleanArray>()
        fun taken(r: Int, c: Int) = r < occupied.size && c < occupied[r].size && occupied[r][c]
        fun take(r: Int, c: Int) {
            while (occupied.size <= r) occupied += BooleanArray(0)
            if (occupied[r].size <= c) occupied[r] = occupied[r].copyOf(maxOf(c + 1, occupied[r].size * 2))
            occupied[r][c] = true
        }
        var columns = colWidths.size
        for (src in listOfNotNull(head) + bodies + listOfNotNull(foot)) {
            val g = Group(src.node)
            g.first = rows.size
            g.count = src.rows.size
            groups += g
            val end = g.first + g.count
            for (rs in src.rows) {
                val r = rows.size
                rows += Row(rs.node, rs.parent, g)
                var c = 0
                for (n in rs.cells) {
                    while (taken(r, c)) c++
                    val cs = n.colSpan
                    val span = n.rowSpan.let { if (it == 0) end - r else minOf(it, end - r) }.coerceAtLeast(1)
                    cells += Cell(n, r, c, span, cs)
                    for (y in r until r + span) for (x in c until c + cs) take(y, x)
                    c += cs
                    columns = maxOf(columns, c)
                }
            }
        }
        val widths = arrayOfNulls<Dim>(columns)
        for ((i, w) in colWidths.withIndex()) widths[i] = w
        return Structure(captions, groups, rows, cells, columns, widths, absolutes)
    }

    // ---- columns -----------------------------------------------------------------------------------------------

    private class Columns(val min: FloatArray, val max: FloatArray, val pct: FloatArray, val fixed: BooleanArray)

    private fun collapsed(table: LayoutNode) = table.style.borderCollapse == BorderCollapse.COLLAPSE

    /** Horizontal / vertical spacing between cells (0 when collapsed). */
    private fun spacingX(table: LayoutNode) = if (collapsed(table)) 0f else table.style.borderSpacingX
    private fun spacingY(table: LayoutNode) = if (collapsed(table)) 0f else table.style.borderSpacingY

    private fun columns(st: Structure, sx: Float, ov: Overlaps): Columns {
        val n = st.columns
        val min = FloatArray(n)
        val max = FloatArray(n)
        val pct = FloatArray(n)
        val fixed = BooleanArray(n)
        val spec = FloatArray(n)
        for (i in 0 until n) when (val w = st.colWidths[i]) {
            is Dim.Px -> { spec[i] = w.px; fixed[i] = true }
            is Dim.Pct -> pct[i] = w.pct
            else -> {}
        }
        for (c in st.cells.filter { it.colSpan == 1 }) {
            val i = c.col
            val contentMin = e.intrinsicContentMin(c.node)
            val contentMax = e.intrinsic(c.node)[1]
            min[i] = maxOf(min[i], contentMin)
            val s = c.node.style
            when (val w = if (c.node.textContent != null) Dim.Auto else s.width) {
                is Dim.Px -> { fixed[i] = true; spec[i] = maxOf(spec[i], e.intrinsic(c.node)[0]) }
                is Dim.Pct -> { pct[i] = maxOf(pct[i], w.pct); max[i] = maxOf(max[i], contentMax) }
                else -> max[i] = maxOf(max[i], contentMax)
            }
        }
        // A fixed column wants its specified width (content can still widen it), whatever its other cells hold.
        for (i in 0 until n) max[i] = if (fixed[i]) maxOf(spec[i], min[i]) else maxOf(max[i], min[i])
        // Spanning cells: spread what their columns lack, in proportion to the columns' max widths.
        for (c in st.cells.filter { it.colSpan > 1 }.sortedBy { it.colSpan }) {
            val range = c.col until c.col + c.colSpan
            // Spacing between the spanned columns adds to the cell; collapsed borders shared inside it take away.
            val between = sx * (c.colSpan - 1) - (c.col + 1 until c.col + c.colSpan).sumOf { ov.v[it].toDouble() }.toFloat()
            fun spread(arr: FloatArray, need: Float) {
                val missing = need - between - range.sumOf { arr[it].toDouble() }.toFloat()
                if (missing <= 0f) return
                val weight = range.sumOf { max[it].toDouble() }.toFloat()
                for (i in range) arr[i] += missing * (if (weight > 0f) max[i] / weight else 1f / c.colSpan)
            }
            spread(min, e.intrinsicContentMin(c.node))
            spread(max, if (c.node.textContent == null && c.node.style.width is Dim.Px) e.intrinsic(c.node)[0] else e.intrinsic(c.node)[1])
            for (i in range) max[i] = maxOf(max[i], min[i])
        }
        return Columns(min, max, pct, fixed)
    }

    /** Hands [width] (the space for the columns themselves) out to the columns. */
    private fun distribute(cols: Columns, width: Float): FloatArray {
        val n = cols.min.size
        val pctTarget = FloatArray(n) { maxOf(cols.min[it], cols.pct[it] / 100f * width) }
        val isPct = BooleanArray(n) { cols.pct[it] > 0f }
        val guesses = listOf(
            cols.min,
            FloatArray(n) { if (isPct[it]) pctTarget[it] else cols.min[it] },
            FloatArray(n) { if (isPct[it]) pctTarget[it] else if (cols.fixed[it]) cols.max[it] else cols.min[it] },
            FloatArray(n) { if (isPct[it]) pctTarget[it] else cols.max[it] },
        )
        val sums = guesses.map { g -> g.sum() }
        if (width <= sums[0]) return cols.min.copyOf()
        for (k in 0 until guesses.size - 1) {
            if (width <= sums[k + 1]) {
                val a = guesses[k]; val b = guesses[k + 1]
                val t = if (sums[k + 1] > sums[k]) (width - sums[k]) / (sums[k + 1] - sums[k]) else 1f
                return FloatArray(n) { a[it] + (b[it] - a[it]) * t }
            }
        }
        // More room than every column wants: auto columns grow first, then fixed ones, then percentage ones.
        val out = guesses.last().copyOf()
        val extra = width - sums.last()
        val auto = (0 until n).filter { !isPct[it] && !cols.fixed[it] }
        val takers = auto.ifEmpty { (0 until n).filter { !isPct[it] } }.ifEmpty { (0 until n).toList() }
        val weight = takers.sumOf { out[it].toDouble() }.toFloat()
        for (i in takers) out[i] += extra * (if (weight > 0f) out[i] / weight else 1f / takers.size)
        return out
    }

    /** `table-layout: fixed`: widths from `<col>`s and the first row only; the rest is split evenly. */
    private fun distributeFixed(st: Structure, width: Float, sx: Float): FloatArray {
        val n = st.columns
        val out = FloatArray(n) { -1f }
        for (i in 0 until n) st.colWidths[i]?.resolve(width)?.let { out[i] = it }
        for (c in st.cells.filter { it.row == 0 }) {
            val node = c.node
            if (node.textContent != null) continue
            val w = node.style.width.resolve(width) ?: continue
            val pb = node.style.let { s -> (s.paddingLeft.resolve(width) ?: 0f) + (s.paddingRight.resolve(width) ?: 0f) + s.borderLeftWidth + s.borderRightWidth }
            val bw = e.toBorderBox(w, node.style, pb)
            val free = (c.col until c.col + c.colSpan).filter { out[it] < 0f }
            val each = (bw - sx * (c.colSpan - 1)) / c.colSpan
            for (i in free) out[i] = maxOf(each, 0f)
        }
        val unset = (0 until n).filter { out[it] < 0f }
        val used = (0 until n).filter { out[it] >= 0f }.sumOf { out[it].toDouble() }.toFloat()
        val left = maxOf(width - used, 0f)
        if (unset.isNotEmpty()) unset.forEach { out[it] = left / unset.size }
        else if (left > 0f && used > 0f) for (i in 0 until n) out[i] += left * out[i] / used
        return out
    }

    // ---- collapsed borders -------------------------------------------------------------------------------------

    /**
     * How much neighbouring boxes overlap at each grid line when borders collapse: per vertical line (0..columns)
     * and per horizontal line (0..rows), the max over the line of min(border on one side, border on the other).
     */
    private class Overlaps(val v: FloatArray, val h: FloatArray) {
        val vSum get() = v.sum()
        val hSum get() = h.sum()
    }

    private fun overlaps(table: LayoutNode, st: Structure): Overlaps {
        val n = st.columns
        val r = st.rows.size
        val v = FloatArray(n + 1)
        val h = FloatArray(r + 1)
        if (!collapsed(table) || n == 0) return Overlaps(v, h)
        val left = HashMap<Long, Float>(); val right = HashMap<Long, Float>()
        val top = HashMap<Long, Float>(); val bottom = HashMap<Long, Float>()
        fun key(a: Int, b: Int) = (a.toLong() shl 32) or b.toLong()
        for (c in st.cells) {
            val s = c.node.style
            val bl = if (c.node.textContent != null) 0f else s.borderLeftWidth
            val br = if (c.node.textContent != null) 0f else s.borderRightWidth
            val bt = if (c.node.textContent != null) 0f else s.borderTopWidth
            val bb = if (c.node.textContent != null) 0f else s.borderBottomWidth
            for (y in c.row until c.row + c.rowSpan) { left[key(c.col, y)] = bl; right[key(c.col + c.colSpan, y)] = br }
            for (x in c.col until c.col + c.colSpan) { top[key(c.row, x)] = bt; bottom[key(c.row + c.rowSpan, x)] = bb }
        }
        val ts = table.style
        for (line in 0..n) for (y in 0 until r) {
            val a = if (line == 0) ts.borderLeftWidth else right[key(line, y)] ?: 0f
            val b = if (line == n) ts.borderRightWidth else left[key(line, y)] ?: 0f
            v[line] = maxOf(v[line], minOf(a, b))
        }
        for (line in 0..r) for (x in 0 until n) {
            val a = if (line == 0) ts.borderTopWidth else bottom[key(line, x)] ?: 0f
            val b = if (line == r) ts.borderBottomWidth else top[key(line, x)] ?: 0f
            h[line] = maxOf(h[line], minOf(a, b))
        }
        return Overlaps(v, h)
    }

    // ---- intrinsic sizes ---------------------------------------------------------------------------------------

    /**
     * Min-/max-content width of the table's content box (the caller adds padding and border). When borders
     * collapse the padding is ignored, so it is subtracted here.
     */
    fun intrinsic(table: LayoutNode): FloatArray {
        val st = structure(table)
        val sx = spacingX(table)
        val n = st.columns
        val ov = overlaps(table, st)
        val cols = columns(st, sx, ov)
        val extra = if (n == 0) 0f else sx * (n + 1) - ov.vSum
        var min = cols.min.sum() + extra
        var max = cols.max.sum() + extra
        // Percentage columns ask for a share of the table: make the max width big enough to give it to them.
        val pctSum = cols.pct.sum().coerceAtMost(100f)
        if (pctSum > 0f) {
            for (i in 0 until n) if (cols.pct[i] > 0f) max = maxOf(max, cols.max[i] / (cols.pct[i] / 100f) + extra)
            val rest = (0 until n).filter { cols.pct[it] <= 0f }.sumOf { cols.max[it].toDouble() }.toFloat()
            if (pctSum < 100f && rest > 0f) max = maxOf(max, rest / (1f - pctSum / 100f) + extra)
        }
        for (c in st.captions) {
            val (a, b) = e.intrinsicOuter(c)
            min = maxOf(min, a); max = maxOf(max, b)
        }
        if (collapsed(table)) {
            val s = table.style
            val pad = ((s.paddingLeft as? Dim.Px)?.px ?: 0f) + ((s.paddingRight as? Dim.Px)?.px ?: 0f)
            min -= pad; max -= pad
        }
        return floatArrayOf(min, maxOf(min, max))
    }

    // ---- layout ------------------------------------------------------------------------------------------------

    /** Lays out the table's content; returns the content height (relative to the content box of the incoming edges). */
    fun layout(table: LayoutNode, contentWidth: Float, contentHeight: Float?): Float {
        val st = structure(table)
        val box = table.box
        val collapse = collapsed(table)
        val padV = box.padding.vertical
        val padH = box.padding.horizontal
        if (collapse) box.padding.set(0f, 0f, 0f, 0f)
        val innerW = if (collapse) contentWidth + padH else contentWidth
        val sx = spacingX(table)
        val sy = spacingY(table)
        val n = st.columns
        val ov = overlaps(table, st)

        // Column widths.
        val colSpace = if (n == 0) 0f else innerW - sx * (n + 1) + ov.vSum
        val fixedLayout = table.style.tableLayout == TableLayoutMode.FIXED && table.style.width != Dim.Auto
        val colW = if (fixedLayout) distributeFixed(st, colSpace, sx) else distribute(columns(st, sx, ov), colSpace)
        val colX = FloatArray(n + 1)
        colX[0] = if (collapse) box.border.left - ov.v[0] else box.contentX + sx
        for (i in 0 until n) colX[i + 1] = colX[i] + colW[i] + sx - ov.v[i + 1]
        fun cellX(col: Int) = colX[col]
        fun spanWidth(col: Int, span: Int) = colX[col + span - 1] + colW[col + span - 1] - colX[col]
        val gridLeft = if (n == 0) box.contentX else colX[0]
        val gridWidth = if (n == 0) 0f else colX[n - 1] + colW[n - 1] - colX[0]

        // Captions sit outside the border box, as wide as it, like the web: the table's margin makes room for them.
        // Top ones are placed now (above y = 0); bottom ones in [placeBottomCaptions] once the height is final.
        val top = st.captions.filter { it.style.captionSide == CaptionSide.TOP }
        val bottom = st.captions.filter { it.style.captionSide == CaptionSide.BOTTOM }
        for (c in st.captions) e.layoutNode(c, box.width, null, LayoutEngine.WidthMode.FILL, box.width)
        var above = top.sumOf { it.box.marginBoxHeight.toDouble() }.toFloat()
        box.margin.top += above
        for (c in top) {
            c.box.x = c.box.margin.left
            c.box.y = -above + c.box.margin.top
            above -= c.box.marginBoxHeight
        }
        box.margin.bottom += bottom.sumOf { it.box.marginBoxHeight.toDouble() }.toFloat()
        if (bottom.isNotEmpty()) bottomCaptions[table] = bottom
        var y = box.contentY

        // Measure cells, then size the rows.
        val rows = st.rows
        for (c in st.cells) {
            val w = spanWidth(c.col, c.colSpan)
            e.layoutNode(c.node, w, null, LayoutEngine.WidthMode.FILL, w, forcedWidth = w)
            val b = c.node.box
            c.height = b.height
            c.baseline = b.baseline ?: (b.height - b.border.bottom - b.padding.bottom)
            c.align = when (val va = if (c.node.textContent != null) VerticalAlign.BASELINE else c.node.style.verticalAlign) {
                VerticalAlign.TOP, VerticalAlign.MIDDLE, VerticalAlign.BOTTOM -> va as VerticalAlign
                else -> VerticalAlign.BASELINE
            }
        }
        for (r in rows) r.height = r.node?.let { rowMinHeight(it, contentHeight) } ?: 0f
        for (c in st.cells) if (c.rowSpan == 1 && c.align == VerticalAlign.BASELINE) {
            val r = rows[c.row]
            r.baseline = maxOf(r.baseline, c.baseline); r.hasBaseline = true
        }
        for (c in st.cells) if (c.rowSpan == 1) {
            val r = rows[c.row]
            r.height = maxOf(r.height, if (c.align == VerticalAlign.BASELINE) r.baseline - c.baseline + c.height else c.height)
        }
        fun spanHeight(first: Int, span: Int): Float {
            var h = 0f
            for (i in first until first + span) h += rows[i].height
            for (i in first + 1 until first + span) h += sy - ov.h[i]
            return h
        }
        for (c in st.cells.filter { it.rowSpan > 1 }.sortedBy { it.rowSpan }) {
            val missing = c.height - spanHeight(c.row, c.rowSpan)
            if (missing > 0f) rows[c.row + c.rowSpan - 1].height += missing
        }
        // A taller table than its rows: the rows share the extra height.
        if (rows.isNotEmpty()) {
            val grid = spanHeight(0, rows.size) + sy * 2 - ov.h[0] - ov.h[rows.size]
            val avail = contentHeight?.let { if (collapse) it + padV else it }
            if (avail != null && avail > grid) {
                val extra = avail - grid
                val total = rows.sumOf { it.height.toDouble() }.toFloat()
                for (r in rows) r.height += extra * (if (total > 0f) r.height / total else 1f / rows.size)
            }
        }

        // Row positions.
        if (rows.isNotEmpty()) {
            var ry = if (collapse) y - ov.h[0] else y + sy
            for ((i, r) in rows.withIndex()) {
                if (i > 0) ry += sy - ov.h[i]
                r.y = ry
                ry += r.height
            }
            y = if (collapse) ry - ov.h[rows.size] else ry + sy
        }

        // Group and row boxes (absolute = relative to the table's border box).
        val absX = HashMap<LayoutNode, Float>()
        val absY = HashMap<LayoutNode, Float>()
        absX[table] = 0f; absY[table] = 0f
        fun placePart(node: LayoutNode, parent: LayoutNode, ax: Float, ay: Float, w: Float, h: Float) {
            val b = node.box
            b.reset()
            e.computeEdges(node, contentWidth)
            b.margin.set(0f, 0f, 0f, 0f); b.padding.set(0f, 0f, 0f, 0f)
            b.x = ax - absX.getValue(parent); b.y = ay - absY.getValue(parent)
            b.width = w; b.height = h
            absX[node] = ax; absY[node] = ay
        }
        for (g in st.groups) {
            val node = g.node ?: continue
            val top = if (g.count > 0) rows[g.first].y else y
            val bottom = if (g.count > 0) rows[g.first + g.count - 1].let { it.y + it.height } else top
            placePart(node, table, gridLeft, top, gridWidth, bottom - top)
        }
        for (r in rows) {
            val node = r.node ?: continue
            placePart(node, r.group.node ?: table, gridLeft, r.y, gridWidth, r.height)
            if (r.hasBaseline) node.box.baseline = r.baseline
        }

        // Final cell layout: the cell fills its rows, its content moves down for vertical-align.
        for (c in st.cells) {
            val r = rows[c.row]
            val h = spanHeight(c.row, c.rowSpan)
            val shift = when (c.align) {
                VerticalAlign.TOP -> 0f
                VerticalAlign.MIDDLE -> (h - c.height) / 2f
                VerticalAlign.BOTTOM -> h - c.height
                else -> r.baseline - c.baseline
            }.coerceIn(0f, maxOf(h - c.height, 0f))
            val w = spanWidth(c.col, c.colSpan)
            if (c.node.textContent == null) {
                if (shift > 0f) e.cellShift[c.node] = shift
                e.layoutNode(c.node, w, h, LayoutEngine.WidthMode.FILL, w, forcedWidth = w, forcedHeight = h)
                e.cellShift.remove(c.node)
            } else {
                e.layoutNode(c.node, w, null, LayoutEngine.WidthMode.FILL, w, forcedWidth = w)
            }
            val b = c.node.box
            b.margin.set(0f, 0f, 0f, 0f)
            val parent = r.node ?: r.parent
            val px = absX[parent] ?: 0f
            val py = absY[parent] ?: 0f
            b.x = cellX(c.col) - px
            b.y = r.y - py + (if (c.node.textContent != null) shift else 0f)
            e.applyRelative(c.node, w, h)
        }
        for (r in rows) r.node?.let { e.computeScrollSize(it) }
        for (g in st.groups) g.node?.let { e.computeScrollSize(it) }

        // Baseline of the table = baseline of its first row.
        rows.firstOrNull()?.let { if (it.hasBaseline) box.baseline = it.y + it.baseline }

        for ((child, parent) in st.absolutes) {
            val sx0 = if (parent === table) box.contentX else 0f
            val sy0 = if (parent === table) box.contentY else 0f
            e.registerAbsolute(child, parent, sx0, sy0)
        }
        val used = y - box.contentY
        return if (collapse) used - padV else used
    }

    private val bottomCaptions = HashMap<LayoutNode, List<LayoutNode>>()

    /** Puts `caption-side: bottom` captions below the table's final border box (called by the engine). */
    fun placeBottomCaptions(table: LayoutNode) {
        val captions = bottomCaptions.remove(table) ?: return
        var y = table.box.height
        for (c in captions) {
            c.box.x = c.box.margin.left
            c.box.y = y + c.box.margin.top
            y += c.box.marginBoxHeight
        }
    }

    /** A row's own `height` (rows have no padding; a percentage resolves against the table's height). */
    private fun rowMinHeight(row: LayoutNode, tableHeight: Float?): Float {
        val s = row.style
        val h = s.height.resolve(tableHeight) ?: 0f
        val min = s.minHeight.resolve(tableHeight) ?: 0f
        return maxOf(h, min)
    }
}
