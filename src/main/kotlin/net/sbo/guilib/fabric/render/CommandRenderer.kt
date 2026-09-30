package net.sbo.guilib.fabric.render

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.world.item.ItemStack
import net.sbo.guilib.core.Log
import net.sbo.guilib.core.css.Colors
import net.sbo.guilib.core.css.ObjectFit
import net.sbo.guilib.core.paint.PaintCommand
import net.sbo.guilib.fabric.font.VanillaFont
import net.sbo.guilib.fabric.image.Images
import org.joml.Matrix3x2f
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Draws [PaintCommand]s with Minecraft's GUI renderer. */
object CommandRenderer {

    /**
     * Minecraft draws all quads of a GUI layer before its items and text. When a quad follows text/items in our
     * paint order it must go to a new layer, otherwise it would end up underneath.
     */
    private var layerHasOverlay = false

    fun draw(ctx: GuiGraphicsExtractor, commands: List<PaintCommand>) {
        layerHasOverlay = false
        var clipDepth = 0
        for (cmd in commands) {
            when (cmd) {
                is PaintCommand.Box -> {
                    quadLayer(ctx); drawBox(ctx, cmd)
                }
                is PaintCommand.Image -> {
                    quadLayer(ctx); drawImage(ctx, cmd)
                }
                is PaintCommand.Text -> {
                    drawText(ctx, cmd); layerHasOverlay = true
                }
                is PaintCommand.Replaced -> {
                    drawReplaced(ctx, cmd); layerHasOverlay = true
                }
                is PaintCommand.PushClip -> {
                    val r = cmd.rect
                    ctx.enableScissor(floor(r.x).toInt(), floor(r.y).toInt(), ceil(r.right).toInt(), ceil(r.bottom).toInt())
                    clipDepth++
                }
                PaintCommand.PopClip -> if (clipDepth > 0) {
                    ctx.disableScissor(); clipDepth--
                }
            }
        }
        repeat(clipDepth) { ctx.disableScissor() }
    }

    private fun quadLayer(ctx: GuiGraphicsExtractor) {
        if (layerHasOverlay) {
            ctx.guiRenderState.up()
            layerHasOverlay = false
        }
    }

    private fun r(v: Float) = v.roundToInt()

    private fun drawBox(ctx: GuiGraphicsExtractor, b: PaintCommand.Box) {
        if (b.width <= 0f || b.height <= 0f) return
        if (b.hasRadius) {
            drawRoundedBox(ctx, b); return
        }
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
        // The SDF shader supports one border width/color; mixed sides with rounded corners use the widest side.
        var bw = 0f
        var bc = 0
        for (i in 0 until 4) if (b.borders[i] > bw) {
            bw = b.borders[i]; bc = b.borderColors[i]
        }
        if (bw > 0f && (b.borders.any { it != bw } || b.borderColors.any { it != bc })) {
            Log.warnOnce("GuiLib: borders with different widths/colors per side are drawn uniformly when border-radius is set")
        }
        val pose = Matrix3x2f(ctx.pose())
        val scissor = ctx.scissorStack.peek()
        val state = ctx.guiRenderState
        if (Colors.alpha(b.background) != 0) {
            state.addGuiElement(RoundedRectState(pose, b.x, b.y, b.width, b.height, b.background, b.radii, bw, scissor))
        }
        if (bw > 0f && Colors.alpha(bc) != 0) {
            state.addGuiElement(RoundedRectState(pose, b.x, b.y, b.width, b.height, bc, b.radii, -bw, scissor))
        }
    }

    private fun drawText(ctx: GuiGraphicsExtractor, t: PaintCommand.Text) {
        if (Colors.alpha(t.color) == 0) return
        val font = Minecraft.getInstance().font
        val scale = VanillaFont.scale(t.style)
        val pose = ctx.pose()
        pose.pushMatrix()
        pose.translate(t.x, t.y)
        if (scale != 1f) pose.scale(scale, scale)
        ctx.text(font, VanillaFont.component(t.text, t.style), 0, 0, t.color, t.style.shadow != null)
        pose.popMatrix()
    }

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

        val pose = ctx.pose()
        pose.pushMatrix()
        pose.translate(dx, dy)
        pose.scale(dw / regionW, dh / regionH)
        val color = Colors.withOpacity(Colors.WHITE, img.alpha)
        ctx.blit(RenderPipelines.GUI_TEXTURED, tex.id, 0, 0, u, v, regionW, regionH, regionW, regionH, tex.width, tex.height, color)
        pose.popMatrix()
    }

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
        }
    }
}
