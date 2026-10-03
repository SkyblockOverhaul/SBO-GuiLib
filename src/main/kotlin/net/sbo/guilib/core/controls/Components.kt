package net.sbo.guilib.core.controls

import net.sbo.guilib.core.dom.Cancelable
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.Rect
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.NodeBuilder
import net.sbo.guilib.core.dsl.classNames
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.input
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.event.EventType
import net.sbo.guilib.core.event.InputEvent
import net.sbo.guilib.core.event.KeyboardEvent
import net.sbo.guilib.core.event.UIEvent

/** One entry of a `select`. [label] may contain `§` color codes. */
/**
 * An entry of a select, radio group, segmented control or chips; [title] is its hover text, [className] / [style] go on
 * the entry (and, in a select, on its label in the box while chosen).
 */
data class SelectOption(
    val value: String,
    val label: String,
    val disabled: Boolean = false,
    val title: String? = null,
    val className: String? = null,
    val style: String? = null,
)

internal data class SelectProps(
    val value: String?,
    val onChange: ((InputEvent) -> Unit)?,
    val options: List<SelectOption>,
    val className: String?,
    val id: String?,
    val style: String?,
    val disabled: Boolean,
    val placeholder: String?,
    val searchable: Boolean = false,
    val searchPlaceholder: String? = null,
    val multiple: Boolean = false,
    val values: List<String> = emptyList(),
    val onChangeValues: ((List<String>) -> Unit)? = null,
)

/** Label without `§` formatting codes (for searching). */
private fun plainLabel(label: String) = label.replace(Regex("§."), "")

/**
 * `<select>`: shows the selected option and opens a menu (rendered in a portal, so it is never clipped).
 * With `searchable` the menu starts with a search field that filters the options (case-insensitive, by label or value).
 * With `multiple` options toggle (with a check mark) and the menu stays open; the box shows the chosen labels.
 * Styled with `select` (`.multiple`), `.guilib-select-value`, `.guilib-select-arrow`, `.guilib-select-menu`,
 * `.guilib-select-search`, `.guilib-select-empty`, `.guilib-option` (`.selected`, `.highlighted`, `.disabled`),
 * `.guilib-option-check`.
 */
