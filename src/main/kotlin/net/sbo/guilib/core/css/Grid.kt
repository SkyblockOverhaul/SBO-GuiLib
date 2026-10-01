package net.sbo.guilib.core.css

/** One track size of `grid-template-columns/rows` or `grid-auto-columns/rows`. */
sealed interface TrackSize {
    /** A length or percentage (resolved to [Dim] during style computation). */
    data class Fixed(val size: Any /* Length before, Dim after computation */) : TrackSize
    data class Fr(val fr: Float) : TrackSize
    data object Auto : TrackSize
    data object MinContent : TrackSize
    data object MaxContent : TrackSize
    data class MinMax(val min: TrackSize, val max: TrackSize) : TrackSize

    val isFlexible get() = this is Fr || (this is MinMax && max is Fr)
}

/**
 * A track list. [tracks] are the explicit tracks (fixed `repeat(n, …)` already expanded). An optional
 * `repeat(auto-fill|auto-fit, …)` is stored in [autoRepeat] and inserted at [autoRepeatIndex] once the
 * container size is known.
 */
data class TrackList(
    val tracks: List<TrackSize>,
    val autoRepeat: List<TrackSize>? = null,
    val autoRepeatIndex: Int = 0,
    val autoFit: Boolean = false,
) {
    fun map(f: (TrackSize) -> TrackSize) = copy(tracks = tracks.map(f), autoRepeat = autoRepeat?.map(f))

    companion object {
        val NONE = TrackList(emptyList())
    }
}

/** A grid line reference: `auto`, a line number (negative = from the end), `span n`, or an area name. */
data class GridLine(val number: Int = 0, val span: Int = 0, val name: String? = null) {
    val isAuto get() = number == 0 && span == 0 && name == null

    companion object {
        val AUTO = GridLine()
    }
}

/** `grid-template-areas`: rows of cell names (`.` = empty). */
data class GridAreas(val rows: List<List<String>>) {
    /** Name → (rowStart, rowEnd, colStart, colEnd), 0-based, end exclusive. Null if an area isn't rectangular. */
    val areas: Map<String, IntArray> by lazy {
        val out = HashMap<String, IntArray>()
        for ((r, row) in rows.withIndex()) for ((c, name) in row.withIndex()) {
            if (name == ".") continue
            val a = out.getOrPut(name) { intArrayOf(r, r + 1, c, c + 1) }
            a[0] = minOf(a[0], r); a[1] = maxOf(a[1], r + 1); a[2] = minOf(a[2], c); a[3] = maxOf(a[3], c + 1)
        }
        out
    }
}

enum class GridAutoFlow { ROW, COLUMN, ROW_DENSE, COLUMN_DENSE;
    val isColumn get() = this == COLUMN || this == COLUMN_DENSE
    val isDense get() = this == ROW_DENSE || this == COLUMN_DENSE
}

/** Parsing of the grid properties. */
internal object GridParser {
    private fun words(v: List<ComponentValue>) = Properties.words(v)

    private fun trackSize(v: ComponentValue): TrackSize? {
        if (v is FunctionValue && v.name == "minmax") {
            val args = BackgroundParser.splitCommas(v.args).map { words(it).singleOrNull() ?: return null }
            if (args.size != 2) return null
            val min = trackSize(args[0]) ?: return null
            val max = trackSize(args[1]) ?: return null
            if (min is TrackSize.Fr) return null // a flexible minimum is invalid in CSS
            return TrackSize.MinMax(min, max)
        }
        val t = (v as? TokenValue)?.token
        if (t != null && t.type == TokenType.DIMENSION && t.unit == "fr") return if (t.number >= 0) TrackSize.Fr(t.number.toFloat()) else null
        return when {
            Properties.isIdent(v, "auto") -> TrackSize.Auto
            Properties.isIdent(v, "min-content") -> TrackSize.MinContent
            Properties.isIdent(v, "max-content") -> TrackSize.MaxContent
            else -> Properties.length(v)?.takeIf { it.isCalc || it.value >= 0f }?.let { TrackSize.Fixed(it) }
        }
    }

    /** `none` or a track list with `repeat()`; `[line-names]` are accepted and ignored. */
    fun trackList(values: List<ComponentValue>, allowAutoRepeat: Boolean = true): TrackList? {
        val w = words(values).filter { !(it is BlockValue && it.open == '[') }
        if (w.size == 1 && Properties.isIdent(w[0], "none")) return TrackList.NONE
        if (w.isEmpty()) return null
        val tracks = ArrayList<TrackSize>()
        var autoRepeat: List<TrackSize>? = null
        var autoIndex = 0
        var autoFit = false
        for (v in w) {
            if (v is FunctionValue && v.name == "repeat") {
                val parts = BackgroundParser.splitCommas(v.args)
                if (parts.size != 2) return null
                val countWord = words(parts[0]).singleOrNull() ?: return null
                val inner = words(parts[1]).filter { !(it is BlockValue && it.open == '[') }.map { trackSize(it) ?: return null }
                if (inner.isEmpty()) return null
                when {
                    Properties.isIdent(countWord, "auto-fill") || Properties.isIdent(countWord, "auto-fit") -> {
                        // Auto-repeated tracks need a fixed size somewhere: `30px` or `minmax(30px, 1fr)` (CSS <fixed-size>).
                        fun fixedSize(t: TrackSize) = t is TrackSize.Fixed || (t is TrackSize.MinMax && (t.min is TrackSize.Fixed || t.max is TrackSize.Fixed))
                        if (!allowAutoRepeat || autoRepeat != null || !inner.all(::fixedSize)) return null
                        autoRepeat = inner; autoIndex = tracks.size; autoFit = Properties.isIdent(countWord, "auto-fit")
                    }
                    else -> {
                        val n = Properties.number(countWord)?.toInt()?.takeIf { it >= 1 } ?: return null
                        repeat(n) { tracks += inner }
                    }
                }
            } else tracks += trackSize(v) ?: return null
        }
        return TrackList(tracks, autoRepeat, autoIndex, autoFit)
    }

