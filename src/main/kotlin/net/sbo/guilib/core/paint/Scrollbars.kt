package net.sbo.guilib.core.paint

import net.sbo.guilib.core.dom.Element

/**
 * Where the scrollbars of a scroll container are, relative to its border box. The painter draws them from here and the
 * mouse drags them with the same numbers.
 */
internal object Scrollbars {
    /** One scrollbar: track and thumb along its axis, [cross] / [thickness] across it. */
    class Bar(val trackStart: Float, val trackLength: Float, val thumbStart: Float, val thumbLength: Float, val cross: Float, val thickness: Float)

    fun thickness(el: Element): Float = if (el.style.scrollbarWidth == "thin") 2f else 3f

    fun vertical(el: Element): Bar? {
        val s = el.style
        val b = el.box
        if (s.scrollbarWidth == "none" || !s.overflowY.scrolls || b.scrollHeight <= b.paddingBoxHeight + 0.5f) return null
        val t = thickness(el)
        val trackLength = b.paddingBoxHeight
        val thumbLength = maxOf(8f, trackLength * trackLength / b.scrollHeight)
        val thumbStart = b.border.top + (trackLength - thumbLength) * (el.scrollTop / el.maxScrollTop.coerceAtLeast(0.0001f))
        return Bar(b.border.top, trackLength, thumbStart, thumbLength, b.width - b.border.right - t, t)
    }

    fun horizontal(el: Element): Bar? {
        val s = el.style
        val b = el.box
        if (s.scrollbarWidth == "none" || !s.overflowX.scrolls || b.scrollWidth <= b.paddingBoxWidth + 0.5f) return null
        val t = thickness(el)
        val trackLength = b.paddingBoxWidth
        val thumbLength = maxOf(8f, trackLength * trackLength / b.scrollWidth)
        val thumbStart = b.border.left + (trackLength - thumbLength) * (el.scrollLeft / el.maxScrollLeft.coerceAtLeast(0.0001f))
        return Bar(b.border.left, trackLength, thumbStart, thumbLength, b.height - b.border.bottom - t, t)
    }
}