internal val SelectComponent = component<SelectProps>("Select") { p ->
    var open by useState(false)
    var anchor by useState<Rect?>(null)
    var highlighted by useState(-1)
    var query by useState("")
    val ref = useElementRef()
    val menuRef = useElementRef()
    val doc = useDocument()

    val q = query.trim().lowercase()
    val shown = if (!p.searchable || q.isEmpty()) p.options
    else p.options.filter { plainLabel(it.label).lowercase().contains(q) || it.value.lowercase().contains(q) }

    fun isSelected(o: SelectOption) = if (p.multiple) o.value in p.values else o.value == p.value

    fun close(refocus: Boolean) {
        open = false
        query = ""
        // The search field had the focus; give it back to the select.
        val focused = doc.focusedElement
        if (refocus && focused != null && menuRef.current?.contains(focused) == true) ref.current?.let { doc.focus(it) }
    }

    fun choose(opt: SelectOption) {
        if (opt.disabled) return
        if (p.multiple) {
            val set = if (opt.value in p.values) p.values - opt.value else p.values + opt.value
            p.onChangeValues?.invoke(p.options.map { it.value }.filter { it in set })
            return
        }
        close(true)
        if (opt.value == p.value) return
        val ev = InputEvent(EventType.CHANGE, opt.value)
        ref.current?.let { ev.target = it; ev.currentTarget = it }
        p.onChange?.invoke(ev)
    }

    fun toggle() {
        if (p.disabled) return
        if (open) return close(true)
        anchor = ref.current?.getBoundingClientRect()
        highlighted = p.options.indexOfFirst { isSelected(it) }
        query = ""
        open = true
    }

    fun inside(target: Element, ref: net.sbo.guilib.core.dom.Ref<Element?>) = ref.current?.contains(target) == true

    useAnchorTracking(ref, if (open) anchor else null) { anchor = it }
    useDocumentEvent(EventType.MOUSEDOWN) { e -> if (open && !inside(e.target, ref) && !inside(e.target, menuRef)) close(false) }
    useDocumentEvent(EventType.WHEEL) { e -> if (open && !inside(e.target, menuRef)) close(false) }

    /** Menu keys, shared by the select itself and the search field. Returns true if handled. */
    fun menuKey(e: KeyboardEvent): Boolean {
        val enabled = shown.indices.filter { !shown[it].disabled }
        when (e.key) {
            "ArrowDown", "ArrowUp" -> {
                if (!open) toggle()
                else if (enabled.isNotEmpty()) {
                    val pos = enabled.indexOf(highlighted)
                    highlighted = if (e.key == "ArrowDown") enabled.getOrElse(pos + 1) { enabled.first() } else enabled.getOrElse(pos - 1) { enabled.last() }
                }
            }
            "Enter" -> if (open && highlighted in shown.indices) choose(shown[highlighted]) else toggle()
            "Escape" -> if (open) {
                close(true); e.stopPropagation()
            } else return false
            "Tab" -> {
                if (open) close(true)
                return false
            }
            else -> return false
        }
        e.preventDefault()
        return true
    }

    val attrs = HashMap<String, Any?>()
    attrs["tabindex"] = 0
    if (p.disabled) attrs["disabled"] = true
    if (open) attrs["open"] = true
    val handlers = HashMap<String, (UIEvent) -> Unit>()
    handlers[EventType.CLICK] = { toggle() }
    handlers[EventType.KEYDOWN] = { e ->
        e as KeyboardEvent
        if (e.key == " ") {
            if (open && highlighted in shown.indices) choose(shown[highlighted]) else toggle()
            e.preventDefault()
        } else menuKey(e)
    }
    val chosen = p.options.filter { isSelected(it) }
    element("select", null, p.id, classNames("multiple" to p.multiple, p.className), p.style, ref, attrs, handlers) {
        span(className = if (chosen.isEmpty()) "guilib-select-value guilib-placeholder" else "guilib-select-value") {
            if (chosen.isEmpty()) +(p.placeholder ?: "")
            // One span per chosen entry, so each keeps the option's class / style (e.g. colored item names).
            chosen.forEachIndexed { i, o ->
                if (i > 0) +", "
                span(key = o.value, className = classNames("guilib-select-chosen", o.className), style = o.style) { +o.label }
            }
        }
        span(className = "guilib-select-arrow") { +"▾" }
    }

    val a = anchor
    if (open && a != null) {
        // Open upwards when the select sits in the lower part of the screen.
        val up = a.bottom > doc.viewportHeight * 0.6f
        val pos = if (up) "bottom: ${doc.viewportHeight - a.y + 1}px" else "top: ${a.bottom + 1}px"
        portal {
            div(className = classNames("guilib-select-menu", "multiple" to p.multiple), ref = menuRef, style = "position: fixed; left: ${a.x}px; $pos; min-width: ${a.width}px") {
                if (p.searchable) {
                    input(
                        className = "guilib-select-search",
                        value = query,
                        placeholder = p.searchPlaceholder ?: "Search…",
                        autoFocus = true,
                        onChange = { e ->
                            query = e.value
                            highlighted = -1
                        },
                        onKeyDown = { e -> menuKey(e) },
                    )
                }
                div(className = "guilib-select-options") {
                    if (shown.isEmpty()) div(className = "guilib-select-empty") { +"No results" }
                    shown.forEachIndexed { i, o ->
                        div(
                            key = o.value,
                            title = o.title,
                            className = classNames("guilib-option", "selected" to isSelected(o), "highlighted" to (i == highlighted), "disabled" to o.disabled, o.className),
                            style = o.style,
                            onMouseEnter = { highlighted = i },
                            onClick = { choose(o) },
                        ) {
                            if (p.multiple) span(className = "guilib-option-check") { +"✓" }
                            span(className = "guilib-option-label") { +o.label }
                        }
                    }
                }
            }
        }
    }
}

internal data class TooltipProps(
    val text: String?,
    val content: (NodeBuilder.() -> Unit)?,
    val placement: String,
    val delayMs: Long,
    val className: String?,
    val children: NodeBuilder.() -> Unit,
)

/**
 * Shows a tooltip after hovering [TooltipProps.children] for `delayMs`. Rendered in a portal with `.guilib-tooltip`.
 * `placement`: `top` (default), `bottom`, `left`, `right`.
 */
