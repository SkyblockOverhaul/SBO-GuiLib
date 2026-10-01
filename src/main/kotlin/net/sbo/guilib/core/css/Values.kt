package net.sbo.guilib.core.css

import kotlin.math.roundToInt

/**
 * A length as written in CSS, before context-dependent units (em, vw, …) are resolved.
 * `calc()`/`min()`/`max()`/`clamp()` are represented with unit `"calc"` and the expression in [calc].
 */
data class Length(val value: Float, val unit: String, val calc: CalcNode? = null) {
    val isPercent get() = unit == "%"
    val isCalc get() = calc != null
}

/**
 * A computed size. `em/rem/vw/vh` are already converted to [Px]; percentages stay [Pct]
 * until layout knows the containing block.
 */
sealed interface Dim {
    data object Auto : Dim
    /** `none` for max-width / max-height. */
    data object None : Dim
    data class Px(val px: Float) : Dim
    data class Pct(val pct: Float) : Dim
    /** `calc()` & co. containing percentages; evaluated against the containing block during layout. */
    data class Calc(val node: CalcNode) : Dim

    /** Resolves against [base] (the containing block size); `null` for auto/none or percentages of an indefinite size. */
    fun resolve(base: Float?): Float? = when (this) {
        is Px -> px
        is Pct -> base?.let { it * pct / 100f }
        is Calc -> node.eval(base)
        Auto, None -> null
    }

    companion object {
        val ZERO = Px(0f)
    }
}

sealed interface LineHeight {
    data object Normal : LineHeight
    data class Multiplier(val factor: Float) : LineHeight
    data class Px(val px: Float) : LineHeight

    fun resolve(fontSize: Float, normalFactor: Float): Float = when (this) {
        Normal -> fontSize * normalFactor
        is Multiplier -> fontSize * factor
        is Px -> px
    }
}

/** Marker used while parsing; replaced by the element's `color` during computation. */
data object CurrentColor

enum class Display { BLOCK, INLINE, INLINE_BLOCK, FLEX, INLINE_FLEX, GRID, INLINE_GRID, NONE;
    val isFlex get() = this == FLEX || this == INLINE_FLEX
    val isGrid get() = this == GRID || this == INLINE_GRID
    val isInlineLevel get() = this == INLINE || this == INLINE_BLOCK || this == INLINE_FLEX || this == INLINE_GRID
}
enum class Position { STATIC, RELATIVE, ABSOLUTE, FIXED }
enum class BoxSizing { BORDER_BOX, CONTENT_BOX }
enum class FlexDirection { ROW, ROW_REVERSE, COLUMN, COLUMN_REVERSE;
    val isRow get() = this == ROW || this == ROW_REVERSE
    val isReverse get() = this == ROW_REVERSE || this == COLUMN_REVERSE
}
enum class FlexWrap { NOWRAP, WRAP, WRAP_REVERSE }
enum class JustifyContent { FLEX_START, FLEX_END, CENTER, SPACE_BETWEEN, SPACE_AROUND, SPACE_EVENLY }
enum class AlignItems { STRETCH, FLEX_START, FLEX_END, CENTER, BASELINE }
enum class AlignSelf { AUTO, STRETCH, FLEX_START, FLEX_END, CENTER, BASELINE }
enum class Overflow { VISIBLE, HIDDEN, SCROLL, AUTO;
    val clips get() = this != VISIBLE
    val scrolls get() = this == SCROLL || this == AUTO
}
enum class TextAlign { LEFT, CENTER, RIGHT }
enum class WhiteSpace { NORMAL, NOWRAP, PRE, PRE_WRAP }
enum class TextOverflow { CLIP, ELLIPSIS }
enum class Visibility { VISIBLE, HIDDEN }
enum class BorderStyle { NONE, HIDDEN, SOLID, DASHED, DOTTED }
enum class PointerEvents { AUTO, NONE }
enum class FontStyle { NORMAL, ITALIC }
enum class ObjectFit { FILL, CONTAIN, COVER, NONE, SCALE_DOWN }
enum class Cursor { AUTO, DEFAULT, POINTER, TEXT, NOT_ALLOWED, CROSSHAIR, MOVE, NS_RESIZE, EW_RESIZE, GRAB }
enum class UserSelect { AUTO, NONE, TEXT }

data class TextDecoration(val underline: Boolean = false, val lineThrough: Boolean = false) {
    companion object {
        val NONE = TextDecoration()
    }
}

/** `text-shadow`: MVP supports `none` or a single shadow; Minecraft-style 1px shadows are the common case. */
data class TextShadow(val offsetX: Float, val offsetY: Float, val color: Any /* Int or CurrentColor */) {
    companion object {
        val NONE: TextShadow? = null
    }
}

/** Colors are stored as packed ARGB ints, like Minecraft uses them. */
object Colors {
    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF

    /** Multiplies the alpha channel of [c] by [factor] (0..1). */
    fun withOpacity(c: Int, factor: Float): Int = (c and 0x00FFFFFF) or ((alpha(c) * factor).roundToInt().coerceIn(0, 255) shl 24)

