package net.sbo.guilib.fabric.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import net.sbo.guilib.fabric.font.GlyphAtlas
import org.joml.Matrix3x2f

/** All glyph quads of one text run that live on the same atlas page, drawn with the vanilla textured GUI pipeline. */
class TextRunState(
    private val pose: Matrix3x2f,
    private val page: GlyphAtlas.Page,
    /** x0, y0, x1, y1, u0, v0, u1, v1 per glyph (GUI coordinates). */
    private val quads: FloatArray,
    private val color: Int,
    private val scissor: ScreenRectangle?,
    bounds: ScreenRectangle,
) : GuiElementRenderState {
    private val bounds: ScreenRectangle? = bounds.transformMaxBounds(pose).let { if (scissor != null) scissor.intersection(it) else it }

    override fun buildVertices(consumer: VertexConsumer) {
        var i = 0
        while (i < quads.size) {
            val x0 = quads[i]; val y0 = quads[i + 1]; val x1 = quads[i + 2]; val y1 = quads[i + 3]
            val u0 = quads[i + 4]; val v0 = quads[i + 5]; val u1 = quads[i + 6]; val v1 = quads[i + 7]
            consumer.addVertexWith2DPose(pose, x0, y0).setUv(u0, v0).setColor(color)
            consumer.addVertexWith2DPose(pose, x0, y1).setUv(u0, v1).setColor(color)
            consumer.addVertexWith2DPose(pose, x1, y1).setUv(u1, v1).setColor(color)
            consumer.addVertexWith2DPose(pose, x1, y0).setUv(u1, v0).setColor(color)
            i += 8
        }
    }

    override fun pipeline(): RenderPipeline = RenderPipelines.GUI_TEXTURED
    override fun textureSetup(): TextureSetup =
        TextureSetup.singleTexture(page.texture.textureView, page.texture.sampler)
    override fun scissorArea(): ScreenRectangle? = scissor
    override fun bounds(): ScreenRectangle? = bounds
}
