package net.sbo.guilib.core.controls

import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.Rect
import net.sbo.guilib.core.dom.Ref
import net.sbo.guilib.core.dsl.ComponentScope
import java.util.Locale

/**
 * Inline style of [target] computed from the finished layout every frame (the indicator under the active tab, the
 * height of a collapsing panel, a menu kept inside the screen …). [compute] runs after layout; a changed result is
 * applied in the same frame without a re-render, so CSS transitions animate it. Returns the last style so re-renders
 * keep it: pass it as the element's `style`.
 *
 * The very first value is applied with `transition: none`, so the element doesn't animate in from nowhere.
 */
internal fun ComponentScope.useLayoutStyle(target: Ref<Element?>, compute: () -> String?): String? {
    val last = useRef<String?>(null)
    val latest = useRef(compute)
    latest.current = compute
    val doc = useDocument()
    useEffect {
        val hook: (Long) -> Unit = hook@{
            val el = target.current ?: return@hook
            if (el.ownerDocument !== doc) return@hook
            val next = latest.current() ?: return@hook
            if (next == last.current && el.inlineStyle == next) return@hook
            el.inlineStyle = if (last.current == null) "$next; transition: none" else next
            last.current = next
        }
        doc.frameHooks += hook
        onCleanup { doc.frameHooks -= hook }
    }
    return last.current
}

/**
 * Keeps the client rect a popup is anchored to up to date: while [anchor] is non-null, [target]'s rect is compared
 * after every layout and [update] is called when it moved (window resized, GUI scale changed, layout shifted).
 */
internal fun ComponentScope.useAnchorTracking(target: Ref<Element?>, anchor: Rect?, update: (Rect) -> Unit) {
    val latest = useRef(anchor to update)
    latest.current = anchor to update
    val doc = useDocument()
    useEffect {
        val hook: (Long) -> Unit = hook@{
            val (a, set) = latest.current
            if (a == null) return@hook
            val el = target.current ?: return@hook
            if (el.ownerDocument !== doc) return@hook
            val r = el.getBoundingClientRect()
            if (r != a) set(r)
        }
        doc.frameHooks += hook
        onCleanup { doc.frameHooks -= hook }
    }
}

/** Box of [child] relative to [container]'s padding box origin, including the container's scroll offset. */
internal fun offsetIn(child: Element, container: Element): FloatArray {
    val c = container.getBoundingClientRect()
    val r = child.getBoundingClientRect()
    return floatArrayOf(
        r.x - c.x - container.box.border.left + container.scrollLeft,
        r.y - c.y - container.box.border.top + container.scrollTop,
        r.width,
        r.height,
    )
}

internal fun px(v: Float) = String.format(Locale.ROOT, "%.2fpx", v)
