package net.sbo.guilib.fabric.image

import com.github.weisj.jsvg.SVGDocument
import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.view.ViewBox
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.sbo.guilib.core.Log
import net.sbo.guilib.core.dom.ReplacedContent
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.DataInputStream
import kotlin.math.ceil

/**
 * Resolves `<img src>` / `background-image: url()` sources to textures.
 * - `modid:path/file.png` → the resource's texture (loaded by Minecraft's TextureManager)
 * - `modid:path/file.svg` → rasterized with JSVG at the exact on-screen pixel size (cached per size)
 * - `modid:path/file.gif` → decoded into composited frames (one texture each); animated GIFs play on their own clock
 */
object Images {
    class Entry(
        val src: String,
        override val width: Float,
        override val height: Float,
        val svg: SVGDocument?,
        val pngId: Identifier?,
        val gif: AnimatedGif? = null,
    ) : ReplacedContent

    /**
     * A decoded GIF. Frames are uploaded on first draw; all `<img>`s showing the same GIF play in sync from the moment
     * it was first loaded (like browsers do for one image resource).
     */
    class AnimatedGif internal constructor(private var decoded: GifDecoder.Gif?) {
        private val timing = decoded!!.timing
        private val start = System.nanoTime()
        internal var textures: List<Texture>? = null

        fun frame(): Texture? {
            val tex = textures ?: upload() ?: return null
            return tex[timing.frameAt((System.nanoTime() - start) / 1_000_000)]
        }

        private fun upload(): List<Texture>? {
            val d = decoded ?: return null
            decoded = null // the pixels live in the textures from now on
            val serial = gifCounter++
            return d.frames.mapIndexed { i, f ->
                val native = NativeImage(d.width, d.height, false)
                for (y in 0 until d.height) for (x in 0 until d.width) native.setPixel(x, y, f.argb[y * d.width + x])
                val id = Identifier.fromNamespaceAndPath("guilib", "dynamic/gif_${serial}_$i")
                Minecraft.getInstance().textureManager.register(id, DynamicTexture({ "GuiLib GIF $id" }, native))
                Texture(id, d.width, d.height)
            }.also { textures = it }
        }
    }

    /** A drawable texture region: the texture and its size in texels. */
    class Texture(val id: Identifier, val width: Int, val height: Int)

    private val entries = HashMap<String, Entry?>()
    private val svgTextures = LinkedHashMap<String, Texture>()
    private var svgCounter = 0
    private var gifCounter = 0

    /** Metadata of [src] (natural size), or `null` if it can't be loaded. Cached. */
    fun entry(src: String): Entry? = entries.getOrPut(src) { load(src) }

    private fun load(src: String): Entry? {
        val id = Identifier.tryParse(src) ?: run {
            Log.warnOnce("GuiLib: invalid image src '$src' (expected 'modid:path/file.png', '.svg' or '.gif')")
            return null
        }
        val resource = Minecraft.getInstance().resourceManager.getResource(id)
        if (resource.isEmpty) {
            Log.warnOnce("GuiLib: image '$src' not found (looked for assets/${id.namespace}/${id.path})")
            return null
        }
        return try {
            if (id.path.endsWith(".svg", ignoreCase = true)) {
                val doc = resource.get().open().use { SVGLoader().load(it, null, LoaderContext.createDefault()) }
                    ?: throw IllegalArgumentException("not a valid SVG")
                val size = doc.size()
                Entry(src, size.width, size.height, doc, null)
            } else if (id.path.endsWith(".gif", ignoreCase = true)) {
                val gif = resource.get().open().use { input ->
                    GifDecoder.decode(input) { kept -> Log.warnOnce("GuiLib: GIF '$src' is too large, only its first $kept frames are shown") }
                }
                Entry(src, gif.width.toFloat(), gif.height.toFloat(), null, null, AnimatedGif(gif))
            } else {
                // Read the PNG header for the natural size; the texture itself is loaded lazily by the TextureManager.
                val (w, h) = resource.get().open().use { pngSize(DataInputStream(it)) }
                Entry(src, w.toFloat(), h.toFloat(), null, id)
            }
        } catch (e: Exception) {
            Log.warnOnce("GuiLib: failed to load image '$src': $e")
            null
        }
    }

    private fun pngSize(input: DataInputStream): Pair<Int, Int> {
        val sig = ByteArray(8)
        input.readFully(sig)
        require(sig[1] == 'P'.code.toByte() && sig[2] == 'N'.code.toByte() && sig[3] == 'G'.code.toByte()) { "only PNG, SVG and GIF images are supported" }
        input.readInt() // IHDR length
        input.readInt() // "IHDR"
        return input.readInt() to input.readInt()
    }

    /** Texture to draw [entry] at [pixelWidth]×[pixelHeight] physical pixels. */
    fun texture(entry: Entry, pixelWidth: Int, pixelHeight: Int): Texture? {
        entry.pngId?.let { return Texture(it, entry.width.toInt(), entry.height.toInt()) }
        entry.gif?.let { return it.frame() }
        val svg = entry.svg ?: return null
        val w = pixelWidth.coerceIn(1, 4096)
        val h = pixelHeight.coerceIn(1, 4096)
        val key = "${entry.src}@${w}x$h"
        svgTextures[key]?.let { return it }
        val tex = rasterize(svg, w, h) ?: return null
        svgTextures[key] = tex
        // Keep the cache bounded; release the oldest textures.
        while (svgTextures.size > 128) {
            val oldest = svgTextures.keys.first()
            svgTextures.remove(oldest)?.let { Minecraft.getInstance().textureManager.release(it.id) }
        }
        return tex
    }

    private fun rasterize(svg: SVGDocument, w: Int, h: Int): Texture? = try {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        svg.render(null, g, ViewBox(0f, 0f, w.toFloat(), h.toFloat()))
        g.dispose()
        val native = NativeImage(w, h, false)
        for (y in 0 until h) for (x in 0 until w) native.setPixel(x, y, img.getRGB(x, y))
        val id = Identifier.fromNamespaceAndPath("guilib", "dynamic/svg_${svgCounter++}")
        val texture = DynamicTexture({ "GuiLib SVG $id" }, native)
        Minecraft.getInstance().textureManager.register(id, texture)
        Texture(id, w, h)
    } catch (e: Exception) {
        Log.warnOnce("GuiLib: failed to rasterize SVG: $e")
        null
    }

    fun guiScale(): Float = Minecraft.getInstance().window.guiScale.toFloat()

    fun physical(gui: Float) = ceil(gui * guiScale()).toInt()

    /** Drops cached metadata (e.g. after a resource reload). */
    fun clear() {
        entries.values.forEach { e -> e?.gif?.textures?.forEach { Minecraft.getInstance().textureManager.release(it.id) } }
        entries.clear()
        svgTextures.values.forEach { Minecraft.getInstance().textureManager.release(it.id) }
        svgTextures.clear()
    }
}
