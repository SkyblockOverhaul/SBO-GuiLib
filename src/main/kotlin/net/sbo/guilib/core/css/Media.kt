package net.sbo.guilib.core.css

import kotlin.math.abs

/**
 * The comma-separated queries of one `@media` rule; it applies if any query matches. Invalid queries never match
 * (like `not all` on the web), the others still work.
 */
class MediaQueryList(val queries: List<MediaQuery>, val text: String) {
    fun matches(ctx: StyleContext) = queries.any { it.matches(ctx) }
    override fun toString() = text
}

/** `[not | only] [type] [and (condition) …]` or `(condition) [and | or (condition) …]`. */
class MediaQuery(private val negated: Boolean, private val typeMatches: Boolean, private val anyOf: Boolean, private val conditions: List<(StyleContext) -> Boolean>) {
    fun matches(ctx: StyleContext): Boolean {
        val cond = if (conditions.isEmpty()) true else if (anyOf) conditions.any { it(ctx) } else conditions.all { it(ctx) }
        return (typeMatches && cond) != negated
    }

    companion object {
        val NEVER = MediaQuery(false, false, false, emptyList())
    }
}

/**
 * Parses `@media` preludes. Media features, in GUI pixels: `width`, `height` (the screen), `aspect-ratio`,
 * `orientation`, `resolution` (Minecraft's GUI scale in `dppx`/`x`, like a browser's devicePixelRatio), plus
 * `hover`, `any-hover` (hover), `pointer`, `any-pointer` (fine), `prefers-reduced-motion` (no-preference) and
 * `prefers-color-scheme` (dark). `min-`/`max-` prefixes and range syntax (`width >= 400px`, `300px < width < 600px`) work.
 */
object MediaParser {
    /** Problems are reported through [warn]; the affected query never matches. */
    fun parse(prelude: List<ComponentValue>, warn: (String) -> Unit): MediaQueryList {
        val text = prelude.joinToString("").trim()
        val queries = BackgroundParser.splitCommas(prelude).map { part ->
            val words = part.filter { !(it is TokenValue && it.token.type == TokenType.WHITESPACE) }
            query(words) ?: run {
                warn("invalid media query '${part.joinToString("").trim()}'; it never matches")
                MediaQuery.NEVER
            }
        }
        return MediaQueryList(queries, text)
    }

    private fun ident(v: ComponentValue?): String? = ((v as? TokenValue)?.token)?.takeIf { it.type == TokenType.IDENT }?.text?.lowercase()

    private fun query(words: List<ComponentValue>): MediaQuery? {
        if (words.isEmpty()) return null
        var i = 0
        var negated = false
        when (ident(words[0])) {
            "not" -> { negated = true; i++ }
            "only" -> i++
        }
        var typeMatches = true
        val type = ident(words.getOrNull(i))
        if (type != null) {
            typeMatches = when (type) {
                "all", "screen" -> true
                "print", "speech" -> false
                else -> return null
            }
            i++
            if (i < words.size && ident(words[i]) != "and") return null
            if (i < words.size) i++
        }
        val conditions = ArrayList<(StyleContext) -> Boolean>()
        var combinator: String? = null
        while (i < words.size) {
            val block = words[i] as? BlockValue ?: return null
            if (block.open != '(') return null
            conditions += condition(block.content) ?: return null
            i++
            if (i < words.size) {
                val c = ident(words[i]) ?: return null
                if (c != "and" && c != "or") return null
                if (combinator != null && combinator != c) return null // mixing needs parentheses
                if (c == "or" && type != null) return null
                combinator = c
                i++
                if (i >= words.size) return null
            }
        }
        if (type == null && conditions.isEmpty()) return null
        return MediaQuery(negated, typeMatches, combinator == "or", conditions)
    }

    private fun condition(content: List<ComponentValue>): ((StyleContext) -> Boolean)? {
        val c = content.filter { !(it is TokenValue && it.token.type == TokenType.WHITESPACE) }
        if (c.isEmpty()) return null
        // Nested condition: ((a) and (b)), (not (a))
        if (c[0] is BlockValue || ident(c[0]) == "not") {
            val q = query(c) ?: return null
            return { q.matches(it) }
        }
        val colon = c.indexOfFirst { it is TokenValue && it.token.type == TokenType.COLON }
        if (colon >= 0) {
            val name = ident(c[0]) ?: return null
            if (colon != 1) return null
            val value = c.subList(2, c.size)
            return when {
                name.startsWith("min-") -> range(name.removePrefix("min-"), value, ">=")
                name.startsWith("max-") -> range(name.removePrefix("max-"), value, "<=")
                else -> discrete(name, value) ?: range(name, value, "=")
            }
        }
        if (c.size == 1) {
            // Boolean context: true unless the feature is "zero"/"none".
            val name = ident(c[0]) ?: return null
            return when (name) {
                "width" -> { ctx -> ctx.viewportWidth > 0f }
                "height" -> { ctx -> ctx.viewportHeight > 0f }
                "aspect-ratio", "resolution", "orientation", "hover", "any-hover", "pointer", "any-pointer", "color" -> { _ -> true }
                "prefers-reduced-motion", "grid", "monochrome", "inverted-colors" -> { _ -> false }
                else -> null
            }
        }
        // Range syntax: feature op value | value op feature | value op feature op value.
        val parts = ArrayList<Any>() // ComponentValue groups and operator strings
        var cur = ArrayList<ComponentValue>()
        var j = 0
        while (j < c.size) {
            val t = (c[j] as? TokenValue)?.token
            if (t != null && t.type == TokenType.DELIM && t.text in listOf("<", ">", "=")) {
                var op = t.text
                val next = (c.getOrNull(j + 1) as? TokenValue)?.token
                if (op != "=" && next != null && next.isDelim('=')) { op += "="; j++ }
                if (cur.isEmpty()) return null
                parts.add(cur); parts.add(op); cur = ArrayList()
            } else cur += c[j]
            j++
        }
        if (cur.isEmpty()) return null
        parts.add(cur)
        @Suppress("UNCHECKED_CAST")
        fun group(k: Int) = parts[k] as List<ComponentValue>
        fun featureName(g: List<ComponentValue>) = if (g.size == 1) ident(g[0])?.takeIf { it in RANGE_FEATURES } else null
        return when (parts.size) {
            3 -> {
                val op = parts[1] as String
                val left = featureName(group(0))
                if (left != null) range(left, group(2), op)
                else range(featureName(group(2)) ?: return null, group(0), flip(op))
            }
            5 -> {
                val name = featureName(group(2)) ?: return null
                val a = range(name, group(0), flip(parts[1] as String)) ?: return null
                val b = range(name, group(4), parts[3] as String) ?: return null
                ({ ctx -> a(ctx) && b(ctx) })
            }
            else -> null
        }
    }

