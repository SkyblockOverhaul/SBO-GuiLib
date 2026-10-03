package net.sbo.guilib.core.controls

import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.NodeBuilder
import net.sbo.guilib.core.dsl.classNames
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.event.EventType
import net.sbo.guilib.core.event.KeyboardEvent
import net.sbo.guilib.core.event.UIEvent

/** Index of the next enabled option from [from] in direction [dir] (wrapping), or -1. */
private fun nextEnabled(options: List<SelectOption>, from: Int, dir: Int): Int {
    if (options.none { !it.disabled }) return -1
    var i = from
    repeat(options.size) {
        i = Math.floorMod(i + dir, options.size)
        if (!options[i].disabled) return i
    }
    return -1
}

/** Arrow keys for a row/column of options: returns the direction (-1/1) or 0. */
private fun arrowDirection(e: KeyboardEvent): Int = when (e.key) {
    "ArrowRight", "ArrowDown" -> 1
    "ArrowLeft", "ArrowUp" -> -1
    else -> 0
}

/** Focuses the sibling at [index] among the option elements of [current]'s parent (after a re-render keeps them). */
private fun focusSibling(current: Element, index: Int) {
    val sibling = current.parent?.children?.filterIsInstance<Element>()?.filter { it.classList.contains(OPTION_MARK) }?.getOrNull(index)
    if (sibling != null) current.ownerDocument?.focus(sibling)
}

private const val OPTION_MARK = "guilib-choice"

internal data class ChoiceProps(
    val value: String?,
    val onChange: ((String) -> Unit)?,
    val options: List<SelectOption>,
    val segmented: Boolean,
    val vertical: Boolean,
    val disabled: Boolean,
    val className: String?,
    val id: String?,
)

/**
 * One-of-many choice: radio buttons ([ChoiceProps.segmented] = false) or a segmented button bar with a sliding
 * highlight. Like native radios, the selected option is the Tab stop and the arrow keys move the selection.
 * Styled with `.guilib-radio-group` (`.vertical`) / `.guilib-radio` (`.checked`, `.disabled`) / `.guilib-radio-dot`,
 * and `.guilib-segmented` / `.guilib-segment` (`.selected`, `.disabled`) / `.guilib-segment-indicator`.
 */
internal val ChoiceComponent = component<ChoiceProps>("Choice") { p ->
    val listRef = useElementRef()
    val indicatorRef = useElementRef()
    val selectedIndex = p.options.indexOfFirst { it.value == p.value }
    val tabStop = if (selectedIndex >= 0) selectedIndex else p.options.indexOfFirst { !it.disabled }

    fun choose(i: Int) {
        val o = p.options.getOrNull(i) ?: return
        if (p.disabled || o.disabled || o.value == p.value) return
        p.onChange?.invoke(o.value)
    }

    val indicatorStyle = useLayoutStyle(indicatorRef) {
        val list = listRef.current?.takeIf { p.segmented } ?: return@useLayoutStyle null
        val sel = list.children.filterIsInstance<Element>().firstOrNull { it.classList.contains("selected") }
            ?: return@useLayoutStyle "opacity: 0"
        val (x, y, w, h) = offsetIn(sel, list).let { listOf(it[0], it[1], it[2], it[3]) }
        "left: ${px(x)}; top: ${px(y)}; width: ${px(w)}; height: ${px(h)}"
    }

    val root = if (p.segmented) classNames("guilib-segmented", "vertical" to p.vertical, "disabled" to p.disabled, p.className)
    else classNames("guilib-radio-group", "vertical" to p.vertical, "disabled" to p.disabled, p.className)
    div(className = root, id = p.id, ref = listRef) {
        if (p.segmented) div(className = "guilib-segment-indicator", ref = indicatorRef, style = indicatorStyle)
        p.options.forEachIndexed { i, o ->
            val selected = i == selectedIndex
            val off = p.disabled || o.disabled
            val attrs = HashMap<String, Any?>()
            attrs["tabindex"] = if (i == tabStop && !p.disabled) 0 else -1
            if (off) attrs["disabled"] = true
            if (o.title != null) attrs["title"] = o.title
            val handlers = HashMap<String, (UIEvent) -> Unit>()
            handlers[EventType.CLICK] = { choose(i) }
            handlers[EventType.KEYDOWN] = { e ->
                e as KeyboardEvent
                val dir = arrowDirection(e)
                if (dir != 0) {
                    e.preventDefault()
                    val next = nextEnabled(p.options, i, dir)
                    if (next >= 0 && !p.disabled) {
                        choose(next)
                        focusSibling(e.currentTarget, next)
                    }
                }
            }
            val cls = if (p.segmented) classNames(OPTION_MARK, "guilib-segment", "selected" to selected, "disabled" to off, o.className)
            else classNames(OPTION_MARK, "guilib-radio", "checked" to selected, "disabled" to off, o.className)
            element("div", o.value, null, cls, o.style, null, attrs, handlers) {
                if (!p.segmented) span(className = "guilib-radio-dot")
                span(className = if (p.segmented) "guilib-segment-label" else "guilib-radio-label") { +o.label }
            }
        }
    }
}

