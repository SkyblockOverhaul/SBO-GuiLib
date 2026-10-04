package net.sbo.guilib.core.css

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** One function of a computed `filter` list. */
sealed interface FilterFn {
    /** `brightness() contrast() grayscale() sepia() saturate() hue-rotate() invert() opacity()`. */
    data class ColorFn(val kind: Kind, val amount: Float) : FilterFn {
        enum class Kind(val css: String, val identity: Float) {
            BRIGHTNESS("brightness", 1f), CONTRAST("contrast", 1f), GRAYSCALE("grayscale", 0f), SEPIA("sepia", 0f),
            SATURATE("saturate", 1f), HUE_ROTATE("hue-rotate", 0f), INVERT("invert", 0f), OPACITY("opacity", 1f),
        }

        val matrix: ColorMatrix get() = ColorMatrix.of(kind, amount)
    }

    /** `blur(<radius>)` in px (the Gaussian's standard deviation). */
    data class Blur(val radius: Float) : FilterFn

    /** `drop-shadow(<x> <y> [<blur>] [<color>])` in px; [blur] is the CSS blur radius (standard deviation = blur / 2). */
    data class DropShadow(val offsetX: Float, val offsetY: Float, val blur: Float, val color: Int) : FilterFn

    /** The function with its "no effect" value, used to pad lists when animating (like CSS). */
    fun identity(): FilterFn = when (this) {
        is ColorFn -> ColorFn(kind, kind.identity)
        is Blur -> Blur(0f)
        is DropShadow -> DropShadow(0f, 0f, 0f, Colors.TRANSPARENT)
    }
}

/**
 * The color part of a filter function as the 4×5 matrix of the Filter Effects spec, applied to straight (not
 * premultiplied) sRGB colors in 0..1. Only `opacity()` touches alpha, so alpha is a single factor.
 * [m] holds the rows for red, green and blue: `r g b offset` each.
 */
class ColorMatrix(val m: FloatArray, val alpha: Float) {
    override fun equals(other: Any?) = other is ColorMatrix && other.alpha == alpha && other.m.contentEquals(m)
    override fun hashCode() = m.contentHashCode() * 31 + alpha.hashCode()

    fun apply(argb: Int): Int {
        val a = (argb ushr 24) and 0xFF
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        fun ch(i: Int) = ((m[i] * r + m[i + 1] * g + m[i + 2] * b + m[i + 3]).coerceIn(0f, 1f) * 255f).roundToInt()
        val na = (a * alpha).roundToInt().coerceIn(0, 255)
        return (na shl 24) or (ch(0) shl 16) or (ch(4) shl 8) or ch(8)
    }

    /** Applies the matrix to every pixel of [argb] (straight alpha) in place. */
    fun applyAll(argb: IntArray) {
        for (i in argb.indices) argb[i] = apply(argb[i])
    }

    companion object {
        private fun rgb(vararg rows: Float, alpha: Float = 1f): ColorMatrix {
            // rows: 3 × (r, g, b) without offsets
            return ColorMatrix(
                floatArrayOf(rows[0], rows[1], rows[2], 0f, rows[3], rows[4], rows[5], 0f, rows[6], rows[7], rows[8], 0f),
                alpha,
            )
        }

        /** Matrices from https://drafts.fxtf.org/filter-effects/#supported-filter-functions. */
        fun of(kind: FilterFn.ColorFn.Kind, amount: Float): ColorMatrix = when (kind) {
            FilterFn.ColorFn.Kind.BRIGHTNESS -> rgb(amount, 0f, 0f, 0f, amount, 0f, 0f, 0f, amount)
            FilterFn.ColorFn.Kind.CONTRAST -> {
                val o = 0.5f - 0.5f * amount
                ColorMatrix(floatArrayOf(amount, 0f, 0f, o, 0f, amount, 0f, o, 0f, 0f, amount, o), 1f)
            }
            FilterFn.ColorFn.Kind.GRAYSCALE -> {
                val a = 1f - amount.coerceIn(0f, 1f)
                rgb(
                    0.2126f + 0.7874f * a, 0.7152f - 0.7152f * a, 0.0722f - 0.0722f * a,
                    0.2126f - 0.2126f * a, 0.7152f + 0.2848f * a, 0.0722f - 0.0722f * a,
                    0.2126f - 0.2126f * a, 0.7152f - 0.7152f * a, 0.0722f + 0.9278f * a,
                )
            }
            FilterFn.ColorFn.Kind.SEPIA -> {
                val a = 1f - amount.coerceIn(0f, 1f)
                rgb(
                    0.393f + 0.607f * a, 0.769f - 0.769f * a, 0.189f - 0.189f * a,
                    0.349f - 0.349f * a, 0.686f + 0.314f * a, 0.168f - 0.168f * a,
                    0.272f - 0.272f * a, 0.534f - 0.534f * a, 0.131f + 0.869f * a,
                )
            }
            FilterFn.ColorFn.Kind.SATURATE -> {
                val s = amount
                rgb(
                    0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s,
                    0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s,
                    0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s,
                )
            }
            FilterFn.ColorFn.Kind.HUE_ROTATE -> {
                val rad = Math.toRadians(amount.toDouble())
                val c = cos(rad).toFloat()
                val s = sin(rad).toFloat()
                rgb(
                    0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f,
                    0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f,
                    0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f,
                )
            }
            FilterFn.ColorFn.Kind.INVERT -> {
                val a = amount.coerceIn(0f, 1f)
                val k = 1f - 2f * a
                ColorMatrix(floatArrayOf(k, 0f, 0f, a, 0f, k, 0f, a, 0f, 0f, k, a), 1f)
            }
            FilterFn.ColorFn.Kind.OPACITY -> ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f), amount.coerceIn(0f, 1f))
        }
    }
}

