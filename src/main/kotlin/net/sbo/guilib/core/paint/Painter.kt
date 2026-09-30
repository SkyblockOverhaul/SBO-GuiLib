package net.sbo.guilib.core.paint

import net.sbo.guilib.core.css.Colors
import net.sbo.guilib.core.css.ComputedStyle
import net.sbo.guilib.core.css.Display
import net.sbo.guilib.core.css.PointerEvents
import net.sbo.guilib.core.css.Position
import net.sbo.guilib.core.css.Visibility
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.Rect
import net.sbo.guilib.core.dom.TextNode
import net.sbo.guilib.core.layout.Fragment
import net.sbo.guilib.core.layout.LayoutBox
import net.sbo.guilib.core.layout.TextMeasurer

/**
 * Turns a laid-out element tree into [PaintCommand]s and [HitRegion]s.
 *
 * Painting order (simplified CSS stacking): an element paints its background/border, its text, its in-flow children,
 * then its positioned descendants sorted by `z-index` (every positioned element acts as its own layer).
 * `opacity` multiplies into the colors of the whole subtree.
 */
class Painter(private val measurer: TextMeasurer) {
    val commands = ArrayList<PaintCommand>()
    val hitRegions = ArrayList<HitRegion>()

    private class Layer(val el: Element, val x: Float, val y: Float, val clip: Rect?, val round: RoundClip?, val alpha: Float, val z: Int, val order: Int)

    /** Padding box and inner corner radii of the nearest clipping ancestor with `border-radius`. */
    private class RoundClip(val rect: Rect, val radii: FloatArray)

    /**
     * The rounded clip that applies to what is being painted right now. Clipping itself is rectangular (scissor);
     * boxes lying exactly in a rounded corner get that corner's radius instead, so e.g. a header background inside
     * a rounded card with `overflow: hidden` follows the card's corners like on the web.
     */
    private var roundClip: RoundClip? = null

    private var layerOrder = 0

    fun paint(root: Element) {
        commands.clear()
        hitRegions.clear()
        layerOrder = 0
        roundClip = null
        paintLayer(root, 0f, 0f, null, 1f)
    }

    /** Paints [el] at absolute ([x], [y]) together with its non-positioned subtree, then its positioned descendants. */
    private fun paintLayer(el: Element, x: Float, y: Float, clip: Rect?, alpha: Float) {
        val layers = ArrayList<Layer>()
        paintElement(el, x, y, clip, alpha, layers)
        layers.sortWith(compareBy<Layer>({ it.z }, { it.order }))
        for (l in layers) {
            val saved = roundClip
            roundClip = l.round
            paintLayer(l.el, l.x, l.y, l.clip, l.alpha)
            roundClip = saved
        }
    }

    private fun paintElement(el: Element, x: Float, y: Float, clip: Rect?, parentAlpha: Float, layers: MutableList<Layer>) {
        val s = el.style
        val b = el.box
        if (!b.visible || s.display == Display.NONE) return
        val alpha = parentAlpha * s.opacity
        if (alpha <= 0.001f) return
        val rect = Rect(x, y, b.width, b.height)
        val visible = s.visibility == Visibility.VISIBLE

        if (visible) {
            paintBox(el, s, rect, alpha)
            if (s.pointerEvents != PointerEvents.NONE) hitRegions += HitRegion(el, rect, clip)
        }

        // Clip children to the padding box if overflow isn't visible.
        val clips = s.overflowX.clips || s.overflowY.clips
        var childClip = clip
        val outerRound = roundClip
        if (clips) {
            val pad = Rect(x + b.border.left, y + b.border.top, b.paddingBoxWidth, b.paddingBoxHeight)
            childClip = clip?.intersect(pad) ?: pad
            commands += PaintCommand.PushClip(childClip)
            val outer = radii(s, rect)
            roundClip = if (outer.any { it > 0f }) {
                // Inner radius = outer radius minus the adjacent borders (like CSS padding-box corners).
                RoundClip(
                    pad,
                    floatArrayOf(
                        (outer[0] - maxOf(b.border.top, b.border.left)).coerceAtLeast(0f),
                        (outer[1] - maxOf(b.border.top, b.border.right)).coerceAtLeast(0f),
                        (outer[2] - maxOf(b.border.bottom, b.border.right)).coerceAtLeast(0f),
                        (outer[3] - maxOf(b.border.bottom, b.border.left)).coerceAtLeast(0f),
                    ),
                )
            } else null
        }
        val sx = x - el.scrollLeft
        val sy = y - el.scrollTop

        if (visible) paintParagraphs(el.box, sx, sy, alpha)

        for (child in el.children) {
            if (child is TextNode) {
                // Text that became its own box (e.g. a flex item) carries its own paragraph.
                val tb = child.box
                if (visible && tb.visible && !tb.inParagraph) paintParagraphs(tb, sx + tb.x, sy + tb.y, alpha)
                continue
            }
            if (child !is Element) continue
            val cb = child.box
            if (!cb.visible) continue
            if (cb.inParagraph) {
                // Inline elements were painted as part of the paragraph; register them for hit-testing and paint
                // atomic inline descendants (inline-block, images), which the inline layout positioned relative to [el].
                paintInlineElement(child, el, sx, sy, childClip, alpha, layers)
                continue
            }
            val cs = child.style
            if (cs.position == Position.FIXED) {
                val (fx, fy) = unscrolledOrigin(child)
                layers += Layer(child, fx, fy, null, null, alpha, cs.zIndex ?: 0, layerOrder++)
            } else if (cs.position != Position.STATIC) {
                layers += Layer(child, sx + cb.x, sy + cb.y, childClip, roundClip, alpha, cs.zIndex ?: 0, layerOrder++)
            } else {
                paintElement(child, sx + cb.x, sy + cb.y, childClip, alpha, layers)
            }
        }

        if (clips) {
            paintScrollbars(el, s, rect, alpha)
            commands += PaintCommand.PopClip
            roundClip = outerRound
        }
    }

