package net.sbo.guilib.core.css

/**
 * One function of a computed `transform` list. Only axis-aligned functions are supported (translate and scale),
 * so transformed boxes stay rectangles and clipping/hit-testing stay exact.
 */
sealed interface TransformFn {
    /** Percentages refer to the element's own border box. */
    data class Translate(val x: Dim, val y: Dim) : TransformFn

    data class Scale(val x: Float, val y: Float) : TransformFn
}

/** Computed `transform-origin`; percentages refer to the element's border box. */
data class TransformOrigin(val x: Dim, val y: Dim) {
    companion object {
        val CENTER = TransformOrigin(Dim.Pct(50f), Dim.Pct(50f))
    }
}

/** Parsing of `transform` and `transform-origin`. Lengths stay [Length] until the style engine resolves them. */
internal object TransformParser {
    /** Specified `transform` value: [TransformFn.Scale] and [TranslateValue] items. */
    data class TransformValue(val fns: List<Any>)

    data class TranslateValue(val x: Length, val y: Length)

    data class OriginValue(val x: Length, val y: Length)

    private val ZERO = Length(0f, "px")

    fun transform(values: List<ComponentValue>): TransformValue? {
        val words = Properties.words(values)
        if (words.size == 1 && Properties.isIdent(words[0], "none")) return TransformValue(emptyList())
        if (words.isEmpty()) return null
        return TransformValue(words.map { function(it as? FunctionValue ?: return null) ?: return null })
    }

    private fun function(f: FunctionValue): Any? {
        val args = Properties.words(f.args).filter { !(it is TokenValue && it.token.type == TokenType.COMMA) }
        fun lengths(): List<Length>? = args.map { Properties.length(it) ?: return null }
        fun numbers(): List<Float>? = args.map { scaleFactor(it) ?: return null }
        return when (f.name) {
            "translate" -> lengths()?.takeIf { it.size in 1..2 }?.let { TranslateValue(it[0], it.getOrElse(1) { ZERO }) }
            "translatex" -> lengths()?.singleOrNull()?.let { TranslateValue(it, ZERO) }
            "translatey" -> lengths()?.singleOrNull()?.let { TranslateValue(ZERO, it) }
            "scale" -> numbers()?.takeIf { it.size in 1..2 }?.let { TransformFn.Scale(it[0], it.getOrElse(1) { _ -> it[0] }) }
            "scalex" -> numbers()?.singleOrNull()?.let { TransformFn.Scale(it, 1f) }
            "scaley" -> numbers()?.singleOrNull()?.let { TransformFn.Scale(1f, it) }
            else -> null // rotate/skew/matrix are not supported: the whole declaration is dropped, like invalid CSS
        }
    }

    private fun scaleFactor(v: ComponentValue): Float? {
        val t = (v as? TokenValue)?.token ?: return null
        return when (t.type) {
            TokenType.NUMBER -> t.number.toFloat()
            TokenType.PERCENTAGE -> t.number.toFloat() / 100f
            else -> null
        }
    }

    /** `transform-origin: <x> [<y>]` with lengths, percentages or `left|center|right|top|bottom`. */
    fun origin(values: List<ComponentValue>): OriginValue? {
        val words = Properties.words(values)
        if (words.size !in 1..2) return null
        var x: Length? = null
        var y: Length? = null
        val pending = ArrayList<Length>() // lengths and `center`, assigned in order once keywords are placed
        for (w in words) {
            when {
                Properties.isIdent(w, "left") -> x = Length(0f, "%")
                Properties.isIdent(w, "right") -> x = Length(100f, "%")
                Properties.isIdent(w, "top") -> y = Length(0f, "%")
                Properties.isIdent(w, "bottom") -> y = Length(100f, "%")
                Properties.isIdent(w, "center") -> pending += Length(50f, "%")
                else -> pending += Properties.length(w) ?: return null
            }
        }
        for (l in pending) {
            if (x == null) x = l else if (y == null) y = l else return null
        }
        return OriginValue(x ?: Length(50f, "%"), y ?: Length(50f, "%"))
    }
}
