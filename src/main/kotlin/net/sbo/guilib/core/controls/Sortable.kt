package net.sbo.guilib.core.controls

import net.sbo.guilib.core.css.Overflow
import net.sbo.guilib.core.dom.Document
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
    val group: String?,
    val exitMs: Long,
    val children: NodeBuilder.(item: Any?, dragging: Boolean) -> Unit,
)

/** Pixels the mouse has to move before a press turns into a drag (so clicks inside items still work). */
internal const val SORTABLE_DRAG_THRESHOLD = 3f

/** Distance from a scroll container's edge where dragging starts to scroll it, and the top speed (px per ms). */
internal const val SORTABLE_SCROLL_ZONE = 20f
internal const val SORTABLE_SCROLL_SPEED = 0.6f

/** A mounted list, so lists of the same group can find each other. */
internal class SortableHandle(val element: () -> Element?, val props: () -> SortableProps, val rerender: () -> Unit)

/** An item dragged out of [source] that is over the list [target] (where it would be inserted at [index]). */
internal class SortableTransfer(val source: SortableHandle, val size: Float, val target: SortableHandle, val rects: List<Rect>, var index: Int)

/** Per-document registry of the lists in each group and the item currently moving between them. */
internal class SortableGroups {
    val lists = HashMap<String, MutableList<SortableHandle>>()
    val transfers = HashMap<String, SortableTransfer>()

    companion object {
        fun of(doc: Document) = doc.services.getOrPut(SortableGroups::class) { SortableGroups() } as SortableGroups
    }
}

/**
 * Drag-to-reorder list. Items are wrapped in `.guilib-sortable-item` and shifted with `transform` while dragging, so
 * layout never changes until the drop; then `onReorder` receives the reordered list. Lists with the same `group`
 * exchange items: outside its list the dragged item follows the mouse as a ghost in a portal and the list under the
 * mouse opens a gap. Dragging near the edge of a scroll container scrolls it. Alt + arrow keys move the focused item.
 * Styled with `.guilib-sortable` (`.horizontal`, `.sorting` while a drag is active, `.receiving` while a foreign item
 * hovers it), `.guilib-sortable-item` (`.dragging`, `.away` while it is dragged outside, `.guilib-sortable-ghost`,
 * `.leaving` while a removed item plays its exit) and `.guilib-drag-handle`. With `exitMs` > 0 removed items stay at
 * their old position for that long (like presenceList); no drag starts meanwhile.
 */
