package net.sbo.guilib.fabric.render

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.ItemStack
import net.sbo.guilib.core.Log
import net.sbo.guilib.core.paint.PaintCommand
import net.sbo.guilib.fabric.font.VanillaFont
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Draws [PaintCommand]s with Minecraft's GUI renderer. */
object CommandRenderer {

    fun draw(ctx: GuiGraphicsExtractor, commands: List<PaintCommand>) {
        var clipDepth = 0
        for (cmd in commands) {
            when (cmd) {
                is PaintCommand.Box -> drawBox(ctx, cmd)
                is PaintCommand.Text -> drawText(ctx, cmd)
                is PaintCommand.Image -> drawImage(ctx, cmd)
                is PaintCommand.Replaced -> drawReplaced(ctx, cmd)
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

    private fun r(v: Float) = v.roundToInt()

    private fun drawBox(ctx: GuiGraphicsExtractor, b: PaintCommand.Box) {
        val x0 = r(b.x)
        val y0 = r(b.y)
        val x1 = r(b.x + b.width)
        val y1 = r(b.y + b.height)
        if (x1 <= x0 || y1 <= y0) return
        val bt = r(b.borders[0])
        val br = r(b.borders[1])
        val bb = r(b.borders[2])
        val bl = r(b.borders[3])
        if (b.background ushr 24 != 0) ctx.fill(x0 + bl, y0 + bt, x1 - br, y1 - bb, b.background)
        if (bt > 0) ctx.fill(x0, y0, x1, y0 + bt, b.borderColors[0])
        if (bb > 0) ctx.fill(x0, y1 - bb, x1, y1, b.borderColors[2])
        if (bl > 0) ctx.fill(x0, y0 + bt, x0 + bl, y1 - bb, b.borderColors[3])
        if (br > 0) ctx.fill(x1 - br, y0 + bt, x1, y1 - bb, b.borderColors[1])
    }

    private fun drawText(ctx: GuiGraphicsExtractor, t: PaintCommand.Text) {
        if (t.color ushr 24 == 0) return
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
        Log.warnOnce("GuiLib: images are not implemented yet (src='${img.src}')")
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
