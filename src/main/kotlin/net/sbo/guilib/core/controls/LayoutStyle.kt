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

/** Minimum distance between a popup and the screen edges. */
internal const val POPUP_MARGIN = 2f

/**
 * One axis of a popup of [size] in a viewport of [view]: its start edge at [after] (e.g. below the anchor) or its end
 * edge at [before] (above it), whichever fits, [preferAfter] first; when neither fits it is pinned to the far edge (or
 * the start edge if it is larger than the screen). Returns `(true, left/top)` or `(false, right/bottom)`. The end side
 * is pinned with `right` / `bottom`, so the popup keeps its natural size: a `left` near the right edge would squeeze a
 * wrapping popup and hide how wide it really is.
 */
private fun popupAxis(after: Float, before: Float, size: Float, view: Float, preferAfter: Boolean): Pair<Boolean, Float> {
    val afterFits = after + size <= view - POPUP_MARGIN
    val beforeFits = before - size >= POPUP_MARGIN
    val useAfter = when {
        preferAfter && afterFits -> true
        !preferAfter && beforeFits -> false
        afterFits -> true
        beforeFits -> false
        else -> return if (size >= view - 2 * POPUP_MARGIN) true to POPUP_MARGIN else false to POPUP_MARGIN
    }
    return if (useAfter) true to maxOf(POPUP_MARGIN, after) else false to view - before
}

private fun axisCss(start: String, end: String, a: Pair<Boolean, Float>) = if (a.first) "$start: ${px(a.second)}" else "$end: ${px(a.second)}"

/**
 * CSS position of a fixed popup of [w]×[h] next to [anchor] on [side] (`top` `bottom` `left` `right`) with [gap]: on
 * the other side when it only fits there, shifted along the anchor's edge to stay inside the [vw]×[vh] screen.
 */
internal fun popupPosition(anchor: Rect, w: Float, h: Float, vw: Float, vh: Float, side: String, gap: Float): String {
    val vertical = side == "top" || side == "bottom"
    return if (vertical) {
        val y = popupAxis(anchor.bottom + gap, anchor.y - gap, h, vh, side == "bottom")
        val x = popupAxis(anchor.x, vw - POPUP_MARGIN, w, vw, true)
        "${axisCss("left", "right", x)}; ${axisCss("top", "bottom", y)}"
    } else {
        val x = popupAxis(anchor.right + gap, anchor.x - gap, w, vw, side == "right")
        val y = popupAxis(anchor.y, vh - POPUP_MARGIN, h, vh, true)
        "${axisCss("left", "right", x)}; ${axisCss("top", "bottom", y)}"
    }
}

/** CSS position of a fixed tooltip of [w]×[h] at the mouse ([mx], [my]): below right of it, else on the other side. */
internal fun pointerPopupPosition(mx: Float, my: Float, w: Float, h: Float, vw: Float, vh: Float): String {
    val x = popupAxis(mx + 8f, mx - 8f, w, vw, true)
    val y = popupAxis(my + 10f, my - 4f, h, vh, true)
    return "${axisCss("left", "right", x)}; ${axisCss("top", "bottom", y)}"
}
