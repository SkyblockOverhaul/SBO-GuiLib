package net.sbo.guilib.core.paint

import net.sbo.guilib.core.css.BackgroundLayer
import net.sbo.guilib.core.css.BoxShadow
import net.sbo.guilib.core.css.Colors
import net.sbo.guilib.core.css.ComputedStyle
import net.sbo.guilib.core.css.Display
import net.sbo.guilib.core.css.PointerEvents
import net.sbo.guilib.core.css.Position
import net.sbo.guilib.core.css.Visibility
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.Node
import net.sbo.guilib.core.dom.Rect
import net.sbo.guilib.core.dom.TextNode
import net.sbo.guilib.core.dom.Transform2D
import net.sbo.guilib.core.layout.Fragment
import net.sbo.guilib.core.layout.LayoutBox
import net.sbo.guilib.core.layout.Paragraph
import net.sbo.guilib.core.layout.TextStyle
import net.sbo.guilib.core.layout.TextMeasurer

/**
 * Turns a laid-out element tree into [PaintCommand]s and [HitRegion]s.
 *
 * Painting order (simplified CSS stacking): an element paints its background/border, its text, its in-flow children,
 * then its positioned descendants sorted by `z-index` (every positioned element acts as its own layer).
 * `opacity` multiplies into the colors of the whole subtree.
 *
 * `transform` is applied here; layout positions stay untransformed. Axis-aligned transforms (translate, positive
 * scale) are folded into the coordinates: every emitted rectangle is mapped through the element's accumulated
 * [Transform2D], so boxes stay pixel-exact and scaled text is re-rasterized sharp. Other transforms (rotate, skew,
 * mirroring) emit [PaintCommand.SetTransform] and the element's commands stay in layout coordinates.
 * Clips and hit regions are always in screen (GUI) coordinates.
 */
class Painter(private val measurer: TextMeasurer) {
    val commands = ArrayList<PaintCommand>()
    val hitRegions = ArrayList<HitRegion>()

    private class Layer(
        val el: Element, val x: Float, val y: Float, val clip: Rect?, val round: RoundClip?, val alpha: Float, val xf: Transform2D,
        val z: Int, val order: Int,
    )

    /**
     * Padding box and inner corner radii of the nearest clipping ancestor with `border-radius`, in the coordinates
     * of [pose] (`null` = screen).
     */
    private class RoundClip(val rect: Rect, val radii: FloatArray, val pose: Transform2D?)

    /**
     * The rounded clip that applies to what is being painted right now. Clipping itself is rectangular (scissor);
     * boxes lying exactly in a rounded corner get that corner's radius instead, so e.g. a header background inside
     * a rounded card with `overflow: hidden` follows the card's corners like on the web.
     */
    private var roundClip: RoundClip? = null

    private var layerOrder = 0

    /** Transform of the commands emitted next (`null` = screen coordinates) and the one last sent to the backend. */
    private var pose: Transform2D? = null
    private var emittedPose: Transform2D? = null

    private fun emit(cmd: PaintCommand) {
        if (cmd !is PaintCommand.PushClip && cmd != PaintCommand.PopClip && pose != emittedPose) {
            commands += PaintCommand.SetTransform(pose)
            emittedPose = pose
        }
        commands += cmd
    }

    fun paint(root: Element) {
        commands.clear()
        hitRegions.clear()
        layerOrder = 0
        roundClip = null
        pose = null
        emittedPose = null
        paintLayer(root, 0f, 0f, null, 1f, Transform2D.IDENTITY)
    }

    /**
     * Paints [el] at absolute layout position ([x], [y]) together with its non-positioned subtree, then its positioned
     * descendants. [parentXf] is the accumulated transform of the ancestors.
     */
    private fun paintLayer(el: Element, x: Float, y: Float, clip: Rect?, alpha: Float, parentXf: Transform2D) {
        val layers = ArrayList<Layer>()
        paintElement(el, x, y, clip, alpha, layers, parentXf)
        layers.sortWith(compareBy<Layer>({ it.z }, { it.order }))
        for (l in layers) {
            val saved = roundClip
            roundClip = l.round
            // The layer paints after its clipping ancestors popped their clips, so re-apply the clip it was found in.
            if (l.clip != null) emit(PaintCommand.PushClip(l.clip))
            paintLayer(l.el, l.x, l.y, l.clip, l.alpha, l.xf)
            if (l.clip != null) emit(PaintCommand.PopClip)
            roundClip = saved
        }
    }

