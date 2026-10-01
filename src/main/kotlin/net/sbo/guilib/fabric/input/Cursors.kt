package net.sbo.guilib.fabric.input

import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.view.ViewBox
import com.mojang.blaze3d.platform.cursor.CursorType
import com.mojang.blaze3d.platform.cursor.CursorTypes
import net.minecraft.client.Minecraft
import net.sbo.guilib.core.Log
import net.sbo.guilib.core.css.Cursor
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWImage
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * Maps CSS cursors to Minecraft cursor types. GLFW only has a handful of standard cursors, so the hand cursors
 * (`grab`, `grabbing`) are created from bundled SVGs (`assets/guilib/cursors/`), sized for the window's content scale.
 */
internal object Cursors {
    private val custom = HashMap<String, CursorType>()

    fun of(cursor: Cursor): CursorType? = when (cursor) {
        Cursor.POINTER -> CursorTypes.POINTING_HAND
        Cursor.TEXT -> CursorTypes.IBEAM
        Cursor.NOT_ALLOWED -> CursorTypes.NOT_ALLOWED
        Cursor.CROSSHAIR -> CursorTypes.CROSSHAIR
        Cursor.MOVE -> CursorTypes.RESIZE_ALL
        Cursor.NS_RESIZE, Cursor.ROW_RESIZE -> CursorTypes.RESIZE_NS
        Cursor.EW_RESIZE, Cursor.COL_RESIZE -> CursorTypes.RESIZE_EW
        Cursor.GRAB -> custom("grab")
        Cursor.GRABBING -> custom("grabbing")
        Cursor.AUTO, Cursor.DEFAULT -> null
    }

    private fun custom(name: String): CursorType =
        custom.getOrPut(name) { create(name) ?: CursorTypes.RESIZE_ALL }

    /** Rasterizes `assets/guilib/cursors/<name>.svg` (a 32×32 design, hotspot in the middle) into a GLFW cursor. */
    private fun create(name: String): CursorType? = try {
        val stream = Cursors::class.java.getResourceAsStream("/assets/guilib/cursors/$name.svg")
            ?: error("missing cursor image")
        val svg = stream.use { SVGLoader().load(it, null, LoaderContext.createDefault()) } ?: error("invalid SVG")
        val window = Minecraft.getInstance().window.handle()
        val scale = MemoryStack.stackPush().use { stack ->
            val xs = stack.mallocFloat(1)
            val ys = stack.mallocFloat(1)
            GLFW.glfwGetWindowContentScale(window, xs, ys)
            xs.get(0).coerceIn(1f, 4f)
        }
        val size = (32 * scale).roundToInt()
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        svg.render(null, g, ViewBox(0f, 0f, size.toFloat(), size.toFloat()))
        g.dispose()
        val pixels = MemoryUtil.memAlloc(size * size * 4)
        try {
            for (y in 0 until size) for (x in 0 until size) {
                val argb = img.getRGB(x, y)
                pixels.put((argb shr 16).toByte()).put((argb shr 8).toByte()).put(argb.toByte()).put((argb ushr 24).toByte())
            }
            pixels.flip()
            val handle = GLFWImage.malloc().use { image ->
                image.set(size, size, pixels)
                GLFW.glfwCreateCursor(image, size / 2, size / 2)
            }
            if (handle == 0L) error("glfwCreateCursor failed")
            CursorType("guilib_$name", handle)
        } finally {
            MemoryUtil.memFree(pixels)
        }
    } catch (e: Exception) {
        Log.warnOnce("GuiLib: could not create the '$name' cursor, using the move cursor instead: $e")
        null
    }
}
