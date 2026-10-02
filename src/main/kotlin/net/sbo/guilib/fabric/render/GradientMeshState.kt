package net.sbo.guilib.fabric.render

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
import net.sbo.guilib.core.paint.ColorMesh
import org.joml.Matrix3x2f
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Colored triangles (e.g. a gradient) drawn with [GuiPipelines.ROUNDED_RECT]: the per-vertex colors give the
 * gradient, the SDF attributes of the box ([x], [y], [w], [h], [radii]) clip it to rounded corners with anti-aliasing.
 */
class GradientMeshState(
    private val pose: Matrix3x2f,
    private val mesh: ColorMesh,
    private val x: Float,
    private val y: Float,
    private val w: Float,
    private val h: Float,
    radii: FloatArray,
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
        // Mesh coordinates are relative to the box.
        val cx = w / 2f
        val cy = h / 2f
        val hx = (w / 2f * 8f).roundToInt()
        val hy = (h / 2f * 8f).roundToInt()
        fun v(i: Int) {
            val px = mesh.x[i]
            val py = mesh.y[i]
            consumer.addVertexWith2DPose(pose, x + px, y + py)
                .setColor(mesh.color[i])
                .setUv(px - cx, py - cy)
                .setUv1(hx, hy)
                .setUv2(packedA, packedB)
                .setLineWidth(0f)
        }
        var i = 0
        while (i + 2 < mesh.x.size) {
            // Same winding as vanilla GUI quads (negative signed area in screen space), so culling never drops them.
            val area = (mesh.x[i + 1] - mesh.x[i]) * (mesh.y[i + 2] - mesh.y[i]) - (mesh.x[i + 2] - mesh.x[i]) * (mesh.y[i + 1] - mesh.y[i])
            // The pipeline draws quads; a triangle is sent as a quad with a repeated last vertex.
            if (area <= 0f) {
                v(i); v(i + 1); v(i + 2); v(i + 2)
            } else {
                v(i); v(i + 2); v(i + 1); v(i + 1)
            }
            i += 3
        }
    }

    override fun pipeline(): RenderPipeline = GuiPipelines.ROUNDED_RECT
    override fun textureSetup(): TextureSetup = TextureSetup.noTexture()
    override fun scissorArea(): ScreenRectangle? = scissor
    override fun bounds(): ScreenRectangle? = bounds
}
