package net.sbo.guilib.fabric.render

import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
//#if MC >= 26.3
//$$ import com.mojang.renderpearl.api.GpuFormat
//$$ import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
//$$ import com.mojang.renderpearl.api.pipeline.RenderPipeline
//$$ import com.mojang.renderpearl.api.vertex.VertexFormat
//#elseif MC >= 26.2
//$$ import com.mojang.blaze3d.GpuFormat
//$$ import com.mojang.blaze3d.PrimitiveTopology
//$$ import com.mojang.blaze3d.pipeline.RenderPipeline
//$$ import com.mojang.blaze3d.vertex.VertexFormat
//#else
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexFormatElement
//#endif

/**
 * GuiLib's render pipelines. They extend the vanilla GUI snippets, so they batch, sort and clip like vanilla GUI
 * elements and work on every graphics backend.
 */
object GuiPipelines {
    private fun id(path: String) = Identifier.fromNamespaceAndPath("guilib", path)

    /**
     * Rounded rectangle: Position, Color, UV0 = position relative to the rect center, UV1 = half size × 8,
     * UV2 = four corner radii × 2 packed as bytes, LineWidth = border width (≥ 0: fill inside the border, < 0: border ring).
     */
    val ROUNDED_FORMAT: VertexFormat =
        //#if MC >= 26.2
        //$$ VertexFormat.builder(0)
        //$$     .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        //$$     .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        //$$     .addAttribute("UV0", GpuFormat.RG32_FLOAT)
        //$$     .addAttribute("UV1", GpuFormat.RG16_SINT)
        //$$     .addAttribute("UV2", GpuFormat.RG16_SINT)
        //$$     .addAttribute("LineWidth", GpuFormat.R32_FLOAT)
        //$$     .build()
        //#else
        VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV1", VertexFormatElement.UV1)
            .add("UV2", VertexFormatElement.UV2)
            .add("LineWidth", VertexFormatElement.LINE_WIDTH)
            .build()
        //#endif

    val ROUNDED_RECT: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(id("pipeline/rounded_rect"))
            .withVertexShader(id("core/rounded_rect"))
            .withFragmentShader(id("core/rounded_rect"))
            .quads(ROUNDED_FORMAT)
            .build(),
    )

    /** `box-shadow`: the rounded-rect format with the shadow parameters packed in (see [ShadowState]). */
    val BOX_SHADOW: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(id("pipeline/box_shadow"))
            .withVertexShader(id("core/box_shadow"))
            .withFragmentShader(id("core/box_shadow"))
            .quads(ROUNDED_FORMAT)
            .build(),
    )

    private fun RenderPipeline.Builder.quads(format: VertexFormat): RenderPipeline.Builder =
        //#if MC >= 26.2
        //$$ withVertexBinding(0, format).withPrimitiveTopology(PrimitiveTopology.QUADS)
        //#else
        withVertexFormat(format, VertexFormat.Mode.QUADS)
        //#endif

    /** Forces class initialisation so the pipelines are registered before resources load. */
    fun init() {}
}
