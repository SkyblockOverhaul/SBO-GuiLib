package net.sbo.guilib.core.layout

import net.sbo.guilib.core.css.ComputedStyle
import net.sbo.guilib.core.css.TextDecoration
import net.sbo.guilib.core.css.TextShadow

/** Everything needed to measure and draw a run of text. */
data class TextStyle(
    val fontFamily: List<String>,
    val fontSize: Float,
    val fontWeight: Int,
    val italic: Boolean,
    val color: Int,
    val decoration: TextDecoration = TextDecoration.NONE,
    val shadow: TextShadow? = null,
) {
    val bold get() = fontWeight >= 600

    companion object {
        fun of(style: ComputedStyle) = TextStyle(
            style.fontFamily, style.fontSize, style.fontWeight, style.isItalic, style.color, style.textDecoration, style.textShadow,
        )
    }
}

/** Vertical font metrics in GUI pixels for a given [TextStyle]. */
data class FontMetrics(val ascent: Float, val descent: Float, val normalLineHeight: Float)

/** Supplied by the rendering backend; the core only needs widths and metrics. Implementations should cache. */
interface TextMeasurer {
    fun width(text: String, style: TextStyle): Float
    fun metrics(style: TextStyle): FontMetrics
}

/**
 * Minecraft `§` formatting codes. Text containing them is split into runs with the matching color/decoration,
 * so the rest of the library never sees the codes.
 */
object FormattingCodes {
    const val SECTION = '§'

    private val COLORS = intArrayOf(
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
    )

    fun hasCodes(text: String) = text.indexOf(SECTION) >= 0

    /** Removes all formatting codes. */
    fun strip(text: String): String = if (!hasCodes(text)) text else buildString {
        var i = 0
        while (i < text.length) {
            if (text[i] == SECTION && i + 1 < text.length) i += 2 else append(text[i++])
        }
    }

    /** Splits [text] into (text, style) runs, starting from [base]. `§r` resets to [base]. */
    fun parse(text: String, base: TextStyle): List<Pair<String, TextStyle>> {
        if (!hasCodes(text)) return listOf(text to base)
        val out = ArrayList<Pair<String, TextStyle>>()
        var style = base
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == SECTION && i + 1 < text.length) {
                val code = text[i + 1].lowercaseChar()
                val next = when (code) {
                    in '0'..'9', in 'a'..'f' -> base.copy(color = (base.color and 0xFF000000.toInt()) or COLORS[code.digitToInt(16)])
                    'l' -> style.copy(fontWeight = 700)
                    'o' -> style.copy(italic = true)
                    'n' -> style.copy(decoration = style.decoration.copy(underline = true))
                    'm' -> style.copy(decoration = style.decoration.copy(lineThrough = true))
                    'r' -> base
                    else -> style // §k (obfuscated) and unknown codes are ignored
                }
                if (next != style) {
                    if (sb.isNotEmpty()) {
                        out += sb.toString() to style; sb.clear()
                    }
                    style = next
                }
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        if (sb.isNotEmpty()) out += sb.toString() to style
        return out
    }
}