internal data class ChipsProps(
    val values: List<String>,
    val onChange: ((List<String>) -> Unit)?,
    val options: List<SelectOption>,
    val disabled: Boolean,
    val className: String?,
)

/**
 * Toggleable chips for picking several options (filters). [ChipsProps.values] keeps the options' order.
 * Styled with `.guilib-chips`, `.guilib-chip` (`.selected`, `.disabled`) and `.guilib-chip-check`.
 */
internal val ChipsComponent = component<ChipsProps>("Chips") { p ->
    div(className = classNames("guilib-chips", "disabled" to p.disabled, p.className)) {
        p.options.forEach { o ->
            val selected = o.value in p.values
            val off = p.disabled || o.disabled
            val attrs = HashMap<String, Any?>()
            attrs["tabindex"] = 0
            if (off) attrs["disabled"] = true
            if (o.title != null) attrs["title"] = o.title
            val handlers = HashMap<String, (UIEvent) -> Unit>()
            handlers[EventType.CLICK] = {
                if (!off) {
                    val set = if (selected) p.values - o.value else p.values + o.value
                    p.onChange?.invoke(p.options.map { it.value }.filter { it in set })
                }
            }
            element("div", o.value, null, classNames("guilib-chip", "selected" to selected, "disabled" to off, o.className), o.style, null, attrs, handlers) {
                span(className = "guilib-chip-check") { +"✓" }
                span { +o.label }
            }
        }
    }
}

/** One tab of a `tabs` bar. */
internal class TabDef(val value: String, val label: String, val disabled: Boolean, val content: (NodeBuilder.() -> Unit)?)

internal data class TabsProps(
    val value: String?,
    val onChange: ((String) -> Unit)?,
    val tabs: List<TabDef>,
    val variant: String,
    val className: String?,
    val id: String?,
)

/**
 * Tab bar with an animated indicator (underline or pill) that slides to the active tab. Tabs with content render the
 * active tab's content below the bar in `.guilib-tab-panel`. Left/Right (or Up/Down) switch tabs.
 * Styled with `.guilib-tabs` (`.underline`, `.pills`), `.guilib-tab-list`, `.guilib-tab` (`.active`, `.disabled`),
 * `.guilib-tab-indicator` and `.guilib-tab-panel`.
 */
internal val TabsComponent = component<TabsProps>("Tabs") { p ->
    val listRef = useElementRef()
    val indicatorRef = useElementRef()
    val options = p.tabs.map { SelectOption(it.value, it.label, it.disabled) }
    val active = p.tabs.indexOfFirst { it.value == p.value }
    val pills = p.variant == "pills"

    fun choose(i: Int) {
        val t = p.tabs.getOrNull(i) ?: return
        if (!t.disabled && t.value != p.value) p.onChange?.invoke(t.value)
    }

    val indicatorStyle = useLayoutStyle(indicatorRef) {
        val list = listRef.current ?: return@useLayoutStyle null
        val tab = list.children.filterIsInstance<Element>().firstOrNull { it.classList.contains("active") }
            ?: return@useLayoutStyle "opacity: 0"
        val r = offsetIn(tab, list)
        if (pills) "left: ${px(r[0])}; top: ${px(r[1])}; width: ${px(r[2])}; height: ${px(r[3])}"
        else "left: ${px(r[0])}; width: ${px(r[2])}"
    }

    div(className = classNames("guilib-tabs", if (pills) "pills" else "underline", p.className), id = p.id) {
        div(className = "guilib-tab-list", ref = listRef) {
            div(className = "guilib-tab-indicator", ref = indicatorRef, style = indicatorStyle)
            p.tabs.forEachIndexed { i, t ->
                val attrs = HashMap<String, Any?>()
                attrs["tabindex"] = if (i == (if (active >= 0) active else 0)) 0 else -1
                if (t.disabled) attrs["disabled"] = true
                val handlers = HashMap<String, (UIEvent) -> Unit>()
                handlers[EventType.CLICK] = { choose(i) }
                handlers[EventType.KEYDOWN] = { e ->
                    e as KeyboardEvent
                    val dir = arrowDirection(e)
                    if (dir != 0) {
                        e.preventDefault()
                        val next = nextEnabled(options, i, dir)
                        if (next >= 0) {
                            choose(next)
                            focusSibling(e.currentTarget, next)
                        }
                    }
                }
                element("div", t.value, null, classNames(OPTION_MARK, "guilib-tab", "active" to (i == active), "disabled" to t.disabled), null, null, attrs, handlers) {
                    +t.label
                }
            }
        }
        val content = p.tabs.getOrNull(active)?.content
        if (content != null) div(className = "guilib-tab-panel", key = p.tabs[active].value) { content() }
    }
}
