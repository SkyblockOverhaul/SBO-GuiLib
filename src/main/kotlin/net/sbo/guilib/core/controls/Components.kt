package net.sbo.guilib.core.controls

import net.sbo.guilib.core.dom.Cancelable
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.Rect
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.NodeBuilder
import net.sbo.guilib.core.dsl.classNames
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.event.EventType
import net.sbo.guilib.core.event.InputEvent
import net.sbo.guilib.core.event.KeyboardEvent
import net.sbo.guilib.core.event.UIEvent

/** One entry of a `select`. [label] may contain `§` color codes. */
data class SelectOption(val value: String, val label: String, val disabled: Boolean = false)

internal data class SelectProps(
    val value: String?,
    val onChange: ((InputEvent) -> Unit)?,
    val options: List<SelectOption>,
    val className: String?,
    val id: String?,
    val style: String?,
    val disabled: Boolean,
    val placeholder: String?,
)

/**
 * `<select>`: shows the selected option and opens a menu (rendered in a portal, so it is never clipped).
 * Styled with `select`, `.guilib-select-value`, `.guilib-select-arrow`, `.guilib-select-menu`, `.guilib-option`
 * (`.selected`, `.highlighted`, `.disabled`).
 */
internal val SelectComponent = component<SelectProps>("Select") { p ->
    var open by useState(false)
    var anchor by useState<Rect?>(null)
    var highlighted by useState(-1)
    val ref = useElementRef()
    val menuRef = useElementRef()
    val doc = useDocument()

    fun choose(opt: SelectOption) {
        open = false
        if (opt.disabled || opt.value == p.value) return
        val ev = InputEvent(EventType.CHANGE, opt.value)
        ref.current?.let { ev.target = it; ev.currentTarget = it }
        p.onChange?.invoke(ev)
    }

    fun toggle() {
        if (p.disabled) return
        anchor = ref.current?.getBoundingClientRect()
        highlighted = p.options.indexOfFirst { it.value == p.value }
        open = !open
    }

    fun inside(target: Element, ref: net.sbo.guilib.core.dom.Ref<Element?>) = ref.current?.contains(target) == true

    useDocumentEvent(EventType.MOUSEDOWN) { e -> if (open && !inside(e.target, ref) && !inside(e.target, menuRef)) open = false }
    useDocumentEvent(EventType.WHEEL) { e -> if (open && !inside(e.target, menuRef)) open = false }

    val selected = p.options.firstOrNull { it.value == p.value }
    val attrs = HashMap<String, Any?>()
    attrs["tabindex"] = 0
    if (p.disabled) attrs["disabled"] = true
    if (open) attrs["open"] = true
    val handlers = HashMap<String, (UIEvent) -> Unit>()
    handlers[EventType.CLICK] = { toggle() }
    handlers[EventType.KEYDOWN] = { e ->
        e as KeyboardEvent
        val enabled = p.options.indices.filter { !p.options[it].disabled }
        when (e.key) {
            "ArrowDown", "ArrowUp" -> {
                if (!open) toggle()
                else if (enabled.isNotEmpty()) {
                    val pos = enabled.indexOf(highlighted)
                    val next = if (e.key == "ArrowDown") enabled.getOrElse(pos + 1) { enabled.first() } else enabled.getOrElse(pos - 1) { enabled.last() }
                    highlighted = next
                }
                e.preventDefault()
            }
            "Enter", " " -> {
                if (open && highlighted in p.options.indices) choose(p.options[highlighted]) else toggle()
                e.preventDefault()
            }
            "Escape" -> if (open) {
                open = false; e.preventDefault(); e.stopPropagation()
            }
        }
    }
    element("select", null, p.id, p.className, p.style, ref, attrs, handlers) {
        span(className = if (selected == null) "guilib-select-value guilib-placeholder" else "guilib-select-value") {
            +(selected?.label ?: p.placeholder ?: "")
        }
        span(className = "guilib-select-arrow") { +"▾" }
    }

    val a = anchor
    if (open && a != null) {
        // Open upwards when the select sits in the lower part of the screen.
        val up = a.bottom > doc.viewportHeight * 0.6f
        val pos = if (up) "bottom: ${doc.viewportHeight - a.y + 1}px" else "top: ${a.bottom + 1}px"
        portal {
            div(className = "guilib-select-menu", ref = menuRef, style = "position: fixed; left: ${a.x}px; $pos; min-width: ${a.width}px") {
                p.options.forEachIndexed { i, o ->
                    div(
                        key = o.value,
                        className = classNames("guilib-option", "selected" to (o.value == p.value), "highlighted" to (i == highlighted), "disabled" to o.disabled),
                        onMouseEnter = { highlighted = i },
                        onClick = { choose(o) },
                    ) { +o.label }
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
