package net.sbo.guilib.core.controls

import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.Rect
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.NodeBuilder
import net.sbo.guilib.core.dsl.classNames
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.event.EventType
import net.sbo.guilib.core.event.KeyboardEvent
import net.sbo.guilib.core.event.MouseEvent
import java.util.Locale
import kotlin.math.abs

internal data class SortableProps(
    val items: List<Any?>,
    val key: (Any?) -> Any?,
    val onReorder: ((List<Any?>) -> Unit)?,
    val horizontal: Boolean,
    val handle: Boolean,
    val className: String?,
    val itemClassName: String?,
    val children: NodeBuilder.(item: Any?, dragging: Boolean) -> Unit,
)

/** Pixels the mouse has to move before a press turns into a drag (so clicks inside items still work). */
internal const val SORTABLE_DRAG_THRESHOLD = 3f

/**
 * Drag-to-reorder list. Items are wrapped in `.guilib-sortable-item` and shifted with `transform` while dragging, so
 * layout never changes until the drop; then `onReorder` receives the reordered list.
 * Styled with `.guilib-sortable` (`.horizontal`, `.sorting` while a drag is active), `.guilib-sortable-item`
 * (`.dragging`) and `.guilib-drag-handle`.
 */
internal val SortableComponent = component<SortableProps>("Sortable") { p ->
    /**
     * One drag. All positions are relative to the list's own box, so scrolling while dragging keeps them valid.
     * Everything lives here (not in state) so event handlers never see values from an older render.
     */
    class Drag(val from: Int, val key: Any?, val startX: Float, val startY: Float, val rects: List<Rect>) {
        var active = false
        var target = from
        var offset = 0f
    }

    val drag = useRef<Drag?>(null)
    val suppressClick = useRef(false)
    val listRef = useElementRef()
    val rerender = useForceUpdate()

    fun start(r: Rect) = if (p.horizontal) r.x else r.y
    fun end(r: Rect) = if (p.horizontal) r.right else r.bottom
    fun center(r: Rect) = (start(r) + end(r)) / 2f

    /** Distance the other items move to fill the gap: the dragged item's size plus the gap between items. */
    fun slot(d: Drag): Float {
        val r = d.rects[d.from]
        val gap = when {
            d.from + 1 < d.rects.size -> start(d.rects[d.from + 1]) - end(r)
            d.from > 0 -> start(r) - end(d.rects[d.from - 1])
            else -> 0f
        }
        return end(r) - start(r) + gap
    }

    fun reset() {
        val wasActive = drag.current?.active == true
        drag.current = null
        if (wasActive) rerender()
    }

    /** Mouse position relative to the list's border box. */
    fun local(m: MouseEvent): Pair<Float, Float>? {
        val list = listRef.current?.getBoundingClientRect() ?: return null
        return (m.clientX - list.x) to (m.clientY - list.y)
    }

    /** The drag no longer matches the items (they changed while dragging): drop it without reordering. */
    fun stale(d: Drag) = d.rects.size != p.items.size || d.from >= p.items.size || p.key(p.items[d.from]) != d.key

    useDocumentEvent(EventType.MOUSEMOVE) { e ->
        val d = drag.current ?: return@useDocumentEvent
        if (stale(d)) return@useDocumentEvent reset()
        val (mx, my) = local(e as MouseEvent) ?: return@useDocumentEvent
        val main = if (p.horizontal) mx - d.startX else my - d.startY
        val cross = if (p.horizontal) my - d.startY else mx - d.startX
        if (!d.active) {
            if (abs(main) < SORTABLE_DRAG_THRESHOLD && abs(cross) < SORTABLE_DRAG_THRESHOLD) return@useDocumentEvent
            d.active = true
        }
        val r = d.rects[d.from]
        // Keep the dragged item within the list.
        val min = start(d.rects.first()) - start(r)
        val max = (end(d.rects.last()) - end(r)).coerceAtLeast(min)
        d.offset = main.coerceIn(min, max)
        val c = center(r) + d.offset
        var t = d.from
        for (i in d.from + 1 until d.rects.size) if (c >= center(d.rects[i])) t = i
        for (i in d.from - 1 downTo 0) if (c <= center(d.rects[i])) t = i
        d.target = t
        rerender()
    }

    useDocumentEvent(EventType.MOUSEUP) { e ->
        val d = drag.current ?: return@useDocumentEvent
        if ((e as MouseEvent).button != 0) return@useDocumentEvent
        reset()
        if (!d.active) return@useDocumentEvent
        suppressClick.current = true
        if (d.target != d.from && !stale(d)) {
            val next = p.items.toMutableList()
            next.add(d.target.coerceIn(0, next.size - 1), next.removeAt(d.from))
            p.onReorder?.invoke(next)
        }
    }

    // A drag ends with a release that would otherwise click whatever is under the mouse.
    useDocumentEvent(EventType.CLICK) { e ->
        if (suppressClick.current) {
            suppressClick.current = false
            e.stopPropagation()
            e.preventDefault()
        }
    }
    useDocumentEvent(EventType.MOUSEDOWN) {
        suppressClick.current = false
        // A drag whose release never arrived (e.g. the window lost focus) must not stay stuck.
        reset()
    }

    useDocumentEvent(EventType.KEYDOWN) { e ->
        if (drag.current?.active == true && e is KeyboardEvent && e.key == "Escape") {
            reset()
            e.stopPropagation()
            e.preventDefault()
        }
    }

    fun isHandle(target: Element, item: Element): Boolean {
        var el: Element? = target
        while (el != null && el !== item) {
            if (el.classList.contains("guilib-drag-handle")) return true
            el = el.parent
        }
        return false
    }

    val d = drag.current?.takeIf { it.active && !stale(it) }
    val dragIndex = d?.from ?: -1
    val target = d?.target ?: -1
    val shiftSize = if (d != null) slot(d) else 0f
    div(
        className = classNames("guilib-sortable", "horizontal" to p.horizontal, "handle" to p.handle, "sorting" to (d != null), p.className),
        ref = listRef,
    ) {
        p.items.forEachIndexed { i, item ->
            val shift = when {
                d == null -> 0f
                i == dragIndex -> d.offset
                dragIndex < target && i in dragIndex + 1..target -> -shiftSize
                target < dragIndex && i in target until dragIndex -> shiftSize
                else -> 0f
            }
            val axis = if (p.horizontal) "X" else "Y"
            val dragging = d != null && i == dragIndex
            div(
                className = classNames("guilib-sortable-item", "dragging" to dragging, p.itemClassName),
                style = if (d != null) "transform: translate$axis(${String.format(Locale.ROOT, "%.2f", shift)}px)" else null,
                key = p.key(item),
                onMouseDown = { e ->
                    if (e.button == 0 && (!p.handle || isHandle(e.target, e.currentTarget))) {
                        val list = listRef.current
                        val origin = list?.getBoundingClientRect()
                        val rects = list?.children?.filterIsInstance<Element>()?.map { it.getBoundingClientRect() }
                        if (origin != null && rects != null && rects.size == p.items.size) {
                            // Item boxes relative to the list (no item is transformed between drags).
                            val local = rects.map { Rect(it.x - origin.x, it.y - origin.y, it.width, it.height) }
                            drag.current = Drag(i, p.key(item), e.clientX - origin.x, e.clientY - origin.y, local)
                        }
                    }
                },
            ) { p.children(this, item, dragging) }
        }
    }
}