    private fun paintInlineElement(inlineEl: Element, container: Element, x: Float, y: Float, clip: Rect?, alpha: Float, layers: MutableList<Layer>) {
        if (inlineEl.style.pointerEvents != PointerEvents.NONE) registerInlineHits(inlineEl, container, x, y, clip)
        for (c in inlineEl.children) {
            if (c !is Element || !c.box.visible) continue
            if (c.box.inParagraph) paintInlineElement(c, container, x, y, clip, alpha, layers)
            else paintElement(c, x + c.box.x, y + c.box.y, clip, alpha, layers)
        }
    }

    /** Absolute position ignoring ancestors' scroll offsets (fixed elements don't scroll). */
    private fun unscrolledOrigin(el: Element): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var n: Element? = el
        while (n != null) {
            x += n.box.x; y += n.box.y; n = n.parent
        }
        return x to y
    }

    private fun paintBox(el: Element, s: ComputedStyle, r: Rect, alpha: Float) {
        val b = el.box
        val bg = Colors.withOpacity(s.backgroundColor, alpha)
        val borders = floatArrayOf(b.border.top, b.border.right, b.border.bottom, b.border.left)
        val hasBorder = borders.any { it > 0f }
        val radii = radii(s, r)
        followRoundClip(r, radii)
        if (Colors.alpha(bg) > 0 || hasBorder) {
            commands += PaintCommand.Box(
                r.x, r.y, r.width, r.height, bg, radii, borders,
                intArrayOf(
                    Colors.withOpacity(s.borderTopColor, alpha), Colors.withOpacity(s.borderRightColor, alpha),
                    Colors.withOpacity(s.borderBottomColor, alpha), Colors.withOpacity(s.borderLeftColor, alpha),
                ),
            )
        }
        s.backgroundImage?.let { src ->
            commands += PaintCommand.Image(r.x, r.y, r.width, r.height, src, net.sbo.guilib.core.css.ObjectFit.FILL, alpha, radii)
        }
        if (el.replaced != null) {
            val cx = r.x + b.contentX
            val cy = r.y + b.contentY
            val src = el.getAttribute("src") as? String
            if (src != null) commands += PaintCommand.Image(cx, cy, b.contentWidth, b.contentHeight, src, s.objectFit, alpha, radii)
            else commands += PaintCommand.Replaced(el, cx, cy, b.contentWidth, b.contentHeight, alpha)
        }
    }

    /** Rounds the corners of [r] that sit exactly in a rounded corner of the current clip. */
    private fun followRoundClip(r: Rect, radii: FloatArray) {
        val rc = roundClip ?: return
        val c = rc.rect
        fun near(a: Float, b: Float) = kotlin.math.abs(a - b) <= 0.5f
        val max = minOf(r.width, r.height) / 2f
        if (near(r.x, c.x) && near(r.y, c.y)) radii[0] = maxOf(radii[0], minOf(rc.radii[0], max))
        if (near(r.right, c.right) && near(r.y, c.y)) radii[1] = maxOf(radii[1], minOf(rc.radii[1], max))
        if (near(r.right, c.right) && near(r.bottom, c.bottom)) radii[2] = maxOf(radii[2], minOf(rc.radii[2], max))
        if (near(r.x, c.x) && near(r.bottom, c.bottom)) radii[3] = maxOf(radii[3], minOf(rc.radii[3], max))
    }

    private fun radii(s: ComputedStyle, r: Rect): FloatArray {
        val base = minOf(r.width, r.height)
        val out = floatArrayOf(
            s.borderTopLeftRadius.resolve(base) ?: 0f,
            s.borderTopRightRadius.resolve(base) ?: 0f,
            s.borderBottomRightRadius.resolve(base) ?: 0f,
            s.borderBottomLeftRadius.resolve(base) ?: 0f,
        )
        // Scale down overlapping radii like the CSS spec.
        val f = minOf(
            1f,
            r.width / (out[0] + out[1]).coerceAtLeast(0.0001f), r.width / (out[3] + out[2]).coerceAtLeast(0.0001f),
            r.height / (out[0] + out[3]).coerceAtLeast(0.0001f), r.height / (out[1] + out[2]).coerceAtLeast(0.0001f),
        )
        if (f < 1f) for (i in out.indices) out[i] *= f
        return out
    }

    private fun paintParagraphs(box: LayoutBox, x: Float, y: Float, alpha: Float) {
        for (p in box.paragraphs) {
            for (line in p.lines) {
                for (f in line.fragments) {
                    if (f !is Fragment.Text) continue
                    val owner = f.owner as? TextNode
                    if (owner?.parent?.style?.visibility == Visibility.HIDDEN) continue
                    val m = measurer.metrics(f.style)
                    val tx = x + p.x + f.x
                    val baseline = y + p.y + line.y + line.baseline
                    val color = Colors.withOpacity(f.style.color, alpha)
                    commands += PaintCommand.Text(tx, baseline - m.ascent, f.text, f.style, color, alpha)
                    val thickness = maxOf(1f, f.style.fontSize / 12f)
                    if (f.style.decoration.underline) {
                        commands += solid(tx, baseline + thickness, f.width, thickness, color)
                    }
                    if (f.style.decoration.lineThrough) {
                        commands += solid(tx, baseline - m.ascent * 0.35f - thickness / 2f, f.width, thickness, color)
                    }
                }
            }
        }
    }

    private fun registerInlineHits(inlineEl: Element, container: Element, x: Float, y: Float, clip: Rect?) {
        // The text fragments owned by this inline element's text nodes become its hit regions.
        for (p in container.box.paragraphs) for (line in p.lines) for (f in line.fragments) {
            val owner = (f.owner as? TextNode)?.parent ?: continue
            if (!inlineEl.contains(owner)) continue
            hitRegions += HitRegion(owner, Rect(x + p.x + f.x, y + p.y + line.y, f.width, line.height), clip)
        }
    }

    private fun paintScrollbars(el: Element, s: ComputedStyle, r: Rect, alpha: Float) {
        if (s.scrollbarWidth == "none") return
        val b = el.box
        val thickness = if (s.scrollbarWidth == "thin") 2f else 3f
        val (thumbColor, trackColor) = s.scrollbarColor ?: (0x80FFFFFF.toInt() to 0x20000000)
        if (s.overflowY.scrolls && b.scrollHeight > b.paddingBoxHeight + 0.5f) {
            val trackX = r.x + b.width - b.border.right - thickness
            val trackY = r.y + b.border.top
            val trackH = b.paddingBoxHeight
            val thumbH = maxOf(8f, trackH * trackH / b.scrollHeight)
            val thumbY = trackY + (trackH - thumbH) * (el.scrollTop / el.maxScrollTop.coerceAtLeast(0.0001f))
            commands += solid(trackX, trackY, thickness, trackH, Colors.withOpacity(trackColor, alpha))
            commands += rounded(trackX, thumbY, thickness, thumbH, Colors.withOpacity(thumbColor, alpha), thickness / 2f)
        }
        if (s.overflowX.scrolls && b.scrollWidth > b.paddingBoxWidth + 0.5f) {
            val trackX = r.x + b.border.left
            val trackY = r.y + b.height - b.border.bottom - thickness
            val trackW = b.paddingBoxWidth
            val thumbW = maxOf(8f, trackW * trackW / b.scrollWidth)
            val thumbX = trackX + (trackW - thumbW) * (el.scrollLeft / el.maxScrollLeft.coerceAtLeast(0.0001f))
            commands += solid(trackX, trackY, trackW, thickness, Colors.withOpacity(trackColor, alpha))
            commands += rounded(thumbX, trackY, thumbW, thickness, Colors.withOpacity(thumbColor, alpha), thickness / 2f)
        }
    }

    private fun solid(x: Float, y: Float, w: Float, h: Float, color: Int) =
        PaintCommand.Box(x, y, w, h, color, FloatArray(4), FloatArray(4), IntArray(4))

    private fun rounded(x: Float, y: Float, w: Float, h: Float, color: Int, r: Float) =
        PaintCommand.Box(x, y, w, h, color, floatArrayOf(r, r, r, r), FloatArray(4), IntArray(4))

    /** Topmost element at ([x], [y]), respecting clips and `pointer-events: none`. */
    fun hitTest(x: Float, y: Float): Element? {
        for (i in hitRegions.indices.reversed()) {
            val h = hitRegions[i]
            if (!h.rect.contains(x, y)) continue
            if (h.clip != null && !h.clip.contains(x, y)) continue
            return h.element
        }
        return null
    }
}
