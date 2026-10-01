package net.sbo.guilib.core.css

/**
 * One function of a computed `transform` list. Translate and (positive) scale keep boxes axis-aligned and
 * pixel-exact; rotate, skew and matrix are drawn by the backend through a transformed matrix.
 */
sealed interface TransformFn {
    /** Percentages refer to the element's own border box. */
    data class Translate(val x: Dim, val y: Dim) : TransformFn

    data class Scale(val x: Float, val y: Float) : TransformFn

    /** Clockwise rotation in degrees. */
    data class Rotate(val deg: Float) : TransformFn

    /** Skew angles in degrees along x and y. */
    data class Skew(val x: Float, val y: Float) : TransformFn

    /** `matrix(a, b, c, d, tx, ty)`; the translation is in px. */
    data class Matrix(val a: Float, val b: Float, val c: Float, val d: Float, val tx: Float, val ty: Float) : TransformFn
}

/** Computed `transform-origin`; percentages refer to the element's border box. */
data class TransformOrigin(val x: Dim, val y: Dim) {
    companion object {
        val CENTER = TransformOrigin(Dim.Pct(50f), Dim.Pct(50f))
    }
}

/** Parsing of `transform` and `transform-origin`. Lengths stay [Length] until the style engine resolves them. */
internal object TransformParser {
    /** Specified `transform` value: [TranslateValue] items and already computed [TransformFn]s. */
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
        fun angles(): List<Float>? = args.map { angle(it) ?: return null }
        return when (f.name) {
            "translate" -> lengths()?.takeIf { it.size in 1..2 }?.let { TranslateValue(it[0], it.getOrElse(1) { ZERO }) }
            "translatex" -> lengths()?.singleOrNull()?.let { TranslateValue(it, ZERO) }
            "translatey" -> lengths()?.singleOrNull()?.let { TranslateValue(ZERO, it) }
            "scale" -> numbers()?.takeIf { it.size in 1..2 }?.let { TransformFn.Scale(it[0], it.getOrElse(1) { _ -> it[0] }) }
            "scalex" -> numbers()?.singleOrNull()?.let { TransformFn.Scale(it, 1f) }
            "scaley" -> numbers()?.singleOrNull()?.let { TransformFn.Scale(1f, it) }
            "rotate", "rotatez" -> angles()?.singleOrNull()?.let { TransformFn.Rotate(it) }
            "skew" -> angles()?.takeIf { it.size in 1..2 }?.let { TransformFn.Skew(it[0], it.getOrElse(1) { 0f }) }
            "skewx" -> angles()?.singleOrNull()?.let { TransformFn.Skew(it, 0f) }
            "skewy" -> angles()?.singleOrNull()?.let { TransformFn.Skew(0f, it) }
            "matrix" -> args.map { plainNumber(it) ?: return null }.takeIf { it.size == 6 }
                ?.let { TransformFn.Matrix(it[0], it[1], it[2], it[3], it[4], it[5]) }
            else -> null // 3D functions are not supported: the whole declaration is dropped, like invalid CSS
        }
    }

    private fun plainNumber(v: ComponentValue): Float? =
        (v as? TokenValue)?.token?.takeIf { it.type == TokenType.NUMBER }?.number?.toFloat()

    /** `<angle>` in degrees (`deg`, `rad`, `grad`, `turn`; a bare `0` is allowed). */
    private fun angle(v: ComponentValue): Float? {
        val t = (v as? TokenValue)?.token ?: return null
        if (t.type == TokenType.NUMBER && t.number == 0.0) return 0f
        if (t.type != TokenType.DIMENSION) return null
        val n = t.number.toFloat()
        return when (t.unit) {
            "deg" -> n
            "rad" -> n * 180f / kotlin.math.PI.toFloat()
            "grad" -> n * 0.9f
            "turn" -> n * 360f
            else -> null
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
