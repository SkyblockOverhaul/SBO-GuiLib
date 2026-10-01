package net.sbo.guilib.fabric.render

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.sbo.guilib.core.Log
import net.sbo.guilib.core.css.Colors
import net.sbo.guilib.core.css.ObjectFit
import net.sbo.guilib.core.dom.Rect
import net.sbo.guilib.core.dom.Transform2D
import net.sbo.guilib.core.paint.PaintCommand
import net.sbo.guilib.fabric.font.FontManager
import net.sbo.guilib.fabric.font.GlyphAtlas
import net.sbo.guilib.fabric.font.TrueTypeFont
import net.sbo.guilib.fabric.font.VanillaFont
import net.sbo.guilib.fabric.image.Images
import org.joml.Matrix3x2f
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Draws [PaintCommand]s with Minecraft's GUI renderer. */
object CommandRenderer {

    /**
     * Keeps our paint order intact on top of Minecraft's GUI batching:
     * - vanilla text and items are drawn after all quads of their layer, so a quad following them needs a new layer;
     * - quads inside a layer are sorted by scissor/pipeline/texture, so a quad overlapping an earlier quad with a
     *   different batch key needs a new layer too.
     */
    private var layerHasOverlay = false
    private val layerQuads = ArrayList<Quad>()

    private class Quad(val key: Any, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    private fun beforeQuad(ctx: GuiGraphicsExtractor, kind: Any, lx0: Float, ly0: Float, lx1: Float, ly1: Float) {
        // Overlap is tested in screen space, so quads drawn under different transforms compare correctly.
        val t = transform
        val sr = if (t == null) null else t.map(Rect(lx0, ly0, lx1 - lx0, ly1 - ly0))
        val x0 = sr?.x ?: lx0
        val y0 = sr?.y ?: ly0
        val x1 = sr?.right ?: lx1
        val y1 = sr?.bottom ?: ly1
        val key = kind to ctx.scissorStack.peek()
        val conflict = layerHasOverlay || layerQuads.any { it.key != key && it.x0 < x1 && x0 < it.x1 && it.y0 < y1 && y0 < it.y1 }
        if (conflict) {
            ctx.guiRenderState.up()
            layerHasOverlay = false
            layerQuads.clear()
        }
        layerQuads += Quad(key, x0, y0, x1, y1)
    }

    /** Mouse position of the current frame, for `entity` elements that follow the cursor. */
    private var mouseX = 0
    private var mouseY = 0

    /** Transform set by the last [PaintCommand.SetTransform] (`null` = screen coordinates). */
    private var transform: Transform2D? = null

    fun draw(ctx: GuiGraphicsExtractor, commands: List<PaintCommand>, mouseX: Int = 0, mouseY: Int = 0) {
        this.mouseX = mouseX
        this.mouseY = mouseY
        layerHasOverlay = false
        layerQuads.clear()
        transform = null
        val pose = ctx.pose()
        val base = Matrix3x2f(pose)
        var clipDepth = 0
        for (cmd in commands) {
            when (cmd) {
                is PaintCommand.Box -> drawBox(ctx, cmd)
                is PaintCommand.Image -> drawImage(ctx, cmd)
                is PaintCommand.Gradient -> if (cmd.mesh.triangleCount > 0 && cmd.width > 0f && cmd.height > 0f) {
                    beforeQuad(ctx, "rounded", cmd.x, cmd.y, cmd.x + cmd.width, cmd.y + cmd.height)
                    ctx.guiRenderState.addGuiElement(
                        GradientMeshState(Matrix3x2f(ctx.pose()), cmd.mesh, cmd.x, cmd.y, cmd.width, cmd.height, cmd.radii, ctx.scissorStack.peek()),
                    )
                }
                is PaintCommand.Shadow -> if (cmd.width > 0f && cmd.height > 0f) {
                    val a = cmd.bounds
                    beforeQuad(ctx, "shadow", a.x, a.y, a.right, a.bottom)
                    ctx.guiRenderState.addGuiElement(ShadowState(Matrix3x2f(ctx.pose()), cmd, ctx.scissorStack.peek()))
                }
                is PaintCommand.Text -> drawText(ctx, cmd)
                is PaintCommand.Replaced -> {
                    drawReplaced(ctx, cmd); layerHasOverlay = true
                }
                is PaintCommand.PushClip -> {
                    // Clip rects are in screen coordinates, whatever transform is active.
                    val r = cmd.rect
                    pose.pushMatrix()
                    pose.set(base)
                    ctx.enableScissor(floor(r.x).toInt(), floor(r.y).toInt(), ceil(r.right).toInt(), ceil(r.bottom).toInt())
                    pose.popMatrix()
                    clipDepth++
                }
                is PaintCommand.SetTransform -> {
                    val t = cmd.transform
                    transform = t
                    pose.set(base)
                    if (t != null) pose.mul(Matrix3x2f(t.a, t.b, t.c, t.d, t.tx, t.ty))
                }
                PaintCommand.PopClip -> if (clipDepth > 0) {
                    ctx.disableScissor(); clipDepth--
                }
            }
        }
        repeat(clipDepth) { ctx.disableScissor() }
        pose.set(base)
        transform = null
        GlyphAtlas.flush()
    }

    private fun r(v: Float) = v.roundToInt()

    // ---- boxes -------------------------------------------------------------------------------------------------

    private fun drawBox(ctx: GuiGraphicsExtractor, b: PaintCommand.Box) {
        if (b.width <= 0f || b.height <= 0f) return
        if (b.hasRadius) {
            beforeQuad(ctx, "rounded", b.x, b.y, b.x + b.width, b.y + b.height)
            drawRoundedBox(ctx, b)
            return
        }
        beforeQuad(ctx, "fill", b.x, b.y, b.x + b.width, b.y + b.height)
        val x0 = r(b.x)
        val y0 = r(b.y)
        val x1 = r(b.x + b.width)
        val y1 = r(b.y + b.height)
        if (x1 <= x0 || y1 <= y0) return
        val bt = r(b.borders[0])
        val br = r(b.borders[1])
        val bb = r(b.borders[2])
        val bl = r(b.borders[3])
        if (Colors.alpha(b.background) != 0) ctx.fill(x0 + bl, y0 + bt, x1 - br, y1 - bb, b.background)
        if (bt > 0) ctx.fill(x0, y0, x1, y0 + bt, b.borderColors[0])
        if (bb > 0) ctx.fill(x0, y1 - bb, x1, y1, b.borderColors[2])
        if (bl > 0) ctx.fill(x0, y0 + bt, x0 + bl, y1 - bb, b.borderColors[3])
        if (br > 0) ctx.fill(x1 - br, y0 + bt, x1, y1 - bb, b.borderColors[1])
    }

    private fun drawRoundedBox(ctx: GuiGraphicsExtractor, b: PaintCommand.Box) {
        val pose = Matrix3x2f(ctx.pose())
        val scissor = ctx.scissorStack.peek()
        val state = ctx.guiRenderState
        val bw = b.borders[0]
        val bc = b.borderColors[0]
        val uniform = (0 until 4).all { b.borders[it] == bw && (bw == 0f || b.borderColors[it] == bc) }

        if (uniform) {
            // One SDF pass for the background inside the border, one for the border ring.
            if (Colors.alpha(b.background) != 0) {
                state.addGuiElement(RoundedRectState(pose, b.x, b.y, b.width, b.height, b.background, b.radii, bw, scissor))
            }
            if (bw > 0f && Colors.alpha(bc) != 0) {
                state.addGuiElement(RoundedRectState(pose, b.x, b.y, b.width, b.height, bc, b.radii, -bw, scissor))
            }
            return
        }

        // Different widths/colors per side: rounded background under the border (like background-clip: border-box),
        // then one straight strip per side. Strips stop where a rounded corner begins so they never stick out of it;
        // sides between square corners (e.g. a header's border-bottom) are exact.
        if (Colors.alpha(b.background) != 0) {
            state.addGuiElement(RoundedRectState(pose, b.x, b.y, b.width, b.height, b.background, b.radii, 0f, scissor))
        }
        val (tl, tr, br, bl) = b.radii.toList()
        val x0 = b.x
        val y0 = b.y
        val x1 = b.x + b.width
        val y1 = b.y + b.height
        fun strip(side: Int, sx0: Float, sy0: Float, sx1: Float, sy1: Float) {
            val c = b.borderColors[side]
            if (b.borders[side] <= 0f || Colors.alpha(c) == 0 || sx1 <= sx0 || sy1 <= sy0) return
            state.addGuiElement(RoundedRectState(pose, sx0, sy0, sx1 - sx0, sy1 - sy0, c, NO_RADII, 0f, scissor))
        }
        strip(0, x0 + tl, y0, x1 - tr, y0 + b.borders[0])
        strip(2, x0 + bl, y1 - b.borders[2], x1 - br, y1)
        strip(3, x0, y0 + maxOf(tl, b.borders[0]), x0 + b.borders[3], y1 - maxOf(bl, b.borders[2]))
        strip(1, x1 - b.borders[1], y0 + maxOf(tr, b.borders[0]), x1, y1 - maxOf(br, b.borders[2]))
    }

    private val NO_RADII = FloatArray(4)

    // ---- text --------------------------------------------------------------------------------------------------

    private fun drawText(ctx: GuiGraphicsExtractor, t: PaintCommand.Text) {
        if (Colors.alpha(t.color) == 0) return
        val shadow = t.style.shadow
        var x = t.x
        for (seg in FontManager.segments(t.text, t.style)) {
            val font = seg.font
            if (font == null) {
                x += drawVanillaText(ctx, seg.text, t, x)
            } else {
                if (shadow != null) {
                    val sc = Colors.withOpacity(shadow.color as? Int ?: t.color, t.alpha)
                    drawTtf(ctx, seg.text, font, t, x + shadow.offsetX, t.y + shadow.offsetY, sc)
                }
                x += drawTtf(ctx, seg.text, font, t, x, t.y, t.color)
            }
        }
    }

    /** Draws with the Minecraft font; returns the advance in GUI px. */
    private fun drawVanillaText(ctx: GuiGraphicsExtractor, text: String, t: PaintCommand.Text, x: Float): Float {
        val font = Minecraft.getInstance().font
        val scale = VanillaFont.scale(t.style)
        val pose = ctx.pose()
        pose.pushMatrix()
        // t.y is the top of the style's ascent; the Minecraft font's baseline is 7px below its top at scale 1.
        val ascent = FontManager.metrics(t.style).ascent
        pose.translate(x, t.y + ascent - 7f * scale)
        if (scale != 1f) pose.scale(scale, scale)
        ctx.text(font, VanillaFont.sequence(text, t.style), 0, 0, t.color, t.style.shadow != null)
        pose.popMatrix()
        layerHasOverlay = true
        return VanillaFont.width(text, t.style)
    }

    /** Draws TTF glyphs snapped to physical pixels; returns the advance in GUI px. */
    private fun drawTtf(ctx: GuiGraphicsExtractor, text: String, font: TrueTypeFont, t: PaintCommand.Text, x: Float, y: Float, color: Int): Float {
        val scale = FontManager.guiScale()
        val px = FontManager.pixelSize(t.style)
        if (Colors.alpha(color) == 0) return FontManager.ttfWidth(text, font, px) / scale
        val metrics = font.metrics(px)
        var penX = (x * scale).roundToInt().toFloat()
        val startX = penX
        val baseline = (y * scale + metrics.ascent).roundToInt().toFloat()
        val byPage = LinkedHashMap<GlyphAtlas.Page, FloatArrayBuilder>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val g = GlyphAtlas.glyph(font, px, cp)
            if (g.page != null) {
                val gx = penX + g.left
                val gy = baseline - g.top
                byPage.getOrPut(g.page) { FloatArrayBuilder() }.add(
                    gx / scale, gy / scale, (gx + g.width) / scale, (gy + g.height) / scale, g.u0, g.v0, g.u1, g.v1,
                )
            }
            penX += font.advance(cp, px)
        }
        val advance = (penX - startX) / scale
        if (byPage.isEmpty()) return advance
        val x1 = x + advance
        val y1 = y + metrics.lineHeight / scale
        val pose = Matrix3x2f(ctx.pose())
        val scissor = ctx.scissorStack.peek()
        val bounds = ScreenRectangle(
            floor(x).toInt(), floor(y).toInt(),
            (ceil(x1) - floor(x)).toInt().coerceAtLeast(1), (ceil(y1) - floor(y)).toInt().coerceAtLeast(1),
        )
        for ((page, quads) in byPage) {
            beforeQuad(ctx, page.id, x, y, x1, y1)
            ctx.guiRenderState.addGuiElement(TextRunState(pose, page, quads.toArray(), color, scissor, bounds))
        }
        return advance
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(64)
        private var size = 0
        fun add(vararg v: Float) {
            if (size + v.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + v.size))
            v.copyInto(data, size)
            size += v.size
        }