internal val SortableComponent = component<SortableProps>("Sortable") { p ->
    /**
     * One drag. All positions are relative to the list's own box, so scrolling while dragging keeps them valid.
     * Everything lives here (not in state) so event handlers never see values from an older render.
     */
    class Drag(
        val from: Int, val key: Any?, val startX: Float, val startY: Float, val rects: List<Rect>,
        /** Mouse offset inside the dragged item and its size, for the ghost. */
        val grabX: Float, val grabY: Float,
        /** The dragged item's margin box size along the list, and the list's gap between margin boxes. */
        val outerSize: Float, val gap: Float,
    ) {
        var active = false
        var target = from
        var offset = 0f
        /** Last mouse position (screen), used again when scrolling moves the lists under a still mouse. */
        var clientX = 0f
        var clientY = 0f
        /** The mouse left the list (only lists with a group): the item is shown as a ghost. */
        var outside = false
    }

    val doc = useDocument()
    val groups = SortableGroups.of(doc)
    val drag = useRef<Drag?>(null)
    val suppressClick = useRef(false)
    val listRef = useElementRef()
    val rerender = useForceUpdate()
    val latest = useRef(p)
    latest.current = p
    val self = useRef<SortableHandle?>(null)
    if (self.current == null) self.current = SortableHandle({ listRef.current }, { latest.current }, rerender)
    val me = self.current!!

    // Removed items stay rendered while they leave; items dragged into another list of the group leave at once.
    val now = doc.now()
    val entries = useRef(emptyList<PresenceEntry>())
    val movedAway = useRef(HashSet<Any?>())
    val merged = if (p.exitMs > 0) {
        val prev = entries.current.filter { it.key !in movedAway.current }
        mergePresence(prev, p.items, p.key, now, p.exitMs)
    } else p.items.map { PresenceEntry(p.key(it), it, -1) }
    movedAway.current.clear()
    entries.current = merged
    val nextRemoval = merged.filter { it.leftAt >= 0 }.minOfOrNull { it.leftAt + p.exitMs }
    useEffect(nextRemoval) { if (nextRemoval != null) setTimeout(maxOf(0L, nextRemoval - now)) { rerender() } }

    useEffect(p.group) {
        val g = p.group ?: return@useEffect
        groups.lists.getOrPut(g) { ArrayList() } += me
        onCleanup {
            groups.lists[g]?.remove(me)
            val t = groups.transfers[g]
            if (t != null && (t.target === me || t.source === me)) groups.transfers.remove(g)
        }
    }

    fun start(r: Rect) = if (p.horizontal) r.x else r.y
    fun end(r: Rect) = if (p.horizontal) r.right else r.bottom
    fun center(r: Rect) = (start(r) + end(r)) / 2f

    /**
     * Distance the other items move to fill the gap: the dragged item's margin box plus the list's gap. Margins travel
     * with their item when the list reflows, so the space between two boxes is not used (it holds the neighbour's
     * margin too).
     */
    fun slot(d: Drag): Float = d.outerSize + d.gap

    fun transfer(): SortableTransfer? = p.group?.let { g -> groups.transfers[g]?.takeIf { it.source === me } }

    /** Points the drag at [target] (`null` = no foreign list), re-rendering the lists that change. */
    fun setTransfer(next: SortableTransfer?) {
        val g = p.group ?: return
        val old = groups.transfers[g]
        if (next == null) groups.transfers.remove(g) else groups.transfers[g] = next
        if (old != null && old.target !== next?.target) old.target.rerender()
        next?.target?.rerender()
    }

    fun reset() {
        val wasActive = drag.current?.active == true
        drag.current = null
        if (transfer() != null) setTransfer(null)
        if (wasActive) rerender()
    }

    /** The drag no longer matches the items (they changed while dragging): drop it without reordering. */
    fun stale(d: Drag) = d.rects.size != p.items.size || d.from >= p.items.size || p.key(p.items[d.from]) != d.key

    /** Visible part of [el]: its box clipped by every clipping ancestor. */
    fun visibleRect(el: Element): Rect {
        var r: Rect = el.getBoundingClientRect()
        var a = el.parent
        while (a != null) {
            if (a.style.overflowX.clips || a.style.overflowY.clips) r = r.intersect(a.getBoundingClientRect())
            a = a.parent
        }
        return r
    }

    /** The item boxes of [h]'s list (list-relative). */
    fun targetRects(h: SortableHandle): List<Rect>? {
        val list = h.element() ?: return null
        val o = list.getBoundingClientRect()
        return list.children.filterIsInstance<Element>().filterNot { it.classList.contains("leaving") }.map { val r = it.getBoundingClientRect(); Rect(r.x - o.x, r.y - o.y, r.width, r.height) }
    }

    /** Margins of [el] before and after it along the list. */
    fun margins(el: Element, horizontal: Boolean) =
        if (horizontal) el.box.margin.left to el.box.margin.right else el.box.margin.top to el.box.margin.bottom

    /** The list's own gap: the space between the margin boxes of its first two items (`null` with fewer items). */
    fun listGap(list: Element, horizontal: Boolean): Float? {
        val items = list.children.filterIsInstance<Element>().filterNot { it.classList.contains("leaving") }
        if (items.size < 2) return null
        val a = items[0].getBoundingClientRect()
        val b = items[1].getBoundingClientRect()
        val between = if (horizontal) b.x - a.right else b.y - a.bottom
        return (between - margins(items[0], horizontal).second - margins(items[1], horizontal).first).coerceAtLeast(0f)
    }

    fun contains(ancestor: Element, el: Element): Boolean {
        var e: Element? = el
        while (e != null) {
            if (e === ancestor) return true
            e = e.parent
        }
        return false
    }

    /**
     * Where [h] accepts a dragged item when the mouse is over no list: its largest ancestor that holds no other list
     * of the group (e.g. a kanban column), so empty or short lists don't need a min-height to receive.
     */
    fun dropZone(h: SortableHandle, others: List<Element>): Element? {
        var z = h.element() ?: return null
        while (true) {
            val up = z.parent ?: return z
            if (up.parent == null || up === doc.body || others.any { contains(up, it) }) return z
            z = up
        }
    }

    /** The list of the group under the mouse: a list box first, else a list's [dropZone]. */
    fun listAt(g: String, mx: Float, my: Float): SortableHandle? {
        val lists = groups.lists[g] ?: return null
        lists.firstOrNull { h -> h.element()?.let { visibleRect(it) }?.contains(mx, my) == true }?.let { return it }
        val els = lists.mapNotNull { it.element() }
        return lists.firstOrNull { h ->
            val el = h.element() ?: return@firstOrNull false
            dropZone(h, els.filter { it !== el })?.let { visibleRect(it) }?.contains(mx, my) == true
        }
    }

    fun insertIndex(h: SortableHandle, rects: List<Rect>, mx: Float, my: Float): Int {
        val o = h.element()?.getBoundingClientRect() ?: return rects.size
        val horizontal = h.props().horizontal
        val m = if (horizontal) mx - o.x else my - o.y
        return rects.count { r -> (if (horizontal) r.x + r.width / 2f else r.y + r.height / 2f) < m }
    }

    /** Re-evaluates the drag for the current mouse position (after a move or a scroll). */
    fun update(d: Drag) {
        val list = listRef.current ?: return
        val box = list.getBoundingClientRect()
        val mx = d.clientX
        val my = d.clientY
        val g = p.group
        if (g != null) {
            val over = listAt(g, mx, my)
            if (over !== me) {
                d.outside = true
                if (over == null) setTransfer(null)
                else {
                    val cur = transfer()
                    if (cur == null || cur.target !== over) {
                        val rects = targetRects(over) ?: return
                        val r = d.rects[d.from]
                        val horizontal = over.props().horizontal
                        val gap = over.element()?.let { listGap(it, horizontal) } ?: d.gap
                        // The item keeps its margins (taken along the other list's axis) when it moves over.
                        val outer = if (horizontal == p.horizontal) d.outerSize else {
                            val el = listRef.current?.children?.filterIsInstance<Element>()?.getOrNull(d.from)
                            val (m0, m1) = el?.let { margins(it, horizontal) } ?: (0f to 0f)
                            (if (horizontal) r.width else r.height) + m0 + m1
                        }
                        setTransfer(SortableTransfer(me, outer + gap, over, rects, insertIndex(over, rects, mx, my)))
                    } else {
                        val i = insertIndex(over, cur.rects, mx, my)
                        if (i != cur.index) {
                            cur.index = i; over.rerender()
                        }
                    }
                }
                rerender()
                return
            }
            if (d.outside) {
                d.outside = false
                setTransfer(null)
            }
        }
        val main = if (p.horizontal) mx - box.x - d.startX else my - box.y - d.startY
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

    useDocumentEvent(EventType.MOUSEMOVE) { e ->
        val d = drag.current ?: return@useDocumentEvent
        if (stale(d)) return@useDocumentEvent reset()
        e as MouseEvent
        d.clientX = e.clientX
        d.clientY = e.clientY
        if (!d.active) {
            val o = listRef.current?.getBoundingClientRect() ?: return@useDocumentEvent
            val dx = e.clientX - o.x - d.startX
            val dy = e.clientY - o.y - d.startY
            if (abs(dx) < SORTABLE_DRAG_THRESHOLD && abs(dy) < SORTABLE_DRAG_THRESHOLD) return@useDocumentEvent
            d.active = true
        }
        update(d)
    }

    // Scroll the container under the mouse while it is near its edge.
    val lastTick = useRef(-1L)
    useEffect {
        val hook: (Long) -> Unit = hook@{ now ->
            val d = drag.current
            val prev = lastTick.current
            lastTick.current = now
            if (d == null || !d.active || stale(d) || prev < 0) return@hook
            val dt = (now - prev).coerceIn(0L, 50L).toFloat()
            val t = transfer()
            val scrollFrom = if (d.outside) t?.target?.element() else listRef.current
            val horizontal = if (d.outside) t?.target?.props()?.horizontal ?: return@hook else p.horizontal
            var el: Element? = scrollFrom ?: return@hook
            while (el != null) {
                val ov = if (horizontal) el.style.overflowX else el.style.overflowY
                val max = if (horizontal) el.maxScrollLeft else el.maxScrollTop
                if ((ov == Overflow.AUTO || ov == Overflow.SCROLL) && max > 0f) break
                el = el.parent
            }
            el ?: return@hook
            val r = el.getBoundingClientRect()
            val pos = if (horizontal) d.clientX - r.x else d.clientY - r.y
            val size = if (horizontal) r.width else r.height
            val zone = minOf(SORTABLE_SCROLL_ZONE, size / 4f)
            val speed = when {
                pos < zone -> -(1f - pos.coerceAtLeast(0f) / zone)
                pos > size - zone -> 1f - (size - pos).coerceAtLeast(0f) / zone
                else -> 0f
            }
            if (speed == 0f) return@hook
            val delta = speed * SORTABLE_SCROLL_SPEED * dt
            if (horizontal) {
                val before = el.scrollLeft
                el.scrollLeft = before + delta
                if (el.scrollLeft == before) return@hook
            } else {
                val before = el.scrollTop
                el.scrollTop = before + delta
                if (el.scrollTop == before) return@hook
            }
            update(d)
        }
        doc.frameHooks += hook
        onCleanup { doc.frameHooks -= hook }
    }

    useDocumentEvent(EventType.MOUSEUP) { e ->
        val d = drag.current ?: return@useDocumentEvent
        if ((e as MouseEvent).button != 0) return@useDocumentEvent
        val t = transfer()
        reset()
        if (!d.active) return@useDocumentEvent
        suppressClick.current = true
        if (stale(d)) return@useDocumentEvent
        if (d.outside) {
            if (t == null) return@useDocumentEvent
            val item = p.items[d.from]
            val dest = t.target.props()
            val into = dest.items.toMutableList()
            into.add(t.index.coerceIn(0, into.size), item)
            movedAway.current += d.key
            p.onReorder?.invoke(p.items.filterIndexed { i, _ -> i != d.from })
            dest.onReorder?.invoke(into)
        } else if (d.target != d.from) {
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

    /** Alt + arrow keys (Home/End for the ends) move the item at [i]. */
    fun keyMove(e: KeyboardEvent, i: Int) {
        if (!e.modifiers.alt || p.onReorder == null) return
        val prev = if (p.horizontal) "ArrowLeft" else "ArrowUp"
        val next = if (p.horizontal) "ArrowRight" else "ArrowDown"
        val to = when (e.key) {
            prev -> i - 1
            next -> i + 1
            "Home" -> 0
            "End" -> p.items.size - 1
            else -> return
        }.coerceIn(0, p.items.size - 1)
        e.preventDefault()
        e.stopPropagation()
        if (to == i) return
        val list = p.items.toMutableList()
        list.add(to, list.removeAt(i))
        p.onReorder.invoke(list)
    }

    val d = drag.current?.takeIf { it.active && !stale(it) }
    val dragIndex = d?.from ?: -1
    val target = d?.target ?: -1
    val shiftSize = if (d != null) slot(d) else 0f
    val away = d?.outside == true
    val outgoing = if (away) transfer() else null
    // A foreign item hovering this list: the items from its insert position move aside.
    val incoming = p.group?.let { g -> groups.transfers[g]?.takeIf { it.target === me } }
    val axis = if (p.horizontal) "X" else "Y"
    fun shiftStyle(v: Float) = "transform: translate$axis(${String.format(Locale.ROOT, "%.2f", v)}px)"
    div(
        className = classNames(
            "guilib-sortable", "horizontal" to p.horizontal, "handle" to p.handle, "sorting" to (d != null || incoming != null),
            "receiving" to (incoming != null), p.className,
        ),
        ref = listRef,
    ) {
        var i = -1
        for (entry in merged) {
            val item = entry.item
            if (entry.leftAt >= 0) {
                div(className = classNames("guilib-sortable-item", "leaving", p.itemClassName), key = entry.key) { p.children(this, item, false) }
                continue
            }
            i++
            val index = i
            val shift = when {
                incoming != null -> if (i >= incoming.index) incoming.size else 0f
                d == null -> 0f
                away -> if (outgoing != null && i > dragIndex) -shiftSize else 0f
                i == dragIndex -> d.offset
                dragIndex < target && i in dragIndex + 1..target -> -shiftSize
                target < dragIndex && i in target until dragIndex -> shiftSize
                else -> 0f
            }
            val dragging = d != null && i == dragIndex
            div(
                className = classNames("guilib-sortable-item", "dragging" to dragging, "away" to (dragging && away), p.itemClassName),
                style = if (d != null || incoming != null) shiftStyle(shift) else null,
                key = p.key(item),
                tabIndex = 0,
                onKeyDown = { e -> keyMove(e, index) },
                onMouseDown = { e ->
                    if (e.button == 0 && (!p.handle || isHandle(e.target, e.currentTarget))) {
                        val list = listRef.current
                        val origin = list?.getBoundingClientRect()
                        val rects = list?.children?.filterIsInstance<Element>()?.map { it.getBoundingClientRect() }
                        if (origin != null && rects != null && rects.size == p.items.size) {
                            // Item boxes relative to the list (no item is transformed between drags).
                            val local = rects.map { Rect(it.x - origin.x, it.y - origin.y, it.width, it.height) }
                            val (m0, m1) = margins(list.children.filterIsInstance<Element>()[index], p.horizontal)
                            drag.current = Drag(
                                index, p.key(item), e.clientX - origin.x, e.clientY - origin.y, local,
                                e.clientX - rects[index].x, e.clientY - rects[index].y,
                                end(local[index]) - start(local[index]) + m0 + m1, listGap(list, p.horizontal) ?: 0f,
                            ).also { it.clientX = e.clientX; it.clientY = e.clientY }
                        }
                    }
                },
            ) { p.children(this, item, dragging) }
        }
    }
    // Outside its list the dragged item follows the mouse above everything. The host repeats the list's classes so
    // selectors like `.my-list .my-item` still style it.
    if (d != null && away) {
        val r = d.rects[d.from]
        val o = listRef.current?.getBoundingClientRect()
        if (o != null) portal {
            div(
                className = classNames("guilib-sortable", "horizontal" to p.horizontal, "sorting", p.className),
                style = "position: fixed; left: 0; top: 0; width: 0; height: 0; margin: 0; padding: 0; border-style: none; " +
                    "background: none; box-shadow: none; overflow: visible; pointer-events: none",
            ) {
                div(
                    className = classNames("guilib-sortable-item", "dragging", "guilib-sortable-ghost", p.itemClassName),
                    style = String.format(
                        Locale.ROOT, "position: fixed; left: %.2fpx; top: %.2fpx; width: %.2fpx; height: %.2fpx; margin: 0; pointer-events: none",
                        d.clientX - d.grabX, d.clientY - d.grabY, r.width, r.height,
                    ),
                ) { p.children(this, p.items[d.from], true) }
            }
        }
    }
}
