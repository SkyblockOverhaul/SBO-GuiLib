package net.sbo.guilib.fabric.font

import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.sbo.guilib.core.Log
import net.sbo.guilib.core.css.FontFace
import net.sbo.guilib.core.layout.FontMetrics
import net.sbo.guilib.core.layout.TextMeasurer
import net.sbo.guilib.core.layout.TextStyle
import kotlin.math.roundToInt

/**
 * Resolves `font-family` / `font-weight` / `font-style` to a font and measures text.
 *
 * Built in: `inter` (default, bundled, OFL) and `minecraft` (vanilla font; alias `monospace`).
 * Mods add fonts with [register] or with `@font-face` in a stylesheet. Characters missing in a TTF fall back to the
 * Minecraft font.
 * Text is laid out at the exact physical pixel size (`font-size × GUI scale`), so it stays sharp.
 */
object FontManager : TextMeasurer {

    /** One face of a family, for the weights [min]..[max] (a single weight unless it is a variable font). */
    private data class Face(val min: Int, val max: Int, val italic: Boolean)

    private class Family(val faces: MutableMap<Face, String> = LinkedHashMap())

    private val families = HashMap<String, Family>()
    private val loaded = HashMap<String, TrueTypeFont?>()

    /** Registers a TTF/OTF resource (`"mymod:fonts/x.ttf"`) as [family] with the given weight/style. */
    fun register(family: String, weight: Int, italic: Boolean, location: String) = register(family, weight, weight, italic, location)

    /** Like [register] for a weight range (`font-weight: 100 900` of a variable font; drawn at its default weight). */
    fun register(family: String, minWeight: Int, maxWeight: Int, italic: Boolean, location: String) {
        val faces = families.getOrPut(family.lowercase()) { Family() }.faces
        val face = Face(minWeight, maxWeight, italic)
        if (faces[face] == location) return
        faces[face] = location
        widthCache.clear()
    }

    /**
     * `@font-face` rules of a stylesheet. Like [register] they are global (fonts are resources, shared by every
     * screen); a later rule for the same family, weight and style replaces the earlier one (e.g. after a hot reload).
     */
    override fun fontFaces(faces: List<FontFace>) {
        for (f in faces) {
            val location = f.sources.firstOrNull { src ->
                val id = Identifier.tryParse(src)
                id != null && Minecraft.getInstance().resourceManager.getResource(id).isPresent
            }
            if (location == null) {
                Log.warnOnce("GuiLib: @font-face '${f.family}': none of ${f.sources} was found (expected resource locations like 'mymod:fonts/x.ttf')")
                continue
            }
            register(f.family, f.weightMin, f.weightMax, f.italic, location)
        }
    }

    /** A run of text drawn with one font: `font == null` means the Minecraft font. */
    class Segment(val text: String, val font: TrueTypeFont?)

    private const val MINECRAFT = "minecraft"

    private fun familyName(style: TextStyle): String {
        for (f in style.fontFamily) {
            if (f == MINECRAFT || f == "monospace") return MINECRAFT
            if (f in families) return f
        }
        return "inter"
    }

    /** The TTF for [style], or `null` for the Minecraft font. */
    fun fontFor(style: TextStyle): TrueTypeFont? {
        val family = families[familyName(style)] ?: return null
        // Closest weight within the same style (italic falls back to upright).
        val candidates = family.faces.entries.filter { it.key.italic == style.italic }.ifEmpty { family.faces.entries.toList() }
        val w = style.fontWeight
        val best = candidates.minByOrNull { (f, _) -> if (w < f.min) f.min - w else if (w > f.max) w - f.max else 0 } ?: return null
        return load(best.value)
    }

    private fun load(location: String): TrueTypeFont? {
        if (loaded.containsKey(location)) return loaded[location]
        val font = try {
            val id = Identifier.parse(location)
            val res = Minecraft.getInstance().resourceManager.getResource(id)
            if (res.isEmpty) {
                Log.warn("GuiLib: font '$location' not found"); null
            } else TrueTypeFont(location, res.get().open().use { it.readBytes() })
        } catch (e: Throwable) {
            Log.warn("GuiLib: failed to load font '$location': $e")
            null
        }
        loaded[location] = font
        return font
    }

