package net.sbo.guilib.core.paint

import net.sbo.guilib.core.css.BackgroundLayer
import net.sbo.guilib.core.css.BgBox
import net.sbo.guilib.core.css.BorderStyle
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
import kotlin.math.roundToInt

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

    /**
     * Natural size of an image `src` (for `background-size: auto/cover/contain`, positions and tiling), or `null` if
     * unknown. Set by the backend; without it, positioned/tiled `url()` layers are skipped.
     */
    var imageSize: (String) -> Pair<Float, Float>? = { null }
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
        paintLayers(layers)
    }

    /** Paints positioned descendants collected by [paintElement], sorted by `z-index`. */
    private fun paintLayers(layers: MutableList<Layer>) {
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
        // `filter` rewrites everything the element paints, so (like CSS, where it creates a stacking context) its
        // positioned descendants are painted with it instead of joining the outer layers.
        val filter = s.filter
        val filterStart = commands.size
        val poseAtStart = emittedPose
        val outerLayers = layers
        @Suppress("NAME_SHADOWING")
        val layers = if (filter.isEmpty()) outerLayers else ArrayList()

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
        if (visible && s.outlineWidth > 0f) paintOutline(s, rect, alpha, local)
        if (filter.isNotEmpty()) {
            paintLayers(layers)
            pose = elPose
            val space = if (aligned) FilterPass.Space(kotlin.math.abs(xf.sx), kotlin.math.abs(xf.sy), xf.scale, null) else FilterPass.Space(1f, 1f, 1f, xf)
            FilterPass.apply(commands, filterStart, filter, space, poseAtStart)
        }
    }

    /**
     * `outline`: drawn after the element's content, outside its border box (moved out by `outline-offset`), following
     * `border-radius` like current browsers. It takes no space and isn't clickable.
     */
    private fun paintOutline(s: ComputedStyle, layout: Rect, alpha: Float, xf: Transform2D) {
        val w = s.outlineWidth
        val grow = s.outlineOffset + w
        val box = Rect(layout.x - grow, layout.y - grow, layout.width + 2 * grow, layout.height + 2 * grow)
        if (box.width <= 0f || box.height <= 0f) return
        val r = xf.map(box)
        val sx = kotlin.math.abs(xf.sx)
        val sy = kotlin.math.abs(xf.sy)
        val widths = floatArrayOf(w * sy, w * sx, w * sy, w * sx)
        val inner = radii(s, layout, xf)
        val radii = FloatArray(4) { if (inner[it] > 0f) (inner[it] + grow * maxOf(sx, sy)).coerceAtLeast(0f) else 0f }
        val color = Colors.withOpacity(s.outlineColor, alpha)
        val colors = intArrayOf(color, color, color, color)
        val style = s.outlineStyle
        if (style == BorderStyle.DASHED || style == BorderStyle.DOTTED) {
            emitBrokenBorders(r, widths, colors, Array(4) { style }, BooleanArray(4) { true }, radii)
        } else {
            emit(PaintCommand.Box(r.x, r.y, r.width, r.height, Colors.TRANSPARENT, radii, widths, colors))
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
        val pads = floatArrayOf(b.padding.top * by, b.padding.right * bx, b.padding.bottom * by, b.padding.left * bx)
        decorate(s, r, borders, radii, alpha, xf, pads)
        if (el.replaced != null) {
            val c = xf.map(Rect(layout.x + b.contentX, layout.y + b.contentY, b.contentWidth, b.contentHeight))
            val src = el.getAttribute("src") as? String
            if (src != null) emit(PaintCommand.Image(c.x, c.y, c.width, c.height, src, s.objectFit, alpha, radii, s.color))
            else emit(PaintCommand.Replaced(el, c.x, c.y, c.width, c.height, alpha))
        }
    }

    /** Shadows, background and border of the border box [r] (already mapped through [xf]). */
    private fun decorate(s: ComputedStyle, r: Rect, borders: FloatArray, radii: FloatArray, alpha: Float, xf: Transform2D, pads: FloatArray) {
        val bg = Colors.withOpacity(s.backgroundColor, alpha)
        val borderColors = intArrayOf(
            Colors.withOpacity(s.borderTopColor, alpha), Colors.withOpacity(s.borderRightColor, alpha),
            Colors.withOpacity(s.borderBottomColor, alpha), Colors.withOpacity(s.borderLeftColor, alpha),
        )
        // Dashed/dotted sides are drawn on their own after the box; the background reaches under them (border-box).
        val styles = arrayOf(s.borderTopStyle, s.borderRightStyle, s.borderBottomStyle, s.borderLeftStyle)
        val broken = BooleanArray(4) { borders[it] > 0f && (styles[it] == BorderStyle.DASHED || styles[it] == BorderStyle.DOTTED) }
        val anyBroken = broken.any { it }
        val solid = if (anyBroken) FloatArray(4) { if (broken[it]) 0f else borders[it] } else borders
        val hasBorder = solid.any { it > 0f }
        val shadows = s.boxShadow
        // Outer shadows go under the box; the first shadow in the list is on top, so paint the list backwards.
        if (shadows.isNotEmpty()) for (sh in shadows.asReversed()) if (!sh.inset) emitShadow(sh, r, radii, alpha, xf)
        val layers = s.backgroundLayers
        // The color is clipped like the bottom layer (background-clip); border-box is the common, single-command case.
        val colorClip = pick(s.backgroundClips, maxOf(layers.size - 1, 0)) ?: BgBox.BORDER_BOX
        if (layers.isEmpty() && colorClip == BgBox.BORDER_BOX) {
            if (Colors.alpha(bg) > 0 || hasBorder) emit(PaintCommand.Box(r.x, r.y, r.width, r.height, bg, radii, solid, borderColors))
        } else {
            // CSS order: background-color, then the image layers from last to first, then the border on top.
            if (Colors.alpha(bg) > 0) {
                val (c, cr) = backgroundBox(colorClip, r, borders, pads, radii)
                if (c.width > 0f && c.height > 0f) emit(PaintCommand.Box(c.x, c.y, c.width, c.height, bg, cr, FloatArray(4), IntArray(4)))
            }
            for (i in layers.indices.reversed()) paintLayer(s, layers[i], i, r, borders, pads, radii, alpha)
            if (hasBorder) emit(PaintCommand.Box(r.x, r.y, r.width, r.height, Colors.TRANSPARENT, radii, solid, borderColors))
        }
        if (anyBroken) emitBrokenBorders(r, borders, borderColors, styles, broken, radii)
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

    private fun <T> pick(list: List<T>, i: Int): T? = if (list.isEmpty()) null else list[i % list.size]

    /** The border, padding or content box of the border box [r], with the matching inner corner radii. */
    private fun backgroundBox(box: BgBox, r: Rect, borders: FloatArray, pads: FloatArray, radii: FloatArray): Pair<Rect, FloatArray> {
        if (box == BgBox.BORDER_BOX) return r to radii
        val e = if (box == BgBox.PADDING_BOX) borders else FloatArray(4) { borders[it] + pads[it] }
        val rect = Rect(r.x + e[3], r.y + e[0], (r.width - e[1] - e[3]).coerceAtLeast(0f), (r.height - e[0] - e[2]).coerceAtLeast(0f))
        val inner = floatArrayOf(
            (radii[0] - maxOf(e[0], e[3])).coerceAtLeast(0f), (radii[1] - maxOf(e[0], e[1])).coerceAtLeast(0f),
            (radii[2] - maxOf(e[2], e[1])).coerceAtLeast(0f), (radii[3] - maxOf(e[2], e[3])).coerceAtLeast(0f),
        )
        return rect to inner
    }

    /**
     * One background image layer. Without size, position and repeat it is stretched over its clip box (GuiLib's
     * default, rounded with the box); otherwise it is sized, placed and repeated like CSS and clipped to the clip box
     * (a rectangle: rounded corners don't cut the copies).
     */
    private fun paintLayer(s: ComputedStyle, layer: BackgroundLayer, i: Int, r: Rect, borders: FloatArray, pads: FloatArray, radii: FloatArray, alpha: Float) {
        val (clip, clipRadii) = backgroundBox(pick(s.backgroundClips, i) ?: BgBox.BORDER_BOX, r, borders, pads, radii)
        if (clip.width <= 0f || clip.height <= 0f) return
        val size = pick(s.backgroundSizes, i)
        val position = pick(s.backgroundPositions, i)
        val repeat = pick(s.backgroundRepeats, i)
        if (size == null && position == null && repeat == null) {
            when (layer) {
                is BackgroundLayer.Url ->
                    emit(PaintCommand.Image(clip.x, clip.y, clip.width, clip.height, layer.src, net.sbo.guilib.core.css.ObjectFit.FILL, alpha, clipRadii, s.color))
                is BackgroundLayer.Gradient ->
                    emit(PaintCommand.Gradient(clip.x, clip.y, clip.width, clip.height, GradientMesh.cached(layer, clip.width, clip.height, alpha), clipRadii))
            }
            return
        }
        val area = backgroundBox(pick(s.backgroundOrigins, i) ?: BgBox.PADDING_BOX, r, borders, pads, radii).first
        val natural = when (layer) {
            is BackgroundLayer.Url -> imageSize(layer.src) ?: return
            is BackgroundLayer.Gradient -> null
        }
        val tiles = BackgroundTiles.tiles(area, clip, natural, size, position, repeat)
        if (tiles.isEmpty()) return
        val eps = 0.01f
        val needsClip = tiles.any { it.x < clip.x - eps || it.y < clip.y - eps || it.right > clip.right + eps || it.bottom > clip.bottom + eps }
        if (needsClip) emit(PaintCommand.PushClip(pose?.map(clip) ?: clip))
        val none = FloatArray(4)
        for (t in tiles) when (layer) {
            is BackgroundLayer.Url ->
                emit(PaintCommand.Image(t.x, t.y, t.width, t.height, layer.src, net.sbo.guilib.core.css.ObjectFit.FILL, alpha, none, s.color))
            is BackgroundLayer.Gradient ->
                emit(PaintCommand.Gradient(t.x, t.y, t.width, t.height, GradientMesh.cached(layer, t.width, t.height, alpha), none))
        }
        if (needsClip) emit(PaintCommand.PopClip)
    }

    /**
     * Dashed and dotted sides of the border box [r], like browsers: a dash (dot) in each square corner, whole dashes
     * with stretched gaps in between. When all four sides are the same dashed border, rounded corners are drawn as
     * solid arcs (each arc is the corner's dash) and dotted corners get dots along the arc. Other rounded corners stay
     * open, like solid borders with different sides.
     */
    private fun emitBrokenBorders(r: Rect, w: FloatArray, colors: IntArray, styles: Array<BorderStyle>, broken: BooleanArray, radii: FloatArray) {
        val uniform = (0 until 4).all { broken[it] && w[it] == w[0] && colors[it] == colors[0] && styles[it] == styles[0] }
        val x0 = r.x
        val y0 = r.y
        val x1 = r.right
        val y1 = r.bottom
        fun rect(x: Float, y: Float, rw: Float, rh: Float, color: Int, round: Float) =
            emit(PaintCommand.Box(x, y, rw, rh, color, FloatArray(4) { round }, FloatArray(4), IntArray(4)))
        fun dotRadius(ws: Float) = if (ws >= 2f) ws / 2f else 0f // thin dots stay square, like browsers

        for (side in 0 until 4) {
            val color = colors[side]
            if (!broken[side] || Colors.alpha(color) == 0) continue
            val ws = w[side]
            val dotted = styles[side] == BorderStyle.DOTTED
            val horizontal = side == 0 || side == 2
            // Along the side: top/bottom left to right, left/right top to bottom.
            val rStart = if (side == 2) radii[3] else if (side == 1) radii[1] else radii[0]
            val rEnd = when (side) { 0 -> radii[1]; 1, 2 -> radii[2]; else -> radii[3] }
            val adjStart = if (horizontal) w[3] else w[0]
            val adjEnd = if (horizontal) w[1] else w[2]
            // Top and bottom own the square corners; left and right fit between their corner dashes.
            val dashAtStart = rStart <= 0f && (horizontal || adjStart <= 0f)
            val dashAtEnd = rEnd <= 0f && (horizontal || adjEnd <= 0f)
            val a = (if (horizontal) x0 else y0) + if (rStart > 0f) rStart else if (dashAtStart) 0f else adjStart
            val b = (if (horizontal) x1 else y1) - if (rEnd > 0f) rEnd else if (dashAtEnd) 0f else adjEnd
            val dash = if (dotted) ws else 3f * ws
            val slots = BorderDashes.slots(b - a, dash, if (dotted) ws else 3f * ws, dashAtStart, dashAtEnd)
            val across = when (side) { 0 -> y0; 1 -> x1 - ws; 2 -> y1 - ws; else -> x0 }
            for (i in 0 until slots.size / 2) {
                var start = a + slots[2 * i]
                var len = slots[2 * i + 1]
                if (dotted) { start += (len - ws) / 2f; len = ws } // dots keep their size, only the gaps stretch
                val round = if (dotted) dotRadius(ws) else 0f
                if (horizontal) rect(start, across, len, ws, color, round) else rect(across, start, ws, len, color, round)
            }
        }
        if (!uniform || Colors.alpha(colors[0]) == 0) return
        val ws = w[0]
        val color = colors[0]
        if (styles[0] == BorderStyle.DOTTED) {
            // Dots along the middle of each rounded corner's border.
            for (corner in 0 until 4) {
                val rad = radii[corner]
                if (rad <= 0f) continue
                val cx = if (corner == 0 || corner == 3) x0 + rad else x1 - rad
                val cy = if (corner < 2) y0 + rad else y1 - rad
                val rc = (rad - ws / 2f).coerceAtLeast(0f)
                val n = maxOf(1, (Math.PI / 2 * rc / (2f * ws)).roundToInt())
                val from = when (corner) { 0 -> Math.PI; 1 -> 1.5 * Math.PI; 2 -> 0.0; else -> 0.5 * Math.PI }
                for (i in 0 until n) {
                    val t = from + Math.PI / 2 * (i + 0.5) / n
                    val px = cx + rc * kotlin.math.cos(t).toFloat()
                    val py = cy + rc * kotlin.math.sin(t).toFloat()
                    rect(px - ws / 2f, py - ws / 2f, ws, ws, color, dotRadius(ws))
                }
            }
        } else if (pose == null) {
            // Solid arcs: a box with just that rounded corner and a uniform border, clipped to the corner square.
            // Clips are screen-space, so rotated or skewed boxes keep open corners.
            for (corner in 0 until 4) {
                val rad = radii[corner]
                if (rad <= 0f) continue
                val size = rad + 2f * ws + 2f
                val left = corner == 0 || corner == 3
                val top = corner < 2
                emit(PaintCommand.PushClip(Rect(if (left) x0 else x1 - rad, if (top) y0 else y1 - rad, rad, rad)))
                val cr = FloatArray(4).also { it[corner] = rad }
                val bx = if (left) x0 else x1 - size
                val by = if (top) y0 else y1 - size
                emit(PaintCommand.Box(bx, by, size, size, Colors.TRANSPARENT, cr, FloatArray(4) { ws }, IntArray(4) { color }))
                emit(PaintCommand.PopClip)
            }
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
                    val fs = f.style.repaintedWith(f.owner.style)
                    val m = measurer.metrics(fs)
                    val tx = x + p.x + f.x
                    val baseline = y + p.y + line.y + line.baseline - f.shift
                    val color = Colors.withOpacity(fs.color, alpha)
                    // Scaled text is drawn at the scaled font size, so it stays sharp (re-rasterized, not stretched).
                    val style = if (fontScale == 1f) fs else fs.copy(fontSize = fs.fontSize * fontScale, letterSpacing = fs.letterSpacing * fontScale)
                    if (style.fontSize < 0.5f) continue
                    emit(PaintCommand.Text(xf.x(tx), xf.y(baseline - m.ascent), f.text, style, color, alpha))
                    val thickness = maxOf(1f, fs.fontSize / 12f)
                    if (fs.decoration.underline) {
                        emit(solid(xf.map(Rect(tx, baseline + thickness, f.width, thickness)), color))
                    }
                    if (fs.decoration.lineThrough) {
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
                val top = baseline - e.box.baselineShift - metrics.ascent - px(st.paddingTop) - bt
                val bottom = baseline - e.box.baselineShift + metrics.descent + px(st.paddingBottom) + bb
                if (right <= left || bottom <= top) continue
                val layout = Rect(left, top, right - left, bottom - top)
                val radii = radii(st, layout, xf)
                if (!starts) { radii[0] = 0f; radii[3] = 0f }
                if (!ends) { radii[1] = 0f; radii[2] = 0f }
                val sx = kotlin.math.abs(xf.sx)
                val sy = kotlin.math.abs(xf.sy)
                val pads = floatArrayOf(px(st.paddingTop) * sy, (if (ends) px(st.paddingRight) else 0f) * sx, px(st.paddingBottom) * sy, (if (starts) px(st.paddingLeft) else 0f) * sx)
                decorate(st, xf.map(layout), floatArrayOf(bt * sy, br * sx, bb * sy, bl * sx), radii, alpha * st.opacity, xf, pads)
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
        val (thumbColor, trackColor) = s.scrollbarColor ?: (0x80FFFFFF.toInt() to 0x20000000)
        Scrollbars.vertical(el)?.let { bar ->
            val x = r.x + bar.cross
            emit(solid(xf.map(Rect(x, r.y + bar.trackStart, bar.thickness, bar.trackLength)), Colors.withOpacity(trackColor, alpha)))
            emit(rounded(xf.map(Rect(x, r.y + bar.thumbStart, bar.thickness, bar.thumbLength)), Colors.withOpacity(thumbColor, alpha), bar.thickness / 2f * xf.scale))
        }
        Scrollbars.horizontal(el)?.let { bar ->
            val y = r.y + bar.cross
            emit(solid(xf.map(Rect(r.x + bar.trackStart, y, bar.trackLength, bar.thickness)), Colors.withOpacity(trackColor, alpha)))
            emit(rounded(xf.map(Rect(r.x + bar.thumbStart, y, bar.thumbLength, bar.thickness)), Colors.withOpacity(thumbColor, alpha), bar.thickness / 2f * xf.scale))
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