    private fun paintElement(el: Element, x: Float, y: Float, clip: Rect?, parentAlpha: Float, layers: MutableList<Layer>, parentXf: Transform2D) {
        val s = el.style
        val b = el.box
        if (!b.visible || s.display == Display.NONE) return
        val alpha = parentAlpha * s.opacity
        if (alpha <= 0.001f) return
        val xf = parentXf * Transform2D.of(s, x, y, b.width, b.height)
        if (xf.isDegenerate) return
        // Axis-aligned: map coordinates ourselves. Otherwise draw in layout coordinates through a backend matrix.
        val aligned = xf.isAxisAligned
        val local = if (aligned) xf else Transform2D.IDENTITY
        val elPose = if (aligned) null else xf
        pose = elPose
        val rect = Rect(x, y, b.width, b.height)
        val visible = s.visibility == Visibility.VISIBLE

        if (visible) {
            paintBox(el, s, rect, alpha, local)
            if (s.pointerEvents != PointerEvents.NONE) hitRegions += HitRegion(el.pseudoHost ?: el, rect, clip, xf)
        }

        // Clip children to the padding box if overflow isn't visible.
        val clips = s.overflowX.clips || s.overflowY.clips
        var childClip = clip
        val outerRound = roundClip
        if (clips) {
            val padLayout = Rect(x + b.border.left, y + b.border.top, b.paddingBoxWidth, b.paddingBoxHeight)
            val pad = xf.map(padLayout)
            childClip = clip?.intersect(pad) ?: pad
            emit(PaintCommand.PushClip(childClip))
            val outer = radii(s, rect, local)
            roundClip = if (outer.any { it > 0f }) {
                // Inner radius = outer radius minus the adjacent borders (like CSS padding-box corners).
                RoundClip(
                    local.map(padLayout),
                    floatArrayOf(
                        (outer[0] - maxOf(b.border.top, b.border.left) * local.scale).coerceAtLeast(0f),
                        (outer[1] - maxOf(b.border.top, b.border.right) * local.scale).coerceAtLeast(0f),
                        (outer[2] - maxOf(b.border.bottom, b.border.right) * local.scale).coerceAtLeast(0f),
                        (outer[3] - maxOf(b.border.bottom, b.border.left) * local.scale).coerceAtLeast(0f),
                    ),
                    elPose,
                )
            } else null
        }
        val sx = x - el.scrollLeft
        val sy = y - el.scrollTop

        if (visible) paintParagraphs(el.box, sx, sy, alpha, local, el)

        for (child in el.renderChildren) {
            pose = elPose // a previous child may have painted with its own transform
            if (child is TextNode) {
                // Text that became its own box (e.g. a flex item) carries its own paragraph.
                val tb = child.box
                if (visible && tb.visible && !tb.inParagraph) paintParagraphs(tb, sx + tb.x, sy + tb.y, alpha, local)
                continue
            }
            if (child !is Element) continue
            val cb = child.box
            if (!cb.visible) continue
            if (cb.inParagraph) {
                // Inline elements were painted as part of the paragraph; register them for hit-testing and paint
                // atomic inline descendants (inline-block, images), which the inline layout positioned relative to [el].
                paintInlineElement(child, el, sx, sy, childClip, alpha, layers, xf)
                continue
            }
            val cs = child.style
            if (cs.position == Position.FIXED) {
                val (fx, fy) = unscrolledOrigin(child)
                // Fixed elements ignore ancestors' transforms (simpler than CSS, where a transform contains them).
                layers += Layer(child, fx, fy, null, null, alpha, Transform2D.IDENTITY, cs.zIndex ?: 0, layerOrder++)
            } else if (cs.position != Position.STATIC) {
                layers += Layer(child, sx + cb.x, sy + cb.y, childClip, roundClip, alpha, xf, cs.zIndex ?: 0, layerOrder++)
            } else {
                paintElement(child, sx + cb.x, sy + cb.y, childClip, alpha, layers, xf)
            }
        }
        pose = elPose

        if (clips) {
            paintScrollbars(el, s, rect, alpha, local)
            emit(PaintCommand.PopClip)
            roundClip = outerRound
        }
    }