/** Parsing of `filter`. Lengths stay [Length] until the style engine resolves them. */
internal object FilterParser {
    /** Specified value: computed [FilterFn.ColorFn]s plus [BlurValue] / [DropShadowValue] with unresolved lengths. */
    data class FilterValue(val fns: List<Any>)

    data class BlurValue(val radius: Length)

    data class DropShadowValue(val x: Length, val y: Length, val blur: Length?, val color: Any)

    fun filter(values: List<ComponentValue>): FilterValue? {
        val words = Properties.words(values)
        if (words.size == 1 && Properties.isIdent(words[0], "none")) return FilterValue(emptyList())
        if (words.isEmpty()) return null
        // url() references to SVG filters are not supported: the declaration is dropped like invalid CSS.
        return FilterValue(words.map { function(it as? FunctionValue ?: return null) ?: return null })
    }

    private fun function(f: FunctionValue): Any? {
        val args = Properties.words(f.args)
        val name = f.name.lowercase()
        if (name == "drop-shadow") return dropShadow(args)
        if (name == "blur") {
            if (args.isEmpty()) return BlurValue(Length(0f, "px"))
            val l = args.singleOrNull()?.let { Properties.length(it) } ?: return null
            return l.takeIf { !it.isPercent && (it.isCalc || it.value >= 0f) }?.let { BlurValue(it) }
        }
        val kind = FilterFn.ColorFn.Kind.entries.firstOrNull { it.css == name } ?: return null
        if (args.size > 1) return null
        val v = args.singleOrNull()
        if (kind == FilterFn.ColorFn.Kind.HUE_ROTATE) {
            val deg = if (v == null) 0f else angle(v) ?: return null
            return FilterFn.ColorFn(kind, deg)
        }
        // A missing argument means the full effect (1 / 100%); negative amounts are invalid.
        val amount = if (v == null) 1f else amount(v) ?: return null
        return if (amount < 0f) null else FilterFn.ColorFn(kind, amount)
    }

    private fun dropShadow(args: List<ComponentValue>): Any? {
        var color: Any = CurrentColor
        var colorSeen = false
        val lengths = ArrayList<Length>()
        for (v in args) {
            val c = if (colorSeen) null else Properties.color(v)
            when {
                c != null -> { color = c; colorSeen = true }
                else -> lengths += Properties.length(v)?.takeIf { !it.isPercent } ?: return null
            }
        }
        if (lengths.size !in 2..3) return null
        if (lengths.size == 3 && !lengths[2].isCalc && lengths[2].value < 0f) return null
        return DropShadowValue(lengths[0], lengths[1], lengths.getOrNull(2), color)
    }

    private fun amount(v: ComponentValue): Float? {
        val t = (v as? TokenValue)?.token ?: return null
        return when (t.type) {
            TokenType.NUMBER -> t.number.toFloat()
            TokenType.PERCENTAGE -> t.number.toFloat() / 100f
            else -> null
        }
    }

    private fun angle(v: ComponentValue): Float? {
        val t = (v as? TokenValue)?.token ?: return null
        if (t.type == TokenType.NUMBER && t.number == 0.0) return 0f
        if (t.type != TokenType.DIMENSION) return null
        val n = t.number.toFloat()
        return when (t.unit.lowercase()) {
            "deg" -> n
            "rad" -> n * 180f / kotlin.math.PI.toFloat()
            "grad" -> n * 0.9f
            "turn" -> n * 360f
            else -> null
        }
    }
}