internal val TooltipComponent = component<TooltipProps>("Tooltip") { p ->
    var rect by useState<Rect?>(null)
    val ref = useElementRef()
    val timer = useRef<Cancelable?>(null)
    val doc = useDocument()
    useEffect { onCleanup { timer.current?.cancel() } }
    useAnchorTracking(ref, rect) { rect = it }

    span(
        className = "guilib-tooltip-anchor",
        ref = ref,
        onMouseEnter = {
            timer.current?.cancel()
            timer.current = doc.setTimeout(p.delayMs) { rect = ref.current?.getBoundingClientRect() }
        },
        onMouseLeave = {
            timer.current?.cancel()
            rect = null
        },
    ) { p.children(this) }

    val r = rect
    if (r != null) {
        val gap = 3f
        val pos = when (p.placement) {
            "bottom" -> "left: ${r.x}px; top: ${r.bottom + gap}px"
            "left" -> "right: ${doc.viewportWidth - r.x + gap}px; top: ${r.y}px"
            "right" -> "left: ${r.right + gap}px; top: ${r.y}px"
            else -> "left: ${r.x}px; bottom: ${doc.viewportHeight - r.y + gap}px"
        }
        portal {
            div(className = classNames("guilib-tooltip", p.className), style = "position: fixed; $pos") {
                p.text?.let { +it }
                p.content?.invoke(this)
            }
        }
    }
}

internal data class ModalProps(
    val open: Boolean,
    val onClose: (() -> Unit)?,
    val className: String?,
    val closeOnBackdropClick: Boolean,
    val children: NodeBuilder.() -> Unit,
)

/**
 * Dialog above everything (portal) with a dimmed backdrop. Escape and (optionally) a backdrop click call `onClose`.
 * Styled with `.guilib-modal-backdrop` and `.guilib-modal`.
 */
internal val ModalComponent = component<ModalProps>("Modal") { p ->
    useDocumentEvent(EventType.KEYDOWN) { e ->
        if (p.open && e is KeyboardEvent && e.key == "Escape") {
            p.onClose?.invoke()
            e.preventDefault()
            e.stopPropagation()
        }
    }
    if (!p.open) return@component
    portal(className = "guilib-modal-portal") {
        div(className = "guilib-modal-backdrop", onClick = { e -> if (p.closeOnBackdropClick && e.target === e.currentTarget) p.onClose?.invoke() }) {
            div(className = classNames("guilib-modal", p.className)) { p.children(this) }
        }
    }
}

internal data class PresenceProps(val visible: Boolean, val exitMs: Long, val children: NodeBuilder.(leaving: Boolean) -> Unit)

internal val PresenceComponent = component<PresenceProps>("Presence") { p ->
    var mounted by useState(p.visible)
    useEffect(p.visible) {
        if (p.visible) mounted = true
        // Cancelled by the effect cleanup if `visible` comes back before the exit finished.
        else if (mounted) setTimeout(p.exitMs) { mounted = false }
    }
    if (p.visible || mounted) p.children(this, !p.visible)
}

internal data class PresenceListProps(
    val items: List<Any?>,
    val key: (Any?) -> Any?,
    val exitMs: Long,
    val children: NodeBuilder.(item: Any?, leaving: Boolean) -> Unit,
)

/** A rendered entry; [leftAt] is the time the item was removed from the list, or -1 while it is present. */
internal class PresenceEntry(val key: Any?, val item: Any?, val leftAt: Long)

/**
 * Merges the previously rendered entries with the new [items]: present items in their new order, removed ones kept
 * after the entry that preceded them before (so they leave from where they were) until [exitMs] passed.
 */
internal fun mergePresence(prev: List<PresenceEntry>, items: List<Any?>, key: (Any?) -> Any?, now: Long, exitMs: Long): List<PresenceEntry> {
    val result = ArrayList<PresenceEntry>(items.size + prev.size)
    val present = HashSet<Any?>()
    for (item in items) {
        val k = key(item)
        if (present.add(k)) result += PresenceEntry(k, item, -1)
    }
    var insertAt = 0
    for (e in prev) {
        if (e.key in present) {
            insertAt = result.indexOfFirst { it.key == e.key } + 1
        } else {
            val leftAt = if (e.leftAt >= 0) e.leftAt else now
            if (now - leftAt < exitMs) result.add(insertAt++, PresenceEntry(e.key, e.item, leftAt))
        }
    }
    return result
}

internal val PresenceListComponent = component<PresenceListProps>("PresenceList") { p ->
    val now = useDocument().now()
    val entries = useRef(emptyList<PresenceEntry>())
    val merged = mergePresence(entries.current, p.items, p.key, now, p.exitMs)
    entries.current = merged
    // Re-render when the next leaving entry is due for removal.
    val nextRemoval = merged.filter { it.leftAt >= 0 }.minOfOrNull { it.leftAt + p.exitMs }
    val update = useForceUpdate()
    useEffect(nextRemoval) { if (nextRemoval != null) setTimeout(maxOf(0L, nextRemoval - now)) { update() } }
    for (e in merged) fragment(key = e.key) { p.children(this, e.item, e.leftAt >= 0) }
}