    private fun paintInlineElement(
        inlineEl: Element, container: Element, x: Float, y: Float, clip: Rect?, alpha: Float, layers: MutableList<Layer>, xf: Transform2D,
    ) {
        if (inlineEl.style.pointerEvents != PointerEvents.NONE) registerInlineHits(inlineEl, container, x, y, clip, xf)
        for (c in inlineEl.renderChildren) {
            if (c !is Element || !c.box.visible) continue
            if (c.box.inParagraph) paintInlineElement(c, container, x, y, clip, alpha, layers, xf)
            else paintElement(c, x + c.box.x, y + c.box.y, clip, alpha, layers, xf)
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

    /** [layout] is the untransformed border box; everything is emitted mapped through [xf]. */
    private fun paintBox(el: Element, s: ComputedStyle, layout: Rect, alpha: Float, xf: Transform2D) {
        val b = el.box
        val r = xf.map(layout)
        val bx = kotlin.math.abs(xf.sx)
        val by = kotlin.math.abs(xf.sy)
        val borders = floatArrayOf(b.border.top * by, b.border.right * bx, b.border.bottom * by, b.border.left * bx)
        val radii = radii(s, layout, xf)
        followRoundClip(r, radii)
        decorate(s, r, borders, radii, alpha, xf)
        if (el.replaced != null) {
            val c = xf.map(Rect(layout.x + b.contentX, layout.y + b.contentY, b.contentWidth, b.contentHeight))
            val src = el.getAttribute("src") as? String
            if (src != null) emit(PaintCommand.Image(c.x, c.y, c.width, c.height, src, s.objectFit, alpha, radii))
            else emit(PaintCommand.Replaced(el, c.x, c.y, c.width, c.height, alpha))
        }
    }

    /** Shadows, background and border of the border box [r] (already mapped through [xf]). */
    private fun decorate(s: ComputedStyle, r: Rect, borders: FloatArray, radii: FloatArray, alpha: Float, xf: Transform2D) {
        val bg = Colors.withOpacity(s.backgroundColor, alpha)
        val hasBorder = borders.any { it > 0f }
        val borderColors = intArrayOf(
            Colors.withOpacity(s.borderTopColor, alpha), Colors.withOpacity(s.borderRightColor, alpha),
            Colors.withOpacity(s.borderBottomColor, alpha), Colors.withOpacity(s.borderLeftColor, alpha),
        )
        val shadows = s.boxShadow
        // Outer shadows go under the box; the first shadow in the list is on top, so paint the list backwards.
        if (shadows.isNotEmpty()) for (sh in shadows.asReversed()) if (!sh.inset) emitShadow(sh, r, radii, alpha, xf)
        val layers = s.backgroundLayers
        if (layers.isEmpty()) {
            if (Colors.alpha(bg) > 0 || hasBorder) emit(PaintCommand.Box(r.x, r.y, r.width, r.height, bg, radii, borders, borderColors))
        } else {
            // CSS order: background-color, then the image layers from last to first, then the border on top.
            if (Colors.alpha(bg) > 0) emit(PaintCommand.Box(r.x, r.y, r.width, r.height, bg, radii, FloatArray(4), IntArray(4)))
            for (layer in layers.asReversed()) when (layer) {
                is BackgroundLayer.Url ->
                    emit(PaintCommand.Image(r.x, r.y, r.width, r.height, layer.src, net.sbo.guilib.core.css.ObjectFit.FILL, alpha, radii))
                is BackgroundLayer.Gradient ->
                    emit(PaintCommand.Gradient(r.x, r.y, r.width, r.height, GradientMesh.build(layer, r.x, r.y, r.width, r.height, alpha), radii))
            }
            if (hasBorder) emit(PaintCommand.Box(r.x, r.y, r.width, r.height, Colors.TRANSPARENT, radii, borders, borderColors))
        }
        // Inset shadows sit inside the padding box, above the background (the border doesn't overlap them).
        if (shadows.any { it.inset }) {
            val pad = Rect(r.x + borders[3], r.y + borders[0], r.width - borders[1] - borders[3], r.height - borders[0] - borders[2])
            val inner = floatArrayOf(
                (radii[0] - maxOf(borders[0], borders[3])).coerceAtLeast(0f), (radii[1] - maxOf(borders[0], borders[1])).coerceAtLeast(0f),
                (radii[2] - maxOf(borders[2], borders[1])).coerceAtLeast(0f), (radii[3] - maxOf(borders[2], borders[3])).coerceAtLeast(0f),
            )
            if (pad.width > 0f && pad.height > 0f) for (sh in shadows.asReversed()) if (sh.inset) emitShadow(sh, pad, inner, alpha, xf)
        }
    }

    private fun emitShadow(sh: BoxShadow, r: Rect, radii: FloatArray, alpha: Float, xf: Transform2D) {
        val color = Colors.withOpacity(sh.color, alpha)
        if (Colors.alpha(color) == 0) return
        val k = xf.scale
        emit(PaintCommand.Shadow(r.x, r.y, r.width, r.height, radii, sh.offsetX * xf.sx, sh.offsetY * xf.sy, sh.blur * k, sh.spread * k, color, sh.inset))
    }

    /** Rounds the corners of [r] that sit exactly in a rounded corner of the current clip. */
    private fun followRoundClip(r: Rect, radii: FloatArray) {
        val rc = roundClip?.takeIf { it.pose == pose } ?: return
        val c = rc.rect
        fun near(a: Float, b: Float) = kotlin.math.abs(a - b) <= 0.5f
        val max = minOf(r.width, r.height) / 2f
        if (near(r.x, c.x) && near(r.y, c.y)) radii[0] = maxOf(radii[0], minOf(rc.radii[0], max))
        if (near(r.right, c.right) && near(r.y, c.y)) radii[1] = maxOf(radii[1], minOf(rc.radii[1], max))
        if (near(r.right, c.right) && near(r.bottom, c.bottom)) radii[2] = maxOf(radii[2], minOf(rc.radii[2], max))
        if (near(r.x, c.x) && near(r.bottom, c.bottom)) radii[3] = maxOf(radii[3], minOf(rc.radii[3], max))
    }

    /** Corner radii of the untransformed box [r], scaled by [xf]. */
    private fun radii(s: ComputedStyle, r: Rect, xf: Transform2D): FloatArray {
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
        val k = f.coerceAtMost(1f) * xf.scale
        if (k != 1f) for (i in out.indices) out[i] *= k
        return out
    }

    private fun paintParagraphs(box: LayoutBox, x: Float, y: Float, alpha: Float, xf: Transform2D, container: Element? = null) {
        val fontScale = kotlin.math.abs(xf.sy)
        for (p in box.paragraphs) {
            if (container != null) paintInlineBoxes(container, p, x, y, alpha, xf)
            for (line in p.lines) {
                for (f in line.fragments) {
                    if (f !is Fragment.Text) continue
                    val owner = f.owner as? TextNode
                    if (owner?.parent?.style?.visibility == Visibility.HIDDEN) continue
                    val m = measurer.metrics(f.style)
                    val tx = x + p.x + f.x
                    val baseline = y + p.y + line.y + line.baseline
                    val color = Colors.withOpacity(f.style.color, alpha)
                    // Scaled text is drawn at the scaled font size, so it stays sharp (re-rasterized, not stretched).
                    val style = if (fontScale == 1f) f.style else f.style.copy(fontSize = f.style.fontSize * fontScale, letterSpacing = f.style.letterSpacing * fontScale)
                    if (style.fontSize < 0.5f) continue
                    emit(PaintCommand.Text(xf.x(tx), xf.y(baseline - m.ascent), f.text, style, color, alpha))
                    val thickness = maxOf(1f, f.style.fontSize / 12f)
                    if (f.style.decoration.underline) {
                        emit(solid(xf.map(Rect(tx, baseline + thickness, f.width, thickness)), color))
                    }
                    if (f.style.decoration.lineThrough) {
                        emit(solid(xf.map(Rect(tx, baseline - m.ascent * 0.35f - thickness / 2f, f.width, thickness)), color))
                    }
                }
            }
        }
    }

    /**
     * Background, border and shadows of `display: inline` elements inside [container]: one box per line the element
     * is on, spanning its fragments; the start/end border, padding and rounded corners only where the element
     * starts/ends (like `box-decoration-break: slice`). Vertical padding and borders extend around the font's
     * ascent/descent without affecting the line height.
     */
    private fun paintInlineBoxes(container: Element, p: Paragraph, x: Float, y: Float, alpha: Float, xf: Transform2D) {
        for (line in p.lines) {
            var spans: LinkedHashMap<Element, FloatArray>? = null // minX, maxX, starts here, ends here
            for (f in line.fragments) {
                var n: Element? = if (f is Fragment.Edge) f.owner as? Element else (f.owner as? Node)?.parent
                while (n != null && n !== container && n.box.inParagraph) {
                    val st = n.style
                    if (Colors.alpha(st.backgroundColor) > 0 || st.backgroundLayers.isNotEmpty() || st.boxShadow.isNotEmpty() ||
                        st.borderTopWidth > 0f || st.borderRightWidth > 0f || st.borderBottomWidth > 0f || st.borderLeftWidth > 0f
                    ) {
                        val map = spans ?: LinkedHashMap<Element, FloatArray>().also { spans = it }
                        val m = map.getOrPut(n) { floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, 0f, 0f) }
                        m[0] = minOf(m[0], f.x)
                        m[1] = maxOf(m[1], f.x + f.width)
                        if (f is Fragment.Edge && f.owner === n) m[if (f.start) 2 else 3] = 1f
                    }
                    n = n.parent
                }
            }
            val found = spans ?: continue
            val baseline = y + p.y + line.y + line.baseline
            // Outer elements first, so nested inline boxes paint on top.
            for ((e, m) in found.entries.sortedBy { depth(it.key) }) {
                val st = e.style
                fun px(d: net.sbo.guilib.core.css.Dim) = d.resolve(0f) ?: 0f
                val starts = m[2] == 1f
                val ends = m[3] == 1f
                val metrics = measurer.metrics(TextStyle.of(st))
                val bt = st.borderTopWidth
                val bb = st.borderBottomWidth
                val bl = if (starts) st.borderLeftWidth else 0f
                val br = if (ends) st.borderRightWidth else 0f
                val left = x + p.x + m[0] + (if (starts) px(st.marginLeft) else 0f)
                val right = x + p.x + m[1] - (if (ends) px(st.marginRight) else 0f)
                val top = baseline - metrics.ascent - px(st.paddingTop) - bt
                val bottom = baseline + metrics.descent + px(st.paddingBottom) + bb
                if (right <= left || bottom <= top) continue
                val layout = Rect(left, top, right - left, bottom - top)
                val radii = radii(st, layout, xf)
                if (!starts) { radii[0] = 0f; radii[3] = 0f }
                if (!ends) { radii[1] = 0f; radii[2] = 0f }
                val sx = kotlin.math.abs(xf.sx)
                val sy = kotlin.math.abs(xf.sy)
                decorate(st, xf.map(layout), floatArrayOf(bt * sy, br * sx, bb * sy, bl * sx), radii, alpha * st.opacity, xf)
            }
        }
    }

    private fun depth(e: Element): Int {
        var d = 0
        var n = e.parent
        while (n != null) {
            d++; n = n.parent
        }
        return d
    }

    private fun registerInlineHits(inlineEl: Element, container: Element, x: Float, y: Float, clip: Rect?, xf: Transform2D) {
        // The text fragments owned by this inline element's text nodes become its hit regions.
        for (p in container.box.paragraphs) for (line in p.lines) for (f in line.fragments) {
            val owner = (f.owner as? TextNode)?.parent ?: continue
            if (!inlineEl.contains(owner)) continue
            hitRegions += HitRegion(owner.pseudoHost ?: owner, Rect(x + p.x + f.x, y + p.y + line.y, f.width, line.height), clip, xf)
        }
    }

    private fun paintScrollbars(el: Element, s: ComputedStyle, r: Rect, alpha: Float, xf: Transform2D) {
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
            emit(solid(xf.map(Rect(trackX, trackY, thickness, trackH)), Colors.withOpacity(trackColor, alpha)))
            emit(rounded(xf.map(Rect(trackX, thumbY, thickness, thumbH)), Colors.withOpacity(thumbColor, alpha), thickness / 2f * xf.scale))
        }
        if (s.overflowX.scrolls && b.scrollWidth > b.paddingBoxWidth + 0.5f) {
            val trackX = r.x + b.border.left
            val trackY = r.y + b.height - b.border.bottom - thickness
            val trackW = b.paddingBoxWidth
            val thumbW = maxOf(8f, trackW * trackW / b.scrollWidth)
            val thumbX = trackX + (trackW - thumbW) * (el.scrollLeft / el.maxScrollLeft.coerceAtLeast(0.0001f))
            emit(solid(xf.map(Rect(trackX, trackY, trackW, thickness)), Colors.withOpacity(trackColor, alpha)))
            emit(rounded(xf.map(Rect(thumbX, trackY, thumbW, thickness)), Colors.withOpacity(thumbColor, alpha), thickness / 2f * xf.scale))
        }
    }

    private fun solid(r: Rect, color: Int) =
        PaintCommand.Box(r.x, r.y, r.width, r.height, color, FloatArray(4), FloatArray(4), IntArray(4))

    private fun rounded(r: Rect, color: Int, radius: Float) =
        PaintCommand.Box(r.x, r.y, r.width, r.height, color, floatArrayOf(radius, radius, radius, radius), FloatArray(4), IntArray(4))

    /** Topmost element at ([x], [y]), respecting clips and `pointer-events: none`. */
    fun hitTest(x: Float, y: Float): Element? {
        for (i in hitRegions.indices.reversed()) {
            val h = hitRegions[i]
            if (!h.contains(x, y)) continue
            return h.element
        }
        return null
    }
}
