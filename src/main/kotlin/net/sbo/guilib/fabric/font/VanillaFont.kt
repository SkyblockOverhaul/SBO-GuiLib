package net.sbo.guilib.fabric.font

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import net.sbo.guilib.core.layout.FontMetrics
import net.sbo.guilib.core.layout.TextStyle

/**
 * Minecraft's built-in font (`font-family: minecraft`). Its glyphs are 8px tall at scale 1 (7 above the baseline),
 * so `font-size: 8px` renders at native size; other sizes scale the pose.
 */
object VanillaFont {
    const val NATIVE_SIZE = 8f

    private val font get() = Minecraft.getInstance().font

    fun scale(style: TextStyle) = style.fontSize / NATIVE_SIZE

    /**
     * [text] as drawn characters. Unlike `Component.literal`, this never interprets `§`: formatting codes are already
     * resolved by the layout, so any `§` left is meant to be visible (e.g. typed into an input).
     */
    fun sequence(text: String, style: TextStyle): FormattedCharSequence =
        FormattedCharSequence.forward(text, Style.EMPTY.withBold(style.bold).withItalic(style.italic))

    fun width(text: String, style: TextStyle): Float = font.width(sequence(text, style)) * scale(style)

    fun metrics(style: TextStyle): FontMetrics {
        val s = scale(style)
        return FontMetrics(ascent = 7f * s, descent = 1f * s, normalLineHeight = 9f * s)
    }
}
