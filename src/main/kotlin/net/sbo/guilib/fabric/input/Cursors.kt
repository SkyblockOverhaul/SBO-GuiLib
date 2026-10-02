package net.sbo.guilib.fabric.input

import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.view.ViewBox
import com.mojang.blaze3d.platform.cursor.CursorType
import com.mojang.blaze3d.platform.cursor.CursorTypes
import net.minecraft.client.Minecraft
import net.sbo.guilib.core.Log
import net.sbo.guilib.core.css.Cursor
//#if MC >= 26.3
//$$ import org.lwjgl.sdl.SDLMouse
//$$ import org.lwjgl.sdl.SDLPixels
//$$ import org.lwjgl.sdl.SDLSurface
//$$ import org.lwjgl.sdl.SDLVideo
//#else
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWImage
import org.lwjgl.system.MemoryStack
//#endif
import org.lwjgl.system.MemoryUtil
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * Maps CSS cursors to Minecraft cursor types. Minecraft only has a handful of standard cursors, so the hand cursors
 * (`grab`, `grabbing`) are created from bundled SVGs (`assets/guilib/cursors/`), sized for the window's content scale.
 * `none` is a fully transparent cursor (hiding the cursor through GLFW/SDL would fight Minecraft's mouse handling).
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
        Cursor.NONE -> custom.getOrPut("none") { createInvisible() ?: CursorTypes.ARROW }
        Cursor.AUTO, Cursor.DEFAULT -> null
    }

    private fun custom(name: String): CursorType =
        custom.getOrPut(name) { create(name) ?: CursorTypes.RESIZE_ALL }

    /** Rasterizes `assets/guilib/cursors/<name>.svg` (a 32×32 design, hotspot in the middle) into a GLFW cursor. */
    private fun create(name: String): CursorType? = try {
        val stream = Cursors::class.java.getResourceAsStream("/assets/guilib/cursors/$name.svg")
            ?: error("missing cursor image")
        val svg = stream.use { SVGLoader().load(it, null, LoaderContext.createDefault()) } ?: error("invalid SVG")
        val scale = contentScale().coerceIn(1f, 4f)
        val size = (32 * scale).roundToInt()
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        svg.render(null, g, ViewBox(0f, 0f, size.toFloat(), size.toFloat()))
        g.dispose()
        cursorFrom(name, img)
    } catch (e: Exception) {
        Log.warnOnce("GuiLib: could not create the '$name' cursor, using the move cursor instead: $e")
        null
    }

    /** `cursor: none`: a 1×1 transparent cursor. */
    private fun createInvisible(): CursorType? = try {
        cursorFrom("none", BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB))
    } catch (e: Exception) {
        Log.warnOnce("GuiLib: could not create an invisible cursor for 'cursor: none': $e")
        null
    }

    /** The window's content scale (1 on a normal display, 2 on a 200 % display). */
    private fun contentScale(): Float {
        val window = Minecraft.getInstance().window.handle()
        //#if MC >= 26.3
        //$$ return SDLVideo.SDL_GetWindowDisplayScale(window).takeIf { it > 0f } ?: 1f
        //#else
        return MemoryStack.stackPush().use { stack ->
            val xs = stack.mallocFloat(1)
            val ys = stack.mallocFloat(1)
            GLFW.glfwGetWindowContentScale(window, xs, ys)
            xs.get(0)
        }
        //#endif
    }

    /** Creates a cursor from [img] with the hotspot in its middle (GLFW up to 26.2, SDL from 26.3 on). */
    private fun cursorFrom(name: String, img: BufferedImage): CursorType {
        val (w, h) = img.width to img.height
        val pixels = MemoryUtil.memAlloc(w * h * 4)
        try {
            for (y in 0 until h) for (x in 0 until w) {
                val argb = img.getRGB(x, y)
                pixels.put((argb shr 16).toByte()).put((argb shr 8).toByte()).put(argb.toByte()).put((argb ushr 24).toByte())
            }
            pixels.flip()
            //#if MC >= 26.3
            //$$ // Bytes R, G, B, A = SDL_PIXELFORMAT_RGBA32, which is ABGR8888 on little-endian machines.
            //$$ val format = if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN)
            //$$     SDLPixels.SDL_PIXELFORMAT_ABGR8888 else SDLPixels.SDL_PIXELFORMAT_RGBA8888
            //$$ val surface = SDLSurface.SDL_CreateSurfaceFrom(w, h, format, pixels, w * 4) ?: error("SDL_CreateSurfaceFrom failed")
            //$$ val handle = try {
            //$$     SDLMouse.SDL_CreateColorCursor(surface, w / 2, h / 2) // copies the pixels
            //$$ } finally {
            //$$     SDLSurface.SDL_DestroySurface(surface)
            //$$ }
            //$$ if (handle == 0L) error("SDL_CreateColorCursor failed")
            //#else
            val handle = GLFWImage.malloc().use { image ->
                image.set(w, h, pixels)
                GLFW.glfwCreateCursor(image, w / 2, h / 2)
            }
            if (handle == 0L) error("glfwCreateCursor failed")
            //#endif
            return CursorType("guilib_$name", handle)
        } finally {
            MemoryUtil.memFree(pixels)
        }
    }
}
