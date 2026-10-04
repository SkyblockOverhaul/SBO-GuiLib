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
    /** `letter-spacing` in px, added after every character (grapheme). Measurers never see it: see [LetterSpacing]. */
    val letterSpacing: Float = 0f,
    /** `§k`: drawn as random characters that keep changing, like Minecraft; measured as the real text. */
    val obfuscated: Boolean = false,
    /** RGB set by a `§0`–`§f` code (`null` = the CSS `color`); kept when the CSS color changes, see [repaintedWith]. */
    val codeColor: Int? = null,
    /** Decorations added by `§n` / `§m`, on top of the CSS `text-decoration`. */
    val codeDecoration: TextDecoration = TextDecoration.NONE,
) {
    val bold get() = fontWeight >= 600

    /**
     * This style with the paint-only properties (color, decoration, shadow) taken from [live]: they change without a
     * new layout (e.g. `button:disabled` → enabled), so text laid out earlier must not keep the old ones.
     */
    fun repaintedWith(live: ComputedStyle): TextStyle {
        val c = codeColor?.let { (live.color and 0xFF000000.toInt()) or it } ?: live.color
        val d = live.textDecoration.let {
            if (codeDecoration == TextDecoration.NONE) it
            else TextDecoration(it.underline || codeDecoration.underline, it.lineThrough || codeDecoration.lineThrough)
        }
        return if (c == color && d == decoration && live.textShadow == shadow) this
        else copy(color = c, decoration = d, shadow = live.textShadow)
    }

    companion object {
        fun of(style: ComputedStyle) = TextStyle(
            style.fontFamily, style.fontSize, style.fontWeight, style.isItalic, style.color, style.textDecoration, style.textShadow,
            style.letterSpacing,
        )
    }
}

/**
 * Adds `letter-spacing` on top of a backend [TextMeasurer]: the backend measures without it, and every grapheme
 * gets [TextStyle.letterSpacing] after it (also the last one, like browsers). Renderers draw per grapheme the same way.
 */
object LetterSpacing {
    fun wrap(inner: TextMeasurer): TextMeasurer = if (inner is Wrapped) inner else Wrapped(inner)

    /** The graphemes of [text] (user-perceived characters, e.g. an emoji with modifiers). */
    fun graphemes(text: String): List<String> {
        val b = java.text.BreakIterator.getCharacterInstance().also { it.setText(text) }
        val out = ArrayList<String>()
        var start = b.first()
        var end = b.next()
        while (end != java.text.BreakIterator.DONE) {
            out += text.substring(start, end)
            start = end
            end = b.next()
        }
        return out
    }

    private fun count(text: String): Int {
        if (text.isEmpty()) return 0
        val b = java.text.BreakIterator.getCharacterInstance().also { it.setText(text) }
        var n = 0
        while (b.next() != java.text.BreakIterator.DONE) n++
        return n
    }

    private class Wrapped(val inner: TextMeasurer) : TextMeasurer {
        override fun width(text: String, style: TextStyle): Float {
            val ls = style.letterSpacing
            if (ls == 0f) return inner.width(text, style)
            return inner.width(text, style.copy(letterSpacing = 0f)) + ls * count(text)
        }

        override fun metrics(style: TextStyle) = inner.metrics(if (style.letterSpacing == 0f) style else style.copy(letterSpacing = 0f))

        override fun fontFaces(faces: List<net.sbo.guilib.core.css.FontFace>) = inner.fontFaces(faces)
    }
}

/** Vertical font metrics in GUI pixels for a given [TextStyle]. */
data class FontMetrics(val ascent: Float, val descent: Float, val normalLineHeight: Float)

/** Supplied by the rendering backend; the core only needs widths and metrics. Implementations should cache. */
interface TextMeasurer {
    fun width(text: String, style: TextStyle): Float
    fun metrics(style: TextStyle): FontMetrics

    /** The `@font-face` rules of a document's stylesheets (called whenever they are set). */
    fun fontFaces(faces: List<net.sbo.guilib.core.css.FontFace>) {}
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
                    in '0'..'9', in 'a'..'f' -> base.copy(color = (base.color and 0xFF000000.toInt()) or COLORS[code.digitToInt(16)], codeColor = COLORS[code.digitToInt(16)])
                    'l' -> style.copy(fontWeight = 700)
                    'o' -> style.copy(italic = true)
                    'n' -> style.copy(decoration = style.decoration.copy(underline = true), codeDecoration = style.codeDecoration.copy(underline = true))
                    'm' -> style.copy(decoration = style.decoration.copy(lineThrough = true), codeDecoration = style.codeDecoration.copy(lineThrough = true))
                    'k' -> style.copy(obfuscated = true)
                    'r' -> base
                    else -> style // unknown codes are ignored
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