    private val RANGE_FEATURES = setOf("width", "height", "aspect-ratio", "resolution")

    private fun flip(op: String) = when (op) {
        "<" -> ">"
        "<=" -> ">="
        ">" -> "<"
        ">=" -> "<="
        else -> op
    }

    private fun discrete(name: String, value: List<ComponentValue>): ((StyleContext) -> Boolean)? {
        val v = if (value.size == 1) ident(value[0]) else null
        return when (name) {
            "orientation" -> when (v) {
                "portrait" -> { ctx -> ctx.viewportHeight >= ctx.viewportWidth }
                "landscape" -> { ctx -> ctx.viewportWidth > ctx.viewportHeight }
                else -> null
            }
            "hover", "any-hover" -> constant(v, "hover", "none")
            "pointer", "any-pointer" -> constant(v, "fine", "coarse", "none")
            "prefers-reduced-motion" -> constant(v, "no-preference", "reduce")
            "prefers-color-scheme" -> constant(v, "dark", "light")
            else -> null
        }
    }

    /** Matches when [v] is [yes]; `null` (invalid) when it's none of the allowed keywords. */
    private fun constant(v: String?, yes: String, vararg no: String): ((StyleContext) -> Boolean)? = when (v) {
        yes -> { _ -> true }
        in no -> { _ -> false }
        else -> null
    }

    private fun range(name: String, value: List<ComponentValue>, op: String): ((StyleContext) -> Boolean)? {
        val actual: (StyleContext) -> Float = when (name) {
            "width" -> { ctx -> ctx.viewportWidth }
            "height" -> { ctx -> ctx.viewportHeight }
            "aspect-ratio" -> { ctx -> ctx.viewportWidth / ctx.viewportHeight.coerceAtLeast(0.0001f) }
            "resolution" -> { ctx -> ctx.resolution }
            else -> return null
        }
        val target: (StyleContext) -> Float = when (name) {
            "aspect-ratio" -> ratio(value)?.let { r -> { _: StyleContext -> r } }
            "resolution" -> resolution(value)?.let { r -> { _: StyleContext -> r } }
            else -> length(value)
        } ?: return null
        return { ctx ->
            val a = actual(ctx)
            val b = target(ctx)
            when (op) {
                "<" -> a < b - EPS
                "<=" -> a <= b + EPS
                ">" -> a > b + EPS
                ">=" -> a >= b - EPS
                else -> abs(a - b) <= EPS
            }
        }
    }

    private const val EPS = 0.001f

    private fun single(value: List<ComponentValue>) = if (value.size == 1) (value[0] as? TokenValue)?.token else null

    private fun length(value: List<ComponentValue>): ((StyleContext) -> Float)? {
        val t = single(value) ?: return null
        val n = t.number.toFloat()
        return when {
            t.type == TokenType.NUMBER && n == 0f -> { _ -> 0f }
            t.type != TokenType.DIMENSION -> null
            t.unit.equals("px", true) -> { _ -> n }
            // Like the web, em/rem in media queries use the initial font size, not the document's.
            t.unit.equals("em", true) || t.unit.equals("rem", true) -> { _ -> n * (Prop.FONT_SIZE.initial as Float) }
            else -> null
        }
    }

    private fun ratio(value: List<ComponentValue>): Float? {
        val nums = value.mapNotNull { (it as? TokenValue)?.token }
        return when {
            nums.size == 1 && nums[0].type == TokenType.NUMBER -> nums[0].number.toFloat()
            nums.size == 3 && nums[0].type == TokenType.NUMBER && nums[1].isDelim('/') && nums[2].type == TokenType.NUMBER && nums[2].number != 0.0 ->
                (nums[0].number / nums[2].number).toFloat()
            else -> null
        }?.takeIf { value.size == nums.size }
    }

    private fun resolution(value: List<ComponentValue>): Float? {
        val t = single(value) ?: return null
        if (t.type != TokenType.DIMENSION) return null
        val n = t.number.toFloat()
        return when (t.unit.lowercase()) {
            "dppx", "x" -> n
            "dpi" -> n / 96f
            "dpcm" -> n * 2.54f / 96f
            else -> null
        }
    }
}
