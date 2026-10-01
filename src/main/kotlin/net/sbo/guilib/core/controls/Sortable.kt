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
    class Drag(val from: Int, val startX: Float, val startY: Float, val rects: List<Rect>) {
        var active = false
    }

    val drag = useRef<Drag?>(null)
    val suppressClick = useRef(false)
    val listRef = useElementRef()
    var dragIndex by useState(-1)
    var target by useState(-1)
    var offset by useState(0f)

    val n = p.items.size
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
        drag.current = null
        dragIndex = -1
        target = -1
        offset = 0f
    }

    useDocumentEvent(EventType.MOUSEMOVE) { e ->
        val d = drag.current ?: return@useDocumentEvent
        val m = e as MouseEvent
        var delta = if (p.horizontal) m.clientX - d.startX else m.clientY - d.startY
        if (!d.active) {
            if (abs(delta) < SORTABLE_DRAG_THRESHOLD && abs((if (p.horizontal) m.clientY - d.startY else m.clientX - d.startX)) < SORTABLE_DRAG_THRESHOLD) {
                return@useDocumentEvent
            }
            d.active = true
            dragIndex = d.from
        }
        val r = d.rects[d.from]
        // Keep the dragged item within the list.
        delta = delta.coerceIn(start(d.rects.first()) - start(r), (end(d.rects.last()) - end(r)).coerceAtLeast(start(d.rects.first()) - start(r)))
        offset = delta
        val c = center(r) + delta
        var t = d.from
        for (i in d.from + 1 until d.rects.size) if (c >= center(d.rects[i])) t = i
        for (i in d.from - 1 downTo 0) if (c <= center(d.rects[i])) t = i
        target = t
    }

    useDocumentEvent(EventType.MOUSEUP) { _ ->
        val d = drag.current ?: return@useDocumentEvent
        val to = target
        reset()
        if (!d.active) return@useDocumentEvent
        suppressClick.current = true
        if (to >= 0 && to != d.from && d.from < p.items.size) {
            val next = p.items.toMutableList()
            next.add(to.coerceAtMost(next.size - 1), next.removeAt(d.from))
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
    useDocumentEvent(EventType.MOUSEDOWN) { suppressClick.current = false }

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

    val d = drag.current
    val dragging = dragIndex >= 0 && d != null
    val shiftSize = if (dragging) slot(d!!) else 0f
    div(
        className = classNames("guilib-sortable", "horizontal" to p.horizontal, "handle" to p.handle, "sorting" to dragging, p.className),
        ref = listRef,
    ) {
        p.items.forEachIndexed { i, item ->
            val shift = when {
                !dragging -> 0f
                i == dragIndex -> offset
                dragIndex < target && i in dragIndex + 1..target -> -shiftSize
                target < dragIndex && i in target until dragIndex -> shiftSize
                else -> 0f
            }
            val axis = if (p.horizontal) "X" else "Y"
            div(
                className = classNames("guilib-sortable-item", "dragging" to (dragging && i == dragIndex), p.itemClassName),
                style = if (dragging) "transform: translate$axis(${String.format(Locale.ROOT, "%.2f", shift)}px)" else null,
                key = p.key(item),
                onMouseDown = { e ->
                    if (e.button == 0 && drag.current == null && (!p.handle || isHandle(e.target, e.currentTarget))) {
                        val rects = listRef.current?.children?.filterIsInstance<Element>()?.map { it.getBoundingClientRect() }
                        if (rects != null && rects.size == n) drag.current = Drag(i, e.clientX, e.clientY, rects)
                    }
                },
            ) { p.children(this, item, dragging && i == dragIndex) }
        }
    }
}