        fun toArray() = data.copyOf(size)
    }

    // ---- images ------------------------------------------------------------------------------------------------

    private fun drawImage(ctx: GuiGraphicsExtractor, img: PaintCommand.Image) {
        val entry = Images.entry(img.src) ?: return
        if (img.width <= 0f || img.height <= 0f || entry.width <= 0f || entry.height <= 0f) return
        val nw = entry.width
        val nh = entry.height

        // object-fit: destination rect on screen and the visible part of the source image (in natural px).
        var dx = img.x
        var dy = img.y
        var dw = img.width
        var dh = img.height
        var sx = 0f
        var sy = 0f
        var sw = nw
        var sh = nh
        when (img.fit) {
            ObjectFit.FILL -> {}
            ObjectFit.CONTAIN, ObjectFit.SCALE_DOWN -> {
                var s = minOf(img.width / nw, img.height / nh)
                if (img.fit == ObjectFit.SCALE_DOWN) s = minOf(s, 1f)
                dw = nw * s; dh = nh * s
                dx += (img.width - dw) / 2f; dy += (img.height - dh) / 2f
            }
            ObjectFit.COVER -> {
                val s = maxOf(img.width / nw, img.height / nh)
                sw = img.width / s; sh = img.height / s
                sx = (nw - sw) / 2f; sy = (nh - sh) / 2f
            }
            ObjectFit.NONE -> {
                dw = minOf(nw, img.width); dh = minOf(nh, img.height)
                sw = dw; sh = dh
                sx = (nw - sw) / 2f; sy = (nh - sh) / 2f
                dx += (img.width - dw) / 2f; dy += (img.height - dh) / 2f
            }
        }

        // SVGs are rasterized so that the whole image maps 1:1 to physical pixels at this size.
        val fullW = Images.physical(dw * nw / sw)
        val fullH = Images.physical(dh * nh / sh)
        val tex = Images.texture(entry, fullW, fullH) ?: return
        val tx = tex.width / nw
        val ty = tex.height / nh
        val u = sx * tx
        val v = sy * ty
        val regionW = maxOf(1, (sw * tx).roundToInt())
        val regionH = maxOf(1, (sh * ty).roundToInt())

        beforeQuad(ctx, tex.id, dx, dy, dx + dw, dy + dh)
        val pose = ctx.pose()
        pose.pushMatrix()
        pose.translate(dx, dy)
        pose.scale(dw / regionW, dh / regionH)
        val color = Colors.withOpacity(Colors.WHITE, img.alpha)
        ctx.blit(RenderPipelines.GUI_TEXTURED, tex.id, 0, 0, u, v, regionW, regionH, regionW, regionH, tex.width, tex.height, color)
        pose.popMatrix()
    }

    // ---- replaced content --------------------------------------------------------------------------------------

    private fun drawReplaced(ctx: GuiGraphicsExtractor, cmd: PaintCommand.Replaced) {
        val el = cmd.element
        if (el.tagName == "item") {
            val stack = el.getAttribute("stack") as? ItemStack ?: return
            val pose = ctx.pose()
            pose.pushMatrix()
            pose.translate(cmd.x, cmd.y)
            pose.scale(cmd.width / 16f, cmd.height / 16f)
            ctx.item(stack, 0, 0)
            if (el.getAttribute("decorations") == true) ctx.itemDecorations(Minecraft.getInstance().font, stack, 0, 0)
            pose.popMatrix()
        } else if (el.tagName == "entity") {
            drawEntity(ctx, cmd)
        }
    }

    /**
     * Draws an entity like the player model in the inventory, centered in and scaled to fit the element's box.
     * Approach based on SkyHanni's `FakePlayerRenderable` (https://github.com/hannibal002/SkyHanni, LGPL-2.1).
     */
    private fun drawEntity(ctx: GuiGraphicsExtractor, cmd: PaintCommand.Replaced) {
        val el = cmd.element
        val entity = el.getAttribute("entity") as? LivingEntity ?: return
        val x1 = r(cmd.x)
        val y1 = r(cmd.y)
        val x2 = r(cmd.x + cmd.width)
        val y2 = r(cmd.y + cmd.height)
        if (x2 <= x1 || y2 <= y1) return
        // The renderer centers the bounding box; leave room for limbs and the head turning (vanilla: 30 in a 49×70 box).
        val fit = minOf((y2 - y1) * 0.85f / entity.bbHeight, (x2 - x1) * 0.85f / (entity.bbWidth + 0.5f))
        val size = (fit * ((el.getAttribute("scale") as? Float) ?: 1f)).roundToInt()
        if (size <= 0) return
        // Vanilla turns the entity towards (lookAtX, lookAtY) relative to the box center.
        val lookAtX: Float
        val lookAtY: Float
        if (el.getAttribute("followmouse") == true) {
            lookAtX = mouseX.toFloat()
            lookAtY = mouseY.toFloat()
        } else {
            lookAtX = (x1 + x2) / 2f + ((el.getAttribute("lookx") as? Float) ?: 0f)
            lookAtY = (y1 + y2) / 2f + ((el.getAttribute("looky") as? Float) ?: 0f)
        }
        InventoryScreen.extractEntityInInventoryFollowsMouse(ctx, x1, y1, x2, y2, size, 0.0625f, lookAtX, lookAtY, entity)
    }
}
