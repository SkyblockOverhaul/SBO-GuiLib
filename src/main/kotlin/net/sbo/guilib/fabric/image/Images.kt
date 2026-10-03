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
import net.sbo.guilib.core.paint.ImageFilters
import net.sbo.guilib.core.paint.ImageOp
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.math.ceil

/**
 * Resolves `<img src>` / `background-image: url()` sources to textures.
 * - `modid:path/file.png` → the resource's texture (loaded by Minecraft's TextureManager)
 * - `modid:path/file.svg` → rasterized with JSVG at the exact on-screen pixel size (cached per size); `currentColor`
 *   in the SVG is the element's CSS `color` (see [SvgColor])
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
        /** SVG source, kept only when it uses `currentColor` (it is parsed again for each color). */
        val svgSource: String? = null,
    ) : ReplacedContent

    /**
     * A GIF decoded on a background thread (decoding big GIFs takes long enough to freeze the game). Until it is ready
     * nothing is drawn, like a loading image in a browser. Each frame is uploaded to the GPU when it is first shown,
     * so the uploads are spread over the first play. All `<img>`s showing the same GIF play in sync from the moment it
     * was ready (like browsers do for one image resource).
     */
    class AnimatedGif internal constructor(private val src: String, private val pending: CompletableFuture<Frames>) {
        /** Decoded frames as native images, built off-thread; an entry becomes `null` once uploaded. */
        internal class Frames(val timing: GifDecoder.Timing, val width: Int, val height: Int, val images: Array<NativeImage?>)

        private var frames: Frames? = null
        private var textures: Array<Texture?> = emptyArray()
        private var start = -1L
        private var failed = false
        private var disposed = false
        private val serial = gifCounter++

        fun frame(): Texture? {
            if (disposed || failed) return null
            val f = frames ?: run {
                if (!pending.isDone) return null
                try {
                    pending.join().also { frames = it; textures = arrayOfNulls(it.images.size) }
                } catch (e: Exception) {
                    failed = true
                    Log.warnOnce("GuiLib: failed to load image '$src': ${e.cause ?: e}")
                    return null
                }
            }
            if (start < 0) start = System.nanoTime()
            val i = f.timing.frameAt((System.nanoTime() - start) / 1_000_000)
            textures[i]?.let { return it }
            val native = f.images[i] ?: return null
            f.images[i] = null // the texture owns the pixels from now on
            val id = Identifier.fromNamespaceAndPath("guilib", "dynamic/gif_${serial}_$i")
            Minecraft.getInstance().textureManager.register(id, DynamicTexture({ "GuiLib GIF $id" }, native))
            return Texture(id, f.width, f.height).also { textures[i] = it }
        }

        /** Releases the uploaded textures and frees frames that were never shown (also if decoding finishes later). */
        internal fun dispose() {
            disposed = true
            textures.forEach { it?.let { t -> Minecraft.getInstance().textureManager.release(t.id) } }
            textures = emptyArray()
            pending.thenAccept { f -> f.images.forEachIndexed { i, img -> img?.close(); f.images[i] = null } }
        }
    }

    /** A drawable texture region: the texture and its size in texels, including [pad] transparent texels per side. */
    class Texture(val id: Identifier, val width: Int, val height: Int, val pad: Int = 0)

    /** Decodes GIFs (and warms up SVG support) off the render thread; one daemon thread is enough. */
    private val decoder: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GuiLib image worker").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /**
     * Parses and draws a tiny SVG on the worker thread. The first SVG otherwise froze the render thread for ~110 ms:
     * JSVG's class loading (~80 ms for the first parse) and Java2D's setup (~30 ms for the first rasterization) are
     * one-time costs, so paying them in the background at startup leaves only the real work for the render thread.
     */
    fun warmUp() {
        decoder.execute {
            try {
                val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
                    <defs><linearGradient id="g"><stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="#000"/></linearGradient></defs>
                    <rect x="1" y="1" width="14" height="14" rx="3" fill="url(#g)" stroke="#888" stroke-width="1"/>
                    <path d="M4 8 L7 11 L12 5" fill="none" stroke="#000" stroke-width="2" stroke-linecap="round"/>
                    <circle cx="8" cy="8" r="2" opacity="0.5"/></svg>"""
                val doc = SVGLoader().load(ByteArrayInputStream(svg.toByteArray()), null, LoaderContext.createDefault()) ?: return@execute
                val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
                val g = img.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                doc.render(null, g, ViewBox(0f, 0f, 16f, 16f))
                g.dispose()
            } catch (e: Throwable) {
                Log.warn("GuiLib: SVG warm-up failed: $e") // harmless, the first SVG just loads slower
            }
        }
    }

    private val entries = HashMap<String, Entry?>()
    private val svgTextures = LinkedHashMap<String, Texture>()
    private val coloredSvgs = LinkedHashMap<String, SVGDocument>()
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
                val source = resource.get().open().use { it.readAllBytes() }.toString(Charsets.UTF_8)
                val doc = parseSvg(source) ?: throw IllegalArgumentException("not a valid SVG")
                val size = doc.size()
                Entry(src, size.width, size.height, doc, null, svgSource = source.takeIf { SvgColor.usesCurrentColor(it) })
            } else if (id.path.endsWith(".gif", ignoreCase = true)) {
                // Only the header is read here (the natural size for layout); the frames are decoded in the background.
                val bytes = resource.get().open().use { it.readAllBytes() }
                val decode = { decodeFrames(src, bytes) }
                val size = GifDecoder.screenSize(bytes)
                if (size == null) {
                    val frames = decode() // no size in the header: decode now to know it
                    Entry(src, frames.width.toFloat(), frames.height.toFloat(), null, null, AnimatedGif(src, CompletableFuture.completedFuture(frames)))
                } else {
                    Entry(src, size.first.toFloat(), size.second.toFloat(), null, null, AnimatedGif(src, CompletableFuture.supplyAsync(decode, decoder)))
                }
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

    /** Decodes a GIF and copies its frames into native images (no GPU work, so it can run on any thread). */
    private fun decodeFrames(src: String, bytes: ByteArray): AnimatedGif.Frames {
        val gif = GifDecoder.decode(ByteArrayInputStream(bytes)) { kept ->
            Log.warnOnce("GuiLib: GIF '$src' is too large, only its first $kept frames are shown")
        }
        val w = gif.width
        val h = gif.height
        val images = arrayOfNulls<NativeImage>(gif.frames.size)
        try {
            gif.frames.forEachIndexed { i, f ->
                val native = NativeImage(w, h, false)
                images[i] = native
                for (y in 0 until h) for (x in 0 until w) native.setPixel(x, y, f.argb[y * w + x])
            }
        } catch (e: Throwable) {
            images.forEach { it?.close() }
            throw e
        }
        return AnimatedGif.Frames(gif.timing, w, h, images)
    }

    private fun pngSize(input: DataInputStream): Pair<Int, Int> {
        val sig = ByteArray(8)
        input.readFully(sig)
        require(sig[1] == 'P'.code.toByte() && sig[2] == 'N'.code.toByte() && sig[3] == 'G'.code.toByte()) { "only PNG, SVG and GIF images are supported" }
        input.readInt() // IHDR length
        input.readInt() // "IHDR"
        return input.readInt() to input.readInt()
    }

    private fun parseSvg(source: String): SVGDocument? =
        SVGLoader().load(ByteArrayInputStream(source.toByteArray()), null, LoaderContext.createDefault())

    /** The document of [entry] with `currentColor` = [color] (only for SVGs that use it). Cached. */
    private fun coloredSvg(entry: Entry, source: String, color: Int): SVGDocument? {
        val key = "${entry.src}#${Integer.toHexString(color)}"
        coloredSvgs[key]?.let { return it }
        val doc = try {
            parseSvg(SvgColor.withCurrentColor(source, color))
        } catch (e: Exception) {
            Log.warnOnce("GuiLib: failed to load image '${entry.src}': $e"); null
        } ?: return null
        coloredSvgs[key] = doc
        if (coloredSvgs.size > 64) coloredSvgs.remove(coloredSvgs.keys.first())
        return doc
    }

    /** Texture to draw [entry] at [pixelWidth]×[pixelHeight] physical pixels; [color] is `currentColor` for SVGs. */
    fun texture(entry: Entry, pixelWidth: Int, pixelHeight: Int, color: Int): Texture? {
        entry.pngId?.let { return Texture(it, entry.width.toInt(), entry.height.toInt()) }
        entry.gif?.let { return it.frame() }
        val plain = entry.svg ?: return null
        val w = pixelWidth.coerceIn(1, 4096)
        val h = pixelHeight.coerceIn(1, 4096)
        val source = entry.svgSource
        val key = if (source == null) "${entry.src}@${w}x$h" else "${entry.src}#${Integer.toHexString(color)}@${w}x$h"
        svgTextures[key]?.let { return it }
        val svg = if (source == null) plain else coloredSvg(entry, source, color) ?: return null
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
        upload(rasterizeArgb(svg, w, h), w, h, "svg_${svgCounter++}", "GuiLib SVG")
    } catch (e: Exception) {
        Log.warnOnce("GuiLib: failed to rasterize SVG: $e")
        null
    }

    private fun rasterizeArgb(svg: SVGDocument, w: Int, h: Int): IntArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        svg.render(null, g, ViewBox(0f, 0f, w.toFloat(), h.toFloat()))
        g.dispose()
        return (img.raster.dataBuffer as DataBufferInt).data // TYPE_INT_ARGB: one int per pixel, row by row
    }

    private fun upload(argb: IntArray, w: Int, h: Int, name: String, label: String, pad: Int = 0): Texture {
        val native = NativeImage(w, h, false)
        for (y in 0 until h) for (x in 0 until w) native.setPixel(x, y, argb[y * w + x])
        val id = Identifier.fromNamespaceAndPath("guilib", "dynamic/$name")
        Minecraft.getInstance().textureManager.register(id, DynamicTexture({ "$label $id" }, native))
        return Texture(id, w, h, pad)
    }

    // ---- filter ------------------------------------------------------------------------------------------------

    private data class FilterKey(val src: String, val w: Int, val h: Int, val color: Int, val ops: List<ImageOp>, val pxPerGui: Float)

    private class Pixels(val argb: IntArray, val width: Int, val height: Int)

    private val filteredTextures = LinkedHashMap<FilterKey, Texture>()
    private val pngPixels = LinkedHashMap<String, Pixels>()
    private var filterCounter = 0

    /**
     * [entry] with CSS `filter` operations applied to its pixels, drawn at [pixelWidth]x[pixelHeight] physical pixels.
     * Color-only filters on PNGs keep the natural size (pixel art stays crisp); blurs and silhouettes work at the
     * on-screen size (PNGs are scaled up nearest-neighbour first). Animated GIFs aren't filtered (`null`).
     */
    fun filtered(entry: Entry, pixelWidth: Int, pixelHeight: Int, color: Int, ops: List<ImageOp>): Texture? {
        if (entry.gif != null) return null
        val natural = entry.pngId != null && !ImageFilters.needsResolution(ops)
        val w = if (natural) entry.width.toInt() else pixelWidth.coerceIn(1, 2048)
        val h = if (natural) entry.height.toInt() else pixelHeight.coerceIn(1, 2048)
        val pxPerGui = guiScale() * w / pixelWidth.coerceAtLeast(1)
        val key = FilterKey(entry.src, w, h, if (entry.svgSource != null) color else 0, ops, pxPerGui)
        filteredTextures[key]?.let { return it }
        val src = try {
            val pngId = entry.pngId
            if (pngId != null) {
                val png = pngPixels(pngId) ?: return null
                if (png.width == w && png.height == h) png.argb else IntArray(w * h) { i ->
                    png.argb[(i / w * png.height / h) * png.width + (i % w * png.width / w)]
                }
            } else {
                val plain = entry.svg ?: return null
                val source = entry.svgSource
                rasterizeArgb(if (source == null) plain else coloredSvg(entry, source, color) ?: return null, w, h)
            }
        } catch (e: Exception) {
            Log.warnOnce("GuiLib: failed to filter image '${entry.src}': $e")
            return null
        }
        val result = ImageFilters.apply(src, w, h, ops, pxPerGui)
        val tex = upload(result.argb, result.width, result.height, "filtered_${filterCounter++}", "GuiLib filtered image", result.pad)
        filteredTextures[key] = tex
        while (filteredTextures.size > 64) {
            val oldest = filteredTextures.keys.first()
            filteredTextures.remove(oldest)?.let { Minecraft.getInstance().textureManager.release(it.id) }
        }
        return tex
    }

    /** Decoded PNG pixels (a few, for filters whose values animate). */
    private fun pngPixels(id: Identifier): Pixels? {
        pngPixels[id.toString()]?.let { return it }
        val resource = Minecraft.getInstance().resourceManager.getResource(id)
        if (resource.isEmpty) return null
        val img = resource.get().open().use { ImageIO.read(it) } ?: return null
        val px = Pixels(img.getRGB(0, 0, img.width, img.height, null, 0, img.width), img.width, img.height)
        pngPixels[id.toString()] = px
        if (pngPixels.size > 8) pngPixels.remove(pngPixels.keys.first())
        return px
    }

    fun guiScale(): Float = net.sbo.guilib.fabric.font.FontManager.guiScale()

    fun physical(gui: Float) = ceil(gui * guiScale()).toInt()

    /** Drops cached metadata (e.g. after a resource reload). */
    fun clear() {
        entries.values.forEach { e -> e?.gif?.dispose() }
        entries.clear()
        svgTextures.values.forEach { Minecraft.getInstance().textureManager.release(it.id) }
        svgTextures.clear()
        coloredSvgs.clear()
        filteredTextures.values.forEach { Minecraft.getInstance().textureManager.release(it.id) }
        filteredTextures.clear()
        pngPixels.clear()
    }
}
