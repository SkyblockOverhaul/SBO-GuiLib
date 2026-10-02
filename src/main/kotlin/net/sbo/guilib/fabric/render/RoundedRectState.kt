package net.sbo.guilib.fabric.render

//#if MC >= 26.3
//$$ import com.mojang.renderpearl.api.pipeline.RenderPipeline
//#else
import com.mojang.blaze3d.pipeline.RenderPipeline
//#endif
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import org.joml.Matrix3x2f
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One anti-aliased rounded rectangle drawn with [GuiPipelines.ROUNDED_RECT].
 * [borderWidth] ≥ 0 draws the area inside the border, < 0 draws the border ring of width `-borderWidth`.
 */
class RoundedRectState(
    private val pose: Matrix3x2f,
    private val x: Float,
    private val y: Float,
    private val w: Float,
    private val h: Float,
    private val color: Int,
    radii: FloatArray,
    private val borderWidth: Float,
    private val scissor: ScreenRectangle?,
) : GuiElementRenderState {
    private val packedA: Int
    private val packedB: Int
    private val bounds: ScreenRectangle?

    init {
        fun enc(r: Float) = (r * 2f).roundToInt().coerceIn(0, 255)
        packedA = enc(radii[0]) or (enc(radii[1]) shl 8)
        packedB = enc(radii[2]) or (enc(radii[3]) shl 8)
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val raw = ScreenRectangle(x0, y0, ceil(x + w).toInt() - x0, ceil(y + h).toInt() - y0).transformMaxBounds(pose)
        bounds = if (scissor != null) scissor.intersection(raw) else raw
    }

    override fun buildVertices(consumer: VertexConsumer) {
        val hw = w / 2f
        val hh = h / 2f
        val hx = (hw * 8f).roundToInt()
        val hy = (hh * 8f).roundToInt()
        vertex(consumer, x, y, -hw, -hh, hx, hy)
        vertex(consumer, x, y + h, -hw, hh, hx, hy)
        vertex(consumer, x + w, y + h, hw, hh, hx, hy)
        vertex(consumer, x + w, y, hw, -hh, hx, hy)
    }

    private fun vertex(c: VertexConsumer, px: Float, py: Float, lx: Float, ly: Float, hx: Int, hy: Int) {
        c.addVertexWith2DPose(pose, px, py)
            .setColor(color)
            .setUv(lx, ly)
            .setUv1(hx, hy)
            .setUv2(packedA, packedB)
            .setLineWidth(borderWidth)
    }

    override fun pipeline(): RenderPipeline = GuiPipelines.ROUNDED_RECT
    override fun textureSetup(): TextureSetup = TextureSetup.noTexture()
    override fun scissorArea(): ScreenRectangle? = scissor
    override fun bounds(): ScreenRectangle? = bounds
}