    const val TRANSPARENT = 0
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    fun parseHex(hex: String): Int? {
        if (hex.any { !(it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F') }) return null
        fun d(i: Int) = hex[i].digitToInt(16)
        return when (hex.length) {
            3 -> argb(255, d(0) * 17, d(1) * 17, d(2) * 17)
            4 -> argb(d(3) * 17, d(0) * 17, d(1) * 17, d(2) * 17)
            6 -> argb(255, hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
            8 -> argb(hex.substring(6, 8).toInt(16), hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
            else -> null
        }
    }

    fun hslToArgb(h: Float, s: Float, l: Float, a: Float): Int {
        val hue = ((h % 360f) + 360f) % 360f / 360f
        fun f(n: Float): Float {
            val k = (n + hue * 12f) % 12f
            val amp = s * minOf(l, 1f - l)
            return l - amp * maxOf(-1f, minOf(k - 3f, 9f - k, 1f))
        }
        return argb((a * 255).roundToInt(), (f(0f) * 255).roundToInt(), (f(8f) * 255).roundToInt(), (f(4f) * 255).roundToInt())
    }

    /** HSV (h 0..360, s/v/a 0..1) to ARGB. */
    fun hsvToArgb(h: Float, s: Float, v: Float, a: Float = 1f): Int {
        val hh = ((h % 360f) + 360f) % 360f / 60f
        val c = v * s
        val x = c * (1f - kotlin.math.abs(hh % 2f - 1f))
        val (r, g, b) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        return argb((a * 255).roundToInt(), ((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
    }

    /** ARGB to HSV: `floatArrayOf(h 0..360, s, v, a)`. Hue is 0 for greys. */
    fun argbToHsv(c: Int): FloatArray {
        val r = red(c) / 255f
        val g = green(c) / 255f
        val b = blue(c) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(h, if (max == 0f) 0f else d / max, max, alpha(c) / 255f)
    }

    /** `#rrggbb`, or `#rrggbbaa` when [withAlpha] (or the color isn't opaque). */
    fun toHex(c: Int, withAlpha: Boolean = false): String {
        val rgb = "#%02x%02x%02x".format(red(c), green(c), blue(c))
        return if (withAlpha || alpha(c) != 255) rgb + "%02x".format(alpha(c)) else rgb
    }

    /** CSS named colors (CSS Color Level 4). */
    val NAMED: Map<String, Int> = """
        aliceblue f0f8ff antiquewhite faebd7 aqua 00ffff aquamarine 7fffd4 azure f0ffff beige f5f5dc bisque ffe4c4 black 000000
        blanchedalmond ffebcd blue 0000ff blueviolet 8a2be2 brown a52a2a burlywood deb887 cadetblue 5f9ea0 chartreuse 7fff00
        chocolate d2691e coral ff7f50 cornflowerblue 6495ed cornsilk fff8dc crimson dc143c cyan 00ffff darkblue 00008b
        darkcyan 008b8b darkgoldenrod b8860b darkgray a9a9a9 darkgreen 006400 darkgrey a9a9a9 darkkhaki bdb76b darkmagenta 8b008b
        darkolivegreen 556b2f darkorange ff8c00 darkorchid 9932cc darkred 8b0000 darksalmon e9967a darkseagreen 8fbc8f
        darkslateblue 483d8b darkslategray 2f4f4f darkslategrey 2f4f4f darkturquoise 00ced1 darkviolet 9400d3 deeppink ff1493
        deepskyblue 00bfff dimgray 696969 dimgrey 696969 dodgerblue 1e90ff firebrick b22222 floralwhite fffaf0 forestgreen 228b22
        fuchsia ff00ff gainsboro dcdcdc ghostwhite f8f8ff gold ffd700 goldenrod daa520 gray 808080 green 008000 greenyellow adff2f
        grey 808080 honeydew f0fff0 hotpink ff69b4 indianred cd5c5c indigo 4b0082 ivory fffff0 khaki f0e68c lavender e6e6fa
        lavenderblush fff0f5 lawngreen 7cfc00 lemonchiffon fffacd lightblue add8e6 lightcoral f08080 lightcyan e0ffff
        lightgoldenrodyellow fafad2 lightgray d3d3d3 lightgreen 90ee90 lightgrey d3d3d3 lightpink ffb6c1 lightsalmon ffa07a
        lightseagreen 20b2aa lightskyblue 87cefa lightslategray 778899 lightslategrey 778899 lightsteelblue b0c4de lightyellow ffffe0
        lime 00ff00 limegreen 32cd32 linen faf0e6 magenta ff00ff maroon 800000 mediumaquamarine 66cdaa mediumblue 0000cd
        mediumorchid ba55d3 mediumpurple 9370db mediumseagreen 3cb371 mediumslateblue 7b68ee mediumspringgreen 00fa9a
        mediumturquoise 48d1cc mediumvioletred c71585 midnightblue 191970 mintcream f5fffa mistyrose ffe4e1 moccasin ffe4b5
        navajowhite ffdead navy 000080 oldlace fdf5e6 olive 808000 olivedrab 6b8e23 orange ffa500 orangered ff4500 orchid da70d6
        palegoldenrod eee8aa palegreen 98fb98 paleturquoise afeeee palevioletred db7093 papayawhip ffefd5 peachpuff ffdab9
        peru cd853f pink ffc0cb plum dda0dd powderblue b0e0e6 purple 800080 rebeccapurple 663399 red ff0000 rosybrown bc8f8f
        royalblue 4169e1 saddlebrown 8b4513 salmon fa8072 sandybrown f4a460 seagreen 2e8b57 seashell fff5ee sienna a0522d
        silver c0c0c0 skyblue 87ceeb slateblue 6a5acd slategray 708090 slategrey 708090 snow fffafa springgreen 00ff7f
        steelblue 4682b4 tan d2b48c teal 008080 thistle d8bfd8 tomato ff6347 turquoise 40e0d0 violet ee82ee wheat f5deb3
        white ffffff whitesmoke f5f5f5 yellow ffff00 yellowgreen 9acd32
    """.trim().split(Regex("\\s+")).chunked(2).associate { (name, hex) -> name to parseHex(hex)!! } + ("transparent" to TRANSPARENT)
}
