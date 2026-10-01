package net.sbo.guilib.core.layout

import net.sbo.guilib.core.css.ComputedStyle
import net.sbo.guilib.core.css.Display
import net.sbo.guilib.core.css.TextAlign
import net.sbo.guilib.core.css.TextOverflow
import net.sbo.guilib.core.css.WhiteSpace

/**
 * Inline formatting: turns text nodes, `display: inline` elements and atomic inline boxes (inline-block, images)
 * into wrapped lines. Simplifications vs. the web: padding/border/background of `display: inline` elements are
 * ignored, and `vertical-align` is always `baseline`.
 */
internal class InlineLayout(private val engine: LayoutEngine) {

    sealed interface Item {
        class Text(val text: String, val style: TextStyle, val owner: LayoutNode) : Item
        class Atomic(val node: LayoutNode) : Item
        data object Break : Item
    }

    private class Piece(val text: String, val style: TextStyle, val owner: LayoutNode, val width: Float, val trailingSpace: Float)

    private sealed interface Chunk {
        class Words(val pieces: List<Piece>) : Chunk {
            val width = pieces.sumOf { it.width.toDouble() }.toFloat()
            val widthNoTrail = width - (pieces.lastOrNull()?.trailingSpace ?: 0f)
            val isOnlySpace = pieces.all { it.text.isBlank() }
        }
        class Atomic(val node: LayoutNode) : Chunk
        data object Break : Chunk
    }

    companion object {
        fun isInlineLevel(node: LayoutNode): Boolean =
            node.textContent != null || node.isLineBreak || node.style.display.isInlineLevel

        fun isAtomic(node: LayoutNode): Boolean =
            node.textContent == null && !node.isLineBreak &&
                (node.intrinsicWidth != null || node.style.display == Display.INLINE_BLOCK || node.style.display == Display.INLINE_FLEX ||
                    node.style.display == Display.INLINE_GRID)

        private val WORD = Regex("[^ ]+ *| +")
        private const val ELLIPSIS = "…"
    }

    /** Collects inline items from [nodes] (inline-level siblings) and marks consumed nodes. */
    fun collect(nodes: List<LayoutNode>, out: MutableList<Item>, mark: Boolean = true) {
        for (node in nodes) {
            if (node.textContent == null && node.style.display == Display.NONE) {
                if (mark) node.box.visible = false
                continue
            }
            val text = node.textContent
            when {
                text != null -> {
                    if (mark) node.box.inParagraph = true
                    val base = TextStyle.of(node.style)
                    if (!node.formattingCodes) out += Item.Text(text, base, node)
                    else for ((t, s) in FormattingCodes.parse(text, base)) out += Item.Text(t, s, node)
                }
                node.isLineBreak -> {
                    if (mark) node.box.inParagraph = true
                    out += Item.Break
                }
                isAtomic(node) -> out += Item.Atomic(node)
                else -> {
                    if (mark) {
                        node.box.reset(); node.box.inParagraph = true
                    }
                    collect(node.layoutChildren, out, mark)
                }
            }
        }
    }

    private fun chunks(items: List<Item>, ws: WhiteSpace): List<Chunk> {
        val measurer = engine.measurer
        val collapse = ws == WhiteSpace.NORMAL || ws == WhiteSpace.NOWRAP
        val out = ArrayList<Chunk>()
        val current = ArrayList<Piece>()
        fun close() {
            if (current.isNotEmpty()) {
                out += Chunk.Words(ArrayList(current)); current.clear()
            }
        }
        var lastWasSpace = true
        for (item in items) {
            when (item) {
                is Item.Break -> {
                    close(); out += Chunk.Break; lastWasSpace = true
                }
                is Item.Atomic -> {
                    close(); out += Chunk.Atomic(item.node); lastWasSpace = false
                }
                is Item.Text -> {
                    val segments: List<String> = if (collapse) {
                        var t = item.text.replace(Regex("\\s+"), " ")
                        if (lastWasSpace && t.startsWith(' ')) t = t.substring(1)
                        if (t.isEmpty()) continue
                        lastWasSpace = t.endsWith(' ')
                        listOf(t)
                    } else {
                        // pre / pre-wrap: keep spaces, honour newlines.
                        val parts = item.text.replace("\t", "    ").split('\n')
                        parts.forEachIndexed { i, p ->
                            if (i > 0) {
                                close(); out += Chunk.Break
                            }
                            if (p.isNotEmpty()) addWords(p, item, current, ::close)
                        }
                        lastWasSpace = false
                        continue
                    }
                    for (s in segments) addWords(s, item, current, ::close)
                }
            }
        }
        close()
        return out
    }

