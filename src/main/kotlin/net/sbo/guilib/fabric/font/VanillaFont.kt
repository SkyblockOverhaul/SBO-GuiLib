package net.sbo.guilib.fabric.font

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
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

    fun component(text: String, style: TextStyle): Component =
        Component.literal(text).setStyle(Style.EMPTY.withBold(style.bold).withItalic(style.italic))

    fun width(text: String, style: TextStyle): Float = font.width(component(text, style)) * scale(style)

    fun metrics(style: TextStyle): FontMetrics {
        val s = scale(style)
        return FontMetrics(ascent = 7f * s, descent = 1f * s, normalLineHeight = 9f * s)
    }
}
