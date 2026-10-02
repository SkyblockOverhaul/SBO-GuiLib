package net.sbo.guilib.fabric.font

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier

/**
 * Packs rendered glyphs into 1024×1024 textures (shelf packing). Glyph pixels are white with the coverage as alpha,
 * so the vanilla textured GUI pipeline can tint them with the vertex color.
 */
object GlyphAtlas {
    const val SIZE = 1024
    private const val PAD = 1

    class Page(val id: Identifier, val texture: DynamicTexture, val image: NativeImage) {
        var shelfY = 0
        var shelfH = 0
        var cursorX = 0
        var dirty = false
    }

    /** Location of a glyph in the atlas plus its placement relative to the pen (physical pixels). */
    class Glyph(val page: Page?, val u0: Float, val v0: Float, val u1: Float, val v1: Float, val left: Int, val top: Int, val width: Int, val height: Int)

    private data class Key(val font: TrueTypeFont, val px: Int, val codepoint: Int)

    private val pages = ArrayList<Page>()
    /** Changes whenever the atlas is cleared, so caches of glyph positions know they are stale. */
    var generation = 0
        private set
    private val glyphs = HashMap<Key, Glyph>()

    fun glyph(font: TrueTypeFont, px: Int, codepoint: Int): Glyph = glyphs.getOrPut(Key(font, px, codepoint)) {
        val bmp = font.render(codepoint, px)
        if (bmp == null || bmp.width == 0 || bmp.rows == 0) return@getOrPut Glyph(null, 0f, 0f, 0f, 0f, 0, 0, 0, 0)
        val page = allocate(bmp.width + PAD * 2, bmp.rows + PAD * 2)
        val x0 = page.cursorX - bmp.width - PAD
        val y0 = page.shelfY + PAD
        for (y in 0 until bmp.rows) for (x in 0 until bmp.width) {
            val a = bmp.pixels[y * bmp.width + x].toInt() and 0xFF
            page.image.setPixel(x0 + x, y0 + y, (a shl 24) or 0xFFFFFF)
        }
        page.dirty = true
        Glyph(
            page, x0.toFloat() / SIZE, y0.toFloat() / SIZE, (x0 + bmp.width).toFloat() / SIZE, (y0 + bmp.rows).toFloat() / SIZE,
            bmp.left, bmp.top, bmp.width, bmp.rows,
        )
    }

    private fun allocate(w: Int, h: Int): Page {
        var page = pages.lastOrNull()
        if (page != null) {
            if (page.cursorX + w > SIZE) {
                page.shelfY += page.shelfH
                page.shelfH = 0
                page.cursorX = 0
            }
            if (page.shelfY + h > SIZE) page = null
        }
        if (page == null) page = newPage()
        page.cursorX += w
        page.shelfH = maxOf(page.shelfH, h)
        return page
    }

    private fun newPage(): Page {
        val image = NativeImage(SIZE, SIZE, true)
        val id = Identifier.fromNamespaceAndPath("guilib", "font_atlas/${pages.size}")
        val texture = DynamicTexture({ "GuiLib glyph atlas $id" }, image)
        Minecraft.getInstance().textureManager.register(id, texture)
        return Page(id, texture, image).also { pages += it }
    }

    /** Uploads pages that received new glyphs. Call before drawing. */
    fun flush() {
        for (p in pages) if (p.dirty) {
            p.texture.upload()
            p.dirty = false
        }
    }

    /** Frees all glyph textures (e.g. when fonts are reloaded). */
    fun clear() {
        pages.forEach { Minecraft.getInstance().textureManager.release(it.id) }
        pages.clear()
        glyphs.clear()
        generation++
    }
}