    fun areas(values: List<ComponentValue>): GridAreas? {
        val w = words(values)
        if (w.size == 1 && Properties.isIdent(w[0], "none")) return GridAreas(emptyList())
        val rows = w.map { v ->
            val s = (v as? TokenValue)?.token?.takeIf { it.type == TokenType.STRING }?.text ?: return null
            s.trim().split(Regex("\\s+")).map { cell -> if (cell.all { it == '.' }) "." else cell }
        }
        if (rows.isEmpty() || rows.any { it.size != rows[0].size }) return null
        val g = GridAreas(rows)
        // Every named area must be a rectangle.
        for ((name, a) in g.areas) for (r in a[0] until a[1]) for (c in a[2] until a[3]) if (rows[r][c] != name) return null
        return g
    }

    /** `auto`, `3`, `-1`, `span 2`, `header` … */
    fun line(values: List<ComponentValue>): GridLine? {
        val w = words(values)
        if (w.size == 1 && Properties.isIdent(w[0], "auto")) return GridLine.AUTO
        var span = false
        var number: Int? = null
        var name: String? = null
        for (v in w) {
            val t = (v as? TokenValue)?.token ?: return null
            when {
                t.isIdent("span") -> span = true
                t.type == TokenType.NUMBER && t.number == Math.floor(t.number) && t.number != 0.0 -> number = t.number.toInt()
                t.type == TokenType.IDENT -> name = t.text
                else -> return null
            }
        }
        return when {
            span -> GridLine(span = (number ?: 1).coerceAtLeast(1), name = name)
            number != null -> GridLine(number = number, name = name)
            name != null -> GridLine(name = name)
            else -> null
        }
    }

    /** `grid-row` / `grid-column`: `start [/ end]`. */
    fun lineShorthand(values: List<ComponentValue>, start: Prop, end: Prop): List<Pair<Prop, Any>>? {
        val parts = splitSlash(values)
        if (parts.size !in 1..2) return null
        val s = line(parts[0]) ?: return null
        // A lone area name also sets the end to the same name (like CSS).
        val e = if (parts.size == 2) line(parts[1]) ?: return null else if (s.name != null && s.number == 0 && s.span == 0) s else GridLine.AUTO
        return listOf(start to s, end to e)
    }

    /** `grid-area: name` or `row-start / col-start / row-end / col-end`. */
    fun areaShorthand(values: List<ComponentValue>): List<Pair<Prop, Any>>? {
        val parts = splitSlash(values).map { line(it) ?: return null }
        if (parts.isEmpty() || parts.size > 4) return null
        fun nameOr(i: Int, fallback: GridLine) = parts.getOrNull(i) ?: if (fallback.name != null && fallback.number == 0 && fallback.span == 0) fallback else GridLine.AUTO
        val rs = parts[0]
        val cs = nameOr(1, rs)
        val re = nameOr(2, rs)
        val ce = nameOr(3, cs)
        return listOf(Prop.GRID_ROW_START to rs, Prop.GRID_COLUMN_START to cs, Prop.GRID_ROW_END to re, Prop.GRID_COLUMN_END to ce)
    }

    fun autoFlow(values: List<ComponentValue>): GridAutoFlow? {
        val w = words(values)
        var column = false
        var dense = false
        for (v in w) when {
            Properties.isIdent(v, "row") -> column = false
            Properties.isIdent(v, "column") -> column = true
            Properties.isIdent(v, "dense") -> dense = true
            else -> return null
        }
        if (w.isEmpty()) return null
        return when {
            column && dense -> GridAutoFlow.COLUMN_DENSE
            column -> GridAutoFlow.COLUMN
            dense -> GridAutoFlow.ROW_DENSE
            else -> GridAutoFlow.ROW
        }
    }

    private fun splitSlash(values: List<ComponentValue>): List<List<ComponentValue>> {
        val out = ArrayList<List<ComponentValue>>()
        var cur = ArrayList<ComponentValue>()
        for (v in values) if (v is TokenValue && v.token.isDelim('/')) {
            out += cur; cur = ArrayList()
        } else cur += v
        out += cur
        return out.map { CssParser.trimWhitespace(it) }
    }
}
