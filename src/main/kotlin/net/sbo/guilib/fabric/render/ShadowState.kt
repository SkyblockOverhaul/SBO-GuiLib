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
import net.sbo.guilib.core.paint.PaintCommand
import net.sbo.guilib.core.paint.ShadowMode
import org.joml.Matrix3x2f
import org.joml.Vector2f
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One `box-shadow` layer drawn with [GuiPipelines.BOX_SHADOW]. Uses the rounded-rect vertex format; the values that
 * don't fit are packed exactly into floats: Position.z holds the offset (¼ px steps, ±256 px), LineWidth the blur
 * sigma (½ px steps, up to 127.5), the spread (½ px steps, ±128) and the [ShadowMode].
 */
class ShadowState(private val pose: Matrix3x2f, private val s: PaintCommand.Shadow, private val scissor: ScreenRectangle?) : GuiElementRenderState {
    private val area = s.bounds
    private val packedA: Int
    private val packedB: Int
    private val packedOffset: Float
    private val packedParams: Float
    private val bounds: ScreenRectangle?

    init {
        fun enc(r: Float) = (r * 2f).roundToInt().coerceIn(0, 255)
        packedA = enc(s.radii[0]) or (enc(s.radii[1]) shl 8)
        packedB = enc(s.radii[2]) or (enc(s.radii[3]) shl 8)
        val ox = (s.offsetX * 4f).roundToInt().coerceIn(-1024, 1023) + 1024
        val oy = (s.offsetY * 4f).roundToInt().coerceIn(-1024, 1023) + 1024
        packedOffset = (ox + oy * 2048).toFloat()
        val sigma = (s.blur / 2f * 2f).roundToInt().coerceIn(0, 255)
        val grow = ((if (s.mode == ShadowMode.INSET) -s.spread else s.spread) * 2f).roundToInt().coerceIn(-256, 255) + 256
        packedParams = (1 + sigma + grow * 256 + s.mode.ordinal * 262144).toFloat()
        val x0 = floor(area.x).toInt()
        val y0 = floor(area.y).toInt()
        val raw = ScreenRectangle(x0, y0, ceil(area.right).toInt() - x0, ceil(area.bottom).toInt() - y0).transformMaxBounds(pose)
        bounds = if (scissor != null) scissor.intersection(raw) else raw
    }

    override fun buildVertices(consumer: VertexConsumer) {
        val cx = s.x + s.width / 2f
        val cy = s.y + s.height / 2f
        val hx = (s.width / 2f * 8f).roundToInt()
        val hy = (s.height / 2f * 8f).roundToInt()
        vertex(consumer, area.x, area.y, cx, cy, hx, hy)
        vertex(consumer, area.x, area.bottom, cx, cy, hx, hy)
        vertex(consumer, area.right, area.bottom, cx, cy, hx, hy)
        vertex(consumer, area.right, area.y, cx, cy, hx, hy)
    }

    private fun vertex(c: VertexConsumer, px: Float, py: Float, cx: Float, cy: Float, hx: Int, hy: Int) {
        val p = pose.transformPosition(px, py, Vector2f())
        c.addVertex(p.x, p.y, packedOffset)
            .setColor(s.color)
            .setUv(px - cx, py - cy)
            .setUv1(hx, hy)
            .setUv2(packedA, packedB)
            .setLineWidth(packedParams)
    }

    override fun pipeline(): RenderPipeline = GuiPipelines.BOX_SHADOW
    override fun textureSetup(): TextureSetup = TextureSetup.noTexture()
    override fun scissorArea(): ScreenRectangle? = scissor
    override fun bounds(): ScreenRectangle? = bounds
}
