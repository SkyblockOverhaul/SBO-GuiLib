package net.sbo.guilib.core.css

import kotlin.math.PI

/** One layer of `background-image`. The first layer is painted on top, like CSS. */
sealed interface BackgroundLayer {
    data class Url(val src: String) : BackgroundLayer

    /**
     * `linear-gradient()` / `radial-gradient()` / `conic-gradient()` and their `repeating-` forms. Colors are ARGB
     * ints (or [CurrentColor] before computation); stop positions are lengths (`%` of the gradient line / ray / turn)
     * or `null` = distribute evenly. Conic stop angles are stored as `%` of a full turn.
     */
    data class Gradient(
        val radial: Boolean,
        /** Linear: CSS angle in degrees (0 = to top, 90 = to right, 180 = to bottom). */
        val angle: Float = 180f,
        /** Linear `to <corner>`: the angle depends on the box's aspect ratio. */
        val toCorner: Pair<Int, Int>? = null,
        /** Radial. */
        val circle: Boolean = false,
        val size: RadialSize = RadialSize.FARTHEST_CORNER,
        val explicitSize: Pair<Length, Length>? = null,
        val centerX: Length = Length(50f, "%"),
        val centerY: Length = Length(50f, "%"),
        val stops: List<Stop>,
        /** `conic-gradient`: colors go clockwise around [centerX]/[centerY], starting at [fromAngle]. */
        val conic: Boolean = false,
        /** Conic: CSS angle in degrees where the gradient starts (0 = top). */
        val fromAngle: Float = 0f,
        /** `repeating-*-gradient`: the stops repeat with the distance from the first to the last stop. */
        val repeating: Boolean = false,
    ) : BackgroundLayer

    data class Stop(val color: Any, val position: Length?)

    enum class RadialSize { CLOSEST_SIDE, CLOSEST_CORNER, FARTHEST_SIDE, FARTHEST_CORNER }
}

/** Parsing of `background-image` values (url, gradients, layer lists). */
internal object BackgroundParser {

    private fun ws(v: ComponentValue) = v is TokenValue && v.token.type == TokenType.WHITESPACE
    private fun comma(v: ComponentValue) = v is TokenValue && v.token.type == TokenType.COMMA

    /** Splits a value at top-level commas into trimmed parts. */
    fun splitCommas(values: List<ComponentValue>): List<List<ComponentValue>> {
        val out = ArrayList<List<ComponentValue>>()
        var cur = ArrayList<ComponentValue>()
        for (v in values) if (comma(v)) {
            out += CssParser.trimWhitespace(cur); cur = ArrayList()
        } else cur += v
        out += CssParser.trimWhitespace(cur)
        return out
    }

    /** One layer: `url(...)`, a gradient function, or `null` if invalid. */
    fun layer(v: ComponentValue, url: (ComponentValue) -> String?): BackgroundLayer? {
        url(v)?.let { return BackgroundLayer.Url(it) }
        if (v !is FunctionValue) return null
        return when (v.name) {
            "linear-gradient" -> linear(v.args)
            "radial-gradient" -> radial(v.args)
            "conic-gradient" -> conic(v.args)
            "repeating-linear-gradient" -> linear(v.args)?.copy(repeating = true)
            "repeating-radial-gradient" -> radial(v.args)?.copy(repeating = true)
            "repeating-conic-gradient" -> conic(v.args)?.copy(repeating = true)
            else -> null
        }
    }

    private fun angle(v: ComponentValue): Float? {
        val t = (v as? TokenValue)?.token ?: return null
        if (t.type == TokenType.NUMBER && t.number == 0.0) return 0f
        if (t.type != TokenType.DIMENSION) return null
        val n = t.number.toFloat()
        return when (t.unit) {
            "deg" -> n
            "rad" -> (n * 180f / PI.toFloat())
            "grad" -> n * 0.9f
            "turn" -> n * 360f
            else -> null
        }
    }

    private fun linear(args: List<ComponentValue>): BackgroundLayer.Gradient? {
        val parts = splitCommas(args)
        var angle = 180f
        var corner: Pair<Int, Int>? = null
        var stopParts = parts
        val first = parts.firstOrNull()?.filter { !ws(it) } ?: return null
        val a = first.singleOrNull()?.let(::angle)
        if (a != null) {
            angle = a; stopParts = parts.drop(1)
        } else if (first.isNotEmpty() && Properties.isIdent(first[0], "to")) {
            var dx = 0
            var dy = 0
            for (w in first.drop(1)) when {
                Properties.isIdent(w, "left") -> dx = -1
                Properties.isIdent(w, "right") -> dx = 1
                Properties.isIdent(w, "top") -> dy = -1
                Properties.isIdent(w, "bottom") -> dy = 1
                else -> return null
            }
            if (dx == 0 && dy == 0) return null
            if (dx != 0 && dy != 0) corner = dx to dy
            else angle = when {
                dy == -1 -> 0f
                dx == 1 -> 90f
                dy == 1 -> 180f
                else -> 270f
            }
            stopParts = parts.drop(1)
        }
        val stops = stops(stopParts) ?: return null
        return BackgroundLayer.Gradient(radial = false, angle = angle, toCorner = corner, stops = stops)
    }