    /**
     * Scale of the screen being laid out / drawn right now when it has its own (`useScreenScale`), set by
     * [net.sbo.guilib.fabric.GuiLibScreen] around its frame and input handling. Render thread only.
     */
    @Volatile
    var activeScale: Float? = null

    /** Physical pixels per CSS px: the active screen's own scale, else Minecraft's GUI scale. */
    fun guiScale(): Float = activeScale ?: Minecraft.getInstance().window.guiScale.toFloat().coerceAtLeast(1f)

    /** Physical pixel size used to rasterize [style]. */
    fun pixelSize(style: TextStyle): Int = (style.fontSize * guiScale()).roundToInt().coerceAtLeast(1)

    /** Splits [text] into runs of the style's font and Minecraft-font fallbacks for missing glyphs. */
    private data class SegmentKey(val text: String, val font: TrueTypeFont)

    /** Splits of recently drawn texts (the font's glyph coverage doesn't change). */
    private val segmentCache = object : LinkedHashMap<SegmentKey, List<Segment>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SegmentKey, List<Segment>>?) = size > 2048
    }

    fun segments(text: String, style: TextStyle): List<Segment> {
        val font = fontFor(style) ?: return listOf(Segment(text, null))
        return segmentCache.getOrPut(SegmentKey(text, font)) { split(text, font) }
    }

    private fun split(text: String, font: TrueTypeFont): List<Segment> {
        val out = ArrayList<Segment>()
        val sb = StringBuilder()
        var current: TrueTypeFont? = font
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val f = if (cp == ' '.code || font.hasGlyph(cp)) font else null
            if (f !== current && sb.isNotEmpty()) {
                out += Segment(sb.toString(), current); sb.clear()
            }
            current = f
            sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        if (sb.isNotEmpty()) out += Segment(sb.toString(), current)
        return out
    }

    private data class WidthKey(val text: String, val family: List<String>, val size: Float, val weight: Int, val italic: Boolean, val scale: Float)

    private val widthCache = object : LinkedHashMap<WidthKey, Float>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<WidthKey, Float>?) = size > 16384
    }

    override fun width(text: String, style: TextStyle): Float {
        val scale = guiScale()
        val key = WidthKey(text, style.fontFamily, style.fontSize, style.fontWeight, style.italic, scale)
        return widthCache.getOrPut(key) {
            var w = 0f
            for (seg in segments(text, style)) {
                w += if (seg.font == null) VanillaFont.width(seg.text, style) else ttfWidth(seg.text, seg.font, pixelSize(style)) / scale
            }
            w
        }
    }

    /** Width in physical pixels. */
    fun ttfWidth(text: String, font: TrueTypeFont, px: Int): Float {
        var w = 0f
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            w += font.advance(cp, px)
            i += Character.charCount(cp)
        }
        return w
    }

    override fun metrics(style: TextStyle): FontMetrics {
        val font = fontFor(style) ?: return VanillaFont.metrics(style)
        val scale = guiScale()
        val m = font.metrics(pixelSize(style))
        return FontMetrics(m.ascent / scale, m.descent / scale, m.lineHeight / scale)
    }

    fun clearCaches() {
        widthCache.clear()
        segmentCache.clear()
        GlyphAtlas.clear()
        loaded.values.forEach { it?.close() }
        loaded.clear()
    }

    // Must run after all fields above are initialised.
    init {
        register("inter", 400, false, "guilib:fonts/inter-regular.ttf")
        register("inter", 500, false, "guilib:fonts/inter-medium.ttf")
        register("inter", 600, false, "guilib:fonts/inter-semibold.ttf")
        register("inter", 700, false, "guilib:fonts/inter-bold.ttf")
        register("inter", 400, true, "guilib:fonts/inter-italic.ttf")
        register("inter", 700, true, "guilib:fonts/inter-bolditalic.ttf")
    }
}