    private inline fun addWords(text: String, item: Item.Text, current: MutableList<Piece>, close: () -> Unit) {
        for (m in WORD.findAll(text)) {
            val seg = m.value
            val word = seg.trimEnd(' ')
            val full = engine.measurer.width(seg, item.style)
            val trail = if (word.length == seg.length) 0f else full - engine.measurer.width(word, item.style)
            current += Piece(seg, item.style, item.owner, full, trail)
            if (seg.endsWith(' ')) close()
        }
    }

    /** Min-content and max-content width of the inline content. */
    fun intrinsic(container: ComputedStyle, items: List<Item>): Pair<Float, Float> {
        val ws = container.whiteSpace
        val wraps = ws == WhiteSpace.NORMAL || ws == WhiteSpace.PRE_WRAP
        var min = 0f
        var max = 0f
        var line = 0f
        var lineTrail = 0f
        for (c in chunks(items, ws)) {
            when (c) {
                is Chunk.Break -> {
                    max = maxOf(max, line - lineTrail); line = 0f; lineTrail = 0f
                }
                is Chunk.Words -> {
                    if (wraps) min = maxOf(min, c.widthNoTrail)
                    line += c.width; lineTrail = c.width - c.widthNoTrail
                }
                is Chunk.Atomic -> {
                    val (amin, amax) = engine.intrinsicOuter(c.node)
                    min = maxOf(min, if (wraps) amin else 0f)
                    line += amax; lineTrail = 0f
                }
            }
        }
        max = maxOf(max, line - lineTrail)
        if (!wraps) min = max
        return min to max
    }

