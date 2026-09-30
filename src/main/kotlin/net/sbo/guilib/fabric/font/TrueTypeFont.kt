package net.sbo.guilib.fabric.font

import net.minecraft.client.gui.font.providers.FreeTypeUtil
import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.freetype.FT_Face
import org.lwjgl.util.freetype.FreeType
import java.nio.ByteBuffer

/**
 * A TrueType/OpenType font face loaded with FreeType (the same library Minecraft uses for its TTF fonts).
 * All sizes are in physical pixels. Thread-safe via FreeType's global lock.
 */
class TrueTypeFont(val name: String, data: ByteArray) : AutoCloseable {
    private val buffer: ByteBuffer = MemoryUtil.memAlloc(data.size).put(data).flip()
    private val face: FT_Face
    private var currentPx = -1

    class Metrics(val ascent: Float, val descent: Float, val lineHeight: Float)

    /** A rendered glyph: [pixels] are [width]×[rows] coverage bytes; [left]/[top] are the bearing from the pen position. */
    class Bitmap(val advance: Float, val left: Int, val top: Int, val width: Int, val rows: Int, val pixels: ByteArray)

    private val metricsCache = HashMap<Int, Metrics>()
    private val advanceCache = HashMap<Long, Float>()
    private val glyphIndexCache = HashMap<Int, Int>()

    init {
        synchronized(FreeTypeUtil.LIBRARY_LOCK) {
            MemoryStack.stackPush().use { stack ->
                val ptr: PointerBuffer = stack.mallocPointer(1)
                val err = FreeType.FT_New_Memory_Face(FreeTypeUtil.getLibrary(), buffer, 0, ptr)
                if (err != 0) {
                    MemoryUtil.memFree(buffer)
                    throw IllegalArgumentException("FreeType could not load font '$name' (error $err)")
                }
                face = FT_Face.create(ptr.get(0))
            }
        }
    }

    private fun setSize(px: Int) {
        if (px != currentPx) {
            FreeTypeUtil.assertError(FreeType.FT_Set_Pixel_Sizes(face, 0, px), "Setting font size")
            currentPx = px
        }
    }

    private val loadFlags = FreeType.FT_LOAD_DEFAULT or ((FreeType.FT_RENDER_MODE_LIGHT and 15) shl 16)

    fun glyphIndex(codepoint: Int): Int = glyphIndexCache.getOrPut(codepoint) {
        synchronized(FreeTypeUtil.LIBRARY_LOCK) { FreeType.FT_Get_Char_Index(face, codepoint.toLong()) }
    }

    fun hasGlyph(codepoint: Int) = glyphIndex(codepoint) != 0

    fun metrics(px: Int): Metrics = metricsCache.getOrPut(px) {
        synchronized(FreeTypeUtil.LIBRARY_LOCK) {
            setSize(px)
            val m = face.size()!!.metrics()
            Metrics(m.ascender() / 64f, -m.descender() / 64f, m.height() / 64f)
        }
    }

    /** Horizontal advance of [codepoint] at [px], in pixels. */
    fun advance(codepoint: Int, px: Int): Float = advanceCache.getOrPut((codepoint.toLong() shl 16) or px.toLong()) {
        val index = glyphIndex(codepoint)
        synchronized(FreeTypeUtil.LIBRARY_LOCK) {
            setSize(px)
            if (FreeType.FT_Load_Glyph(face, index, loadFlags) != 0) 0f
            else face.glyph()!!.advance().x() / 64f
        }
    }

    fun render(codepoint: Int, px: Int): Bitmap? {
        val index = glyphIndex(codepoint)
        synchronized(FreeTypeUtil.LIBRARY_LOCK) {
            setSize(px)
            if (FreeType.FT_Load_Glyph(face, index, loadFlags) != 0) return null
            val slot = face.glyph()!!
            if (FreeType.FT_Render_Glyph(slot, FreeType.FT_RENDER_MODE_LIGHT) != 0) return null
            val bmp = slot.bitmap()
            val w = bmp.width()
            val rows = bmp.rows()
            val pitch = bmp.pitch()
            val pixels = ByteArray(w * rows)
            if (w > 0 && rows > 0) {
                val src = bmp.buffer(kotlin.math.abs(pitch) * rows) ?: return null
                for (y in 0 until rows) for (x in 0 until w) pixels[y * w + x] = src.get(y * pitch + x)
            }
            return Bitmap(slot.advance().x() / 64f, slot.bitmap_left(), slot.bitmap_top(), w, rows, pixels)
        }
    }

    override fun close() {
        synchronized(FreeTypeUtil.LIBRARY_LOCK) { FreeType.FT_Done_Face(face) }
        MemoryUtil.memFree(buffer)
    }
}
