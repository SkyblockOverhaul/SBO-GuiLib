package net.sbo.guilib.core.controls

import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.NodeBuilder
import net.sbo.guilib.core.dsl.classNames
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.event.EventType
import net.sbo.guilib.core.event.KeyboardEvent
import net.sbo.guilib.core.event.MouseEvent
import net.sbo.guilib.core.event.UIEvent

/** Entry of a menu built with `MenuBuilder`. */
internal sealed class MenuEntry {
    class Item(val label: String, val disabled: Boolean, val danger: Boolean, val shortcut: String?, val onClick: () -> Unit) : MenuEntry()
    class Header(val text: String) : MenuEntry()
    object Separator : MenuEntry()
}

internal data class MenuProps(
    val x: Float,
    val y: Float,
    val entries: List<MenuEntry>,
    val onClose: () -> Unit,
    val className: String?,
)

/** Distance kept to the screen edges. */
private const val MENU_MARGIN = 2f

/**
 * Popup menu at a screen position, kept inside the screen (flips up/left when there is no room). Rendered in a portal.
 * Arrow keys + Enter choose, Escape, a click outside or the wheel close it.
 * Styled with `.guilib-menu`, `.guilib-menu-item` (`.highlighted`, `.disabled`, `.danger`), `.guilib-menu-shortcut`,
 * `.guilib-menu-header`, `.guilib-menu-separator`.
 */
internal val MenuComponent = component<MenuProps>("Menu") { p ->
    val menuRef = useElementRef()
    var highlighted by useState(-1)
    val doc = useDocument()
    val items = p.entries.indices.filter { (p.entries[it] as? MenuEntry.Item)?.disabled == false }

    fun choose(i: Int) {
        val item = p.entries.getOrNull(i) as? MenuEntry.Item ?: return
        if (item.disabled) return
        p.onClose()
        item.onClick()
    }

    useEffect { menuRef.current?.let { doc.focus(it) } }
    // The menu sits at a mouse position, which means nothing after a resize or GUI scale change: close it (like browsers).
    val onClose = useRef(p.onClose)
    onClose.current = p.onClose
    useEffect {
        val w = doc.viewportWidth
        val h = doc.viewportHeight
        val hook: (Long) -> Unit = { if (doc.viewportWidth != w || doc.viewportHeight != h) onClose.current() }
        doc.frameHooks += hook
        onCleanup { doc.frameHooks -= hook }
    }
    useDocumentEvent(EventType.MOUSEDOWN) { e -> if (menuRef.current?.contains(e.target) != true) p.onClose() }
    useDocumentEvent(EventType.WHEEL) { e -> if (menuRef.current?.contains(e.target) != true) p.onClose() }
    useDocumentEvent(EventType.KEYDOWN) { e ->
        e as KeyboardEvent
        when (e.key) {
            "Escape" -> p.onClose()
            "ArrowDown", "ArrowUp" -> if (items.isNotEmpty()) {
                val pos = items.indexOf(highlighted)
                highlighted = if (e.key == "ArrowDown") items.getOrElse(pos + 1) { items.first() } else items.getOrElse(pos - 1) { items.last() }
            }
            "Enter", " " -> if (highlighted >= 0) choose(highlighted)
            "Tab" -> p.onClose()
            else -> return@useDocumentEvent
        }
        e.preventDefault()
        e.stopPropagation()
    }

    val style = useLayoutStyle(menuRef) {
        val m = menuRef.current ?: return@useLayoutStyle null
        val w = m.box.width
        val h = m.box.height
        val vw = doc.viewportWidth
        val vh = doc.viewportHeight
        val x = (if (p.x + w > vw - MENU_MARGIN) p.x - w else p.x).coerceAtMost(vw - w - MENU_MARGIN).coerceAtLeast(MENU_MARGIN)
        val y = (if (p.y + h > vh - MENU_MARGIN) p.y - h else p.y).coerceAtMost(vh - h - MENU_MARGIN).coerceAtLeast(MENU_MARGIN)
        "left: ${px(x)}; top: ${px(y)}"
    }

    portal {
        div(className = classNames("guilib-menu", p.className), ref = menuRef, tabIndex = -1, style = style ?: "left: ${px(p.x)}; top: ${px(p.y)}") {
            p.entries.forEachIndexed { i, e ->
                when (e) {
                    is MenuEntry.Item -> {
                        val handlers = HashMap<String, (UIEvent) -> Unit>()
                        handlers[EventType.CLICK] = { choose(i) }
                        handlers[EventType.MOUSEENTER] = { highlighted = if (e.disabled) -1 else i }
                        val attrs = HashMap<String, Any?>()
                        if (e.disabled) attrs["disabled"] = true
                        element(
                            "div", i, null,
                            classNames("guilib-menu-item", "highlighted" to (i == highlighted), "danger" to e.danger, "disabled" to e.disabled),
                            null, null, attrs, handlers,
                        ) {
                            span(className = "guilib-menu-label") { +e.label }
                            if (e.shortcut != null) span(className = "guilib-menu-shortcut") { +e.shortcut }
                        }
                    }
                    is MenuEntry.Header -> div(className = "guilib-menu-header", key = i) { +e.text }
                    MenuEntry.Separator -> div(className = "guilib-menu-separator", key = i)
                }
            }
        }
    }
}

internal data class ContextMenuProps(
    val entries: () -> List<MenuEntry>,
    val disabled: Boolean,
    val className: String?,
    val children: NodeBuilder.() -> Unit,
)

/**
 * Wraps [ContextMenuProps.children] in `.guilib-context-anchor`; a right click inside opens the menu at the mouse.
 * The entries are built when the menu opens, so they always reflect the current state.
 */
internal val ContextMenuComponent = component<ContextMenuProps>("ContextMenu") { p ->
    var at by useState<Pair<Float, Float>?>(null)
    var entries by useState<List<MenuEntry>>(emptyList())
    val handlers = HashMap<String, (UIEvent) -> Unit>()
    handlers[EventType.CONTEXTMENU] = { e ->
        e as MouseEvent
        if (!p.disabled) {
            entries = p.entries()
            at = e.clientX to e.clientY
            e.preventDefault()
            e.stopPropagation()
        }
    }
    element("div", null, null, classNames("guilib-context-anchor", "menu-open" to (at != null), p.className), null, null, emptyMap(), handlers) {
        p.children(this)
    }
    val pos = at
    if (pos != null) MenuComponent(MenuProps(pos.first, pos.second, entries, { at = null }, null), key = "${pos.first},${pos.second}")
}