    /** Lays out [items] into lines of at most [availWidth]; positions atomic boxes relative to the container. */
    fun layout(container: ComputedStyle, items: List<Item>, availWidth: Float, x: Float, y: Float): Paragraph {
        val measurer = engine.measurer
        val ws = container.whiteSpace
        val wraps = ws == WhiteSpace.NORMAL || ws == WhiteSpace.PRE_WRAP
        val strutStyle = TextStyle.of(container)
        val strutMetrics = measurer.metrics(strutStyle)
        val strutLineHeight = container.lineHeight.resolve(container.fontSize, strutMetrics.normalLineHeight / container.fontSize.coerceAtLeast(0.01f))

        class Placed(val x: Float, val piece: Piece?, val atomic: LayoutNode?)

        val rawLines = ArrayList<MutableList<Placed>>()
        var cur = ArrayList<Placed>()
        var curX = 0f
        fun finish() {
            rawLines += cur; cur = ArrayList(); curX = 0f
        }

        val chunks = chunks(items, ws)
        for (c in chunks) {
            when (c) {
                is Chunk.Break -> finish()
                is Chunk.Words -> {
                    if (wraps && cur.isNotEmpty() && curX + c.widthNoTrail > availWidth + 0.01f) finish()
                    if (cur.isEmpty() && c.isOnlySpace && ws == WhiteSpace.NORMAL) continue
                    for (p in c.pieces) {
                        cur += Placed(curX, p, null); curX += p.width
                    }
                }
                is Chunk.Atomic -> {
                    engine.layoutShrinkToFit(c.node, availWidth)
                    val w = c.node.box.marginBoxWidth
                    if (wraps && cur.isNotEmpty() && curX + w > availWidth + 0.01f) finish()
                    cur += Placed(curX, null, c.node); curX += w
                }
            }
        }
        if (cur.isNotEmpty() || rawLines.isEmpty()) finish()

        val ellipsis = container.textOverflow == TextOverflow.ELLIPSIS && container.overflowX.clips
        val lines = ArrayList<Line>()
        var lineY = 0f
        for (raw in rawLines) {
            // Hanging trailing whitespace doesn't count towards the line width.
            val last = raw.lastOrNull()
            var width = if (last == null) 0f else last.x + (last.piece?.let { it.width - it.trailingSpace } ?: last.atomic!!.box.marginBoxWidth)

            // Merge pieces with the same style/owner into fragments.
            val frags = ArrayList<Fragment>()
            var i = 0
            while (i < raw.size) {
                val p = raw[i]
                if (p.atomic != null) {
                    frags += Fragment.Box(p.x, p.atomic.box.marginBoxWidth, 0f, p.atomic)
                    i++; continue
                }
                val piece = p.piece!!
                val sb = StringBuilder(piece.text)
                var w = piece.width
                var j = i + 1
                while (j < raw.size && raw[j].piece != null && raw[j].piece!!.style == piece.style && raw[j].piece!!.owner === piece.owner) {
                    sb.append(raw[j].piece!!.text); w += raw[j].piece!!.width; j++
                }
                val isLastFrag = j == raw.size
                var text = sb.toString()
                if (isLastFrag && ws != WhiteSpace.PRE) {
                    val trimmed = text.trimEnd(' ')
                    if (trimmed.length != text.length) {
                        w = measurer.width(trimmed, piece.style); text = trimmed
                    }
                }
                frags += Fragment.Text(p.x, w, text, piece.style, piece.owner)
                i = j
            }

            if (ellipsis && width > availWidth + 0.01f) {
                width = applyEllipsis(frags, availWidth)
            }

            // Vertical metrics: strut + every inline box on the line.
            val strutHalfLeading = (strutLineHeight - (strutMetrics.ascent + strutMetrics.descent)) / 2f
            var above = strutMetrics.ascent + strutHalfLeading
            var below = strutMetrics.descent + strutHalfLeading
            for (f in frags) {
                when (f) {
                    is Fragment.Text -> {
                        val m = measurer.metrics(f.style)
                        val lh = container.lineHeight.resolve(f.style.fontSize, m.normalLineHeight / f.style.fontSize.coerceAtLeast(0.01f))
                        val half = (lh - (m.ascent + m.descent)) / 2f
                        above = maxOf(above, m.ascent + half)
                        below = maxOf(below, m.descent + half)
                    }
                    is Fragment.Box -> {
                        val b = f.owner.box
                        val base = b.margin.top + (b.baseline ?: b.height)
                        above = maxOf(above, base)
                        below = maxOf(below, b.marginBoxHeight - base)
                    }
                }
            }
            val lineHeight = above + below
            val shift = when (container.textAlign) {
                TextAlign.LEFT -> 0f
                TextAlign.CENTER -> ((availWidth - width) / 2f).coerceAtLeast(0f)
                TextAlign.RIGHT -> (availWidth - width).coerceAtLeast(0f)
            }
            val placed = frags.map { f ->
                when (f) {
                    is Fragment.Text -> Fragment.Text(f.x + shift, f.width, f.text, f.style, f.owner)
                    is Fragment.Box -> {
                        val b = f.owner.box
                        val top = above - (b.margin.top + (b.baseline ?: b.height))
                        b.x = x + f.x + shift + b.margin.left
                        b.y = y + lineY + top + b.margin.top
                        Fragment.Box(f.x + shift, f.width, top, f.owner)
                    }
                }
            }
            lines += Line(lineY, width, lineHeight, above, placed)
            lineY += lineHeight
        }
        return Paragraph(x, y, availWidth, lines)
    }

    /** Cuts fragments so that they plus "…" fit in [avail]; returns the new line width. */
    private fun applyEllipsis(frags: MutableList<Fragment>, avail: Float): Float {
        val measurer = engine.measurer
        val lastText = frags.lastOrNull { it is Fragment.Text } as Fragment.Text? ?: return frags.last().let { it.x + it.width }
        val ellipsisWidth = measurer.width(ELLIPSIS, lastText.style)
        val limit = avail - ellipsisWidth
        while (frags.isNotEmpty()) {
            val f = frags.last()
            if (f.x + f.width <= limit) break
            if (f is Fragment.Text && f.x < limit) {
                // Binary search the longest prefix that fits.
                var lo = 0
                var hi = f.text.length
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (f.x + measurer.width(f.text.substring(0, mid), f.style) <= limit) lo = mid else hi = mid - 1
                }
                val cut = f.text.substring(0, lo).trimEnd()
                frags.removeAt(frags.size - 1)
                if (cut.isNotEmpty()) frags += Fragment.Text(f.x, measurer.width(cut, f.style), cut, f.style, f.owner)
                break
            }
            frags.removeAt(frags.size - 1)
        }
        val endX = frags.lastOrNull()?.let { it.x + it.width } ?: 0f
        frags += Fragment.Text(endX, ellipsisWidth, ELLIPSIS, lastText.style, lastText.owner)
        return endX + ellipsisWidth
    }
}