    private fun radial(args: List<ComponentValue>): BackgroundLayer.Gradient? {
        val parts = splitCommas(args)
        var circle = false
        var size = BackgroundLayer.RadialSize.FARTHEST_CORNER
        var explicit: Pair<Length, Length>? = null
        var cx = Length(50f, "%")
        var cy = Length(50f, "%")
        var stopParts = parts
        val first = parts.firstOrNull()?.filter { !ws(it) } ?: return null
        val looksLikeShape = first.isNotEmpty() && Properties.color(first[0]) == null
        if (looksLikeShape) {
            val atIdx = first.indexOfFirst { Properties.isIdent(it, "at") }
            val shapePart = if (atIdx >= 0) first.subList(0, atIdx) else first
            val lengths = ArrayList<Length>()
            for (w in shapePart) when {
                Properties.isIdent(w, "circle") -> circle = true
                Properties.isIdent(w, "ellipse") -> circle = false
                Properties.isIdent(w, "closest-side") -> size = BackgroundLayer.RadialSize.CLOSEST_SIDE
                Properties.isIdent(w, "closest-corner") -> size = BackgroundLayer.RadialSize.CLOSEST_CORNER
                Properties.isIdent(w, "farthest-side") -> size = BackgroundLayer.RadialSize.FARTHEST_SIDE
                Properties.isIdent(w, "farthest-corner") -> size = BackgroundLayer.RadialSize.FARTHEST_CORNER
                else -> lengths += Properties.length(w) ?: return null
            }
            when (lengths.size) {
                0 -> {}
                1 -> { circle = true; explicit = lengths[0] to lengths[0] }
                2 -> explicit = lengths[0] to lengths[1]
                else -> return null
            }
            if (atIdx >= 0) {
                val (x, y) = position(first.subList(atIdx + 1, first.size)) ?: return null
                cx = x; cy = y
            }
            stopParts = parts.drop(1)
        }
        val stops = stops(stopParts) ?: return null
        return BackgroundLayer.Gradient(radial = true, circle = circle, size = size, explicitSize = explicit, centerX = cx, centerY = cy, stops = stops)
    }

    /** `conic-gradient([from <angle>] [at <position>], stops…)`; stop positions are angles or percentages. */
    private fun conic(args: List<ComponentValue>): BackgroundLayer.Gradient? {
        val parts = splitCommas(args)
        var from = 0f
        var cx = Length(50f, "%")
        var cy = Length(50f, "%")
        var stopParts = parts
        val first = parts.firstOrNull()?.filter { !ws(it) } ?: return null
        if (first.isNotEmpty() && (Properties.isIdent(first[0], "from") || Properties.isIdent(first[0], "at"))) {
            var i = 0
            if (Properties.isIdent(first[0], "from")) {
                from = first.getOrNull(1)?.let(::angle) ?: return null
                i = 2
            }
            if (i < first.size) {
                if (!Properties.isIdent(first[i], "at")) return null
                val (x, y) = position(first.subList(i + 1, first.size)) ?: return null
                cx = x; cy = y
            }
            stopParts = parts.drop(1)
        }
        // Angles become % of a full turn, so stop resolution works like for the other gradients.
        fun pos(v: ComponentValue): Length? = angle(v)?.let { Length(it / 360f * 100f, "%") }
            ?: Properties.length(v)?.takeIf { it.isPercent }
        val stops = stops(stopParts, ::pos) ?: return null
        return BackgroundLayer.Gradient(radial = false, conic = true, fromAngle = from, centerX = cx, centerY = cy, stops = stops)
    }

    /** `center`, `left top`, `25% 75%`, `right 10px`… (keywords or lengths, 1–2 values). */
    private fun position(values: List<ComponentValue>): Pair<Length, Length>? {
        fun kw(v: ComponentValue): Pair<Char, Float>? = when {
            Properties.isIdent(v, "left") -> 'x' to 0f
            Properties.isIdent(v, "right") -> 'x' to 100f
            Properties.isIdent(v, "top") -> 'y' to 0f
            Properties.isIdent(v, "bottom") -> 'y' to 100f
            Properties.isIdent(v, "center") -> 'c' to 50f
            else -> null
        }
        var x: Length? = null
        var y: Length? = null
        for ((i, v) in values.withIndex()) {
            val k = kw(v)
            if (k != null) {
                when (k.first) {
                    'x' -> x = Length(k.second, "%")
                    'y' -> y = Length(k.second, "%")
                    else -> if (i == 0 && x == null) x = Length(50f, "%") else y = Length(50f, "%")
                }
            } else {
                val l = Properties.length(v) ?: return null
                if (i == 0) x = l else y = l
            }
        }
        return (x ?: Length(50f, "%")) to (y ?: Length(50f, "%"))
    }

    private fun stops(
        parts: List<List<ComponentValue>>,
        position: (ComponentValue) -> Length? = Properties::length,
    ): List<BackgroundLayer.Stop>? {
        val out = ArrayList<BackgroundLayer.Stop>()
        for (p in parts) {
            val w = p.filter { !ws(it) }
            if (w.isEmpty()) return null
            val color = Properties.color(w[0]) ?: return null // color hints without a color are not supported
            when (w.size) {
                1 -> out += BackgroundLayer.Stop(color, null)
                2 -> out += BackgroundLayer.Stop(color, position(w[1]) ?: return null)
                3 -> { // double position: "red 10% 30%"
                    out += BackgroundLayer.Stop(color, position(w[1]) ?: return null)
                    out += BackgroundLayer.Stop(color, position(w[2]) ?: return null)
                }
                else -> return null
            }
        }
        return if (out.size >= 2) out else if (out.size == 1) listOf(out[0], out[0]) else null
    }
}
