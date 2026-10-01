package net.sbo.guilib.fabric

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.sbo.guilib.core.dsl.NodeBuilder
import net.sbo.guilib.core.dsl.classNames
import net.sbo.guilib.core.dsl.span
import java.util.Optional

/**
 * Renders a Minecraft text [component] (chat messages, item names, `Component.translatable(…)`) as inline text:
 * colors (also RGB), bold, italic, underlined and strikethrough become CSS, `show_text` hover events become `title`
 * tooltips, and click events (open URL, run/suggest command, copy to clipboard …) work like in chat.
 * The text sits in a `span.guilib-text`; clickable parts get `.guilib-text-link`.
 */
fun NodeBuilder.text(component: Component, className: String? = null, key: Any? = null) {
    span(className = classNames("guilib-text", className), key = key) { segments(component) }
}

/** Lets the core's `text(value)` render components too (`text(Component.translatable("…"))`). */
internal fun installRichText() {
    NodeBuilder.richText = { builder, value ->
        if (value is Component) {
            builder.text(value, null, null)
            true
        } else false
    }
}

private fun NodeBuilder.segments(component: Component) {
    component.visit(
        FormattedText.StyledContentConsumer<Unit> { style, s ->
            if (s.isNotEmpty()) segment(style, s)
            Optional.empty()
        },
        Style.EMPTY,
    )
}

private fun NodeBuilder.segment(style: Style, s: String) {
    val css = buildString {
        style.color?.let { append(String.format("color: #%06x; ", it.value and 0xFFFFFF)) }
        if (style.isBold) append("font-weight: bold; ")
        if (style.isItalic) append("font-style: italic; ")
        val decorations = listOfNotNull("underline".takeIf { style.isUnderlined }, "line-through".takeIf { style.isStrikethrough })
        if (decorations.isNotEmpty()) append("text-decoration: ${decorations.joinToString(" ")}; ")
    }.trim()
    val hover = (style.hoverEvent as? HoverEvent.ShowText)?.value?.string
    val click = style.clickEvent
    if (css.isEmpty() && hover == null && click == null) {
        +s
        return
    }
    span(
        className = if (click != null) "guilib-text-link" else null,
        style = css.ifEmpty { null },
        title = hover,
        onClick = click?.let { ev -> { _ -> GuiLib.handleClickEvent(ev) } },
    ) { +s }
}
