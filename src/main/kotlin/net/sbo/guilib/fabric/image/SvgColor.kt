package net.sbo.guilib.fabric.image

/**
 * `currentColor` for SVG images: GuiLib passes the element's CSS `color` into the SVG (browsers don't do this for
 * `<img>`, only for inline SVG), so one icon file can be tinted from CSS. JSVG takes `currentColor` from the `color`
 * attribute, so the color is set on the root `<svg>` element before parsing.
 */
internal object SvgColor {
    private val CURRENT_COLOR = Regex("currentcolor", RegexOption.IGNORE_CASE)
    private val ROOT = Regex("<svg\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val COLOR_ATTR = Regex("\\scolor\\s*=", RegexOption.IGNORE_CASE)

    fun usesCurrentColor(source: String) = CURRENT_COLOR.containsMatchIn(source)

    /**
     * [source] with `color` = [argb] on the root element. A `color` the file sets there itself wins, like a
     * presentation attribute on an inline SVG beats the inherited CSS color.
     */
    fun withCurrentColor(source: String, argb: Int): String {
        val root = ROOT.find(source) ?: return source
        if (COLOR_ATTR.containsMatchIn(root.value)) return source
        val insertAt = root.range.first + 4 // after "<svg"
        return source.substring(0, insertAt) + " color=\"${css(argb)}\"" + source.substring(insertAt)
    }

    private fun css(argb: Int): String {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val a = argb ushr 24
        return if (a == 0xFF) "#%02x%02x%02x".format(r, g, b) else "rgba($r, $g, $b, ${"%.4f".format(java.util.Locale.ROOT, a / 255f)})"
    }
}
