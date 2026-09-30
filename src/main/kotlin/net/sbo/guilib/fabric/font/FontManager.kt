package net.sbo.guilib.fabric.font

import net.sbo.guilib.core.layout.FontMetrics
import net.sbo.guilib.core.layout.TextMeasurer
import net.sbo.guilib.core.layout.TextStyle

/**
 * Chooses the font for a [TextStyle] by walking its `font-family` list and measures text with it.
 * Unknown families fall back to the Minecraft font.
 */
object FontManager : TextMeasurer {
    private data class WidthKey(val text: String, val family: String, val size: Float, val weight: Int, val italic: Boolean)

    private val widthCache = object : LinkedHashMap<WidthKey, Float>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<WidthKey, Float>?) = size > 8192
    }

    /** The family that will actually be used for [style]. */
    fun resolveFamily(style: TextStyle): String {
        for (f in style.fontFamily) {
            if (f == "minecraft" || f == "monospace") return "minecraft"
        }
        return "minecraft"
    }

    override fun width(text: String, style: TextStyle): Float {
        val family = resolveFamily(style)
        val key = WidthKey(text, family, style.fontSize, style.fontWeight, style.italic)
        return widthCache.getOrPut(key) { VanillaFont.width(text, style) }
    }

    override fun metrics(style: TextStyle): FontMetrics = VanillaFont.metrics(style)

    fun clearCaches() = widthCache.clear()
}
