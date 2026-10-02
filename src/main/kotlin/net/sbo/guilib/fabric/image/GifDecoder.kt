package net.sbo.guilib.fabric.image

import org.w3c.dom.Node
import java.io.InputStream
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode

/**
 * Decodes (animated) GIFs into fully composited frames, like a browser shows them: frame offsets, transparency and
 * the disposal methods are applied, so every frame is a complete `width`×`height` ARGB image.
 * Uses only the JDK's ImageIO reader (no Minecraft classes), so it can be unit-tested.
 */
internal object GifDecoder {
    class Frame(val argb: IntArray, val delayMs: Int)

    /**
     * When which frame shows. [plays] = how often the animation runs: `0` = forever (NETSCAPE loop count 0), otherwise
     * loop count + 1; a GIF without loop extension plays once.
     */
    class Timing(private val delaysMs: IntArray, val plays: Int) {
        val durationMs: Int = delaysMs.sum()

        /** Index of the frame shown [elapsedMs] after the animation started. */
        fun frameAt(elapsedMs: Long): Int {
            if (delaysMs.size <= 1 || durationMs <= 0) return 0
            if (plays > 0 && elapsedMs >= plays.toLong() * durationMs) return delaysMs.size - 1
            var t = (elapsedMs.coerceAtLeast(0) % durationMs).toInt()
            for ((i, d) in delaysMs.withIndex()) {
                if (t < d) return i
                t -= d
            }
            return delaysMs.size - 1
        }
    }

    class Gif(val width: Int, val height: Int, val frames: List<Frame>, plays: Int) {
        val timing = Timing(frames.map { it.delayMs }.toIntArray(), plays)
        val plays get() = timing.plays
        val durationMs get() = timing.durationMs
        fun frameAt(elapsedMs: Long) = timing.frameAt(elapsedMs)
    }

    /** Browsers show frames with a delay of 10 ms or less for 100 ms (many GIFs rely on that). */
    private const val MIN_DELAY_MS = 20
    private const val DEFAULT_DELAY_MS = 100

    /** Upper bound for all frames together (pixels); longer animations are cut off to keep memory in check. */
    const val MAX_TOTAL_PIXELS = 32L * 1024 * 1024

    /**
     * The logical screen size from the GIF header (bytes 6-9, little endian), without decoding anything, or `null` if
     * [bytes] isn't a GIF or the header has no size (then only [decode] knows it).
     */
    fun screenSize(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 10 || bytes[0] != 'G'.code.toByte() || bytes[1] != 'I'.code.toByte() || bytes[2] != 'F'.code.toByte()) return null
        fun u16(i: Int) = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
        val w = u16(6)
        val h = u16(8)
        return if (w > 0 && h > 0) w to h else null
    }

    fun decode(input: InputStream, maxTotalPixels: Long = MAX_TOTAL_PIXELS, onTruncated: (Int) -> Unit = {}): Gif {
        val stream = ImageIO.createImageInputStream(input) ?: throw IllegalArgumentException("cannot read GIF")
        stream.use {
            val reader = ImageIO.getImageReadersByFormatName("gif").asSequence().firstOrNull()
                ?: throw IllegalStateException("no GIF reader available")
            try {
                reader.input = stream
                val screen = reader.streamMetadata?.getAsTree("javax_imageio_gif_stream_1.0")?.child("LogicalScreenDescriptor")
                val count = reader.getNumImages(true)
                require(count > 0) { "GIF has no frames" }
                var width = screen?.int("logicalScreenWidth") ?: 0
                var height = screen?.int("logicalScreenHeight") ?: 0
                if (width <= 0 || height <= 0) {
                    width = reader.getWidth(0); height = reader.getHeight(0)
                }
                require(width in 1..4096 && height in 1..4096) { "GIF too large (${width}x$height)" }

                val canvas = IntArray(width * height)
                val frames = ArrayList<Frame>()
                var plays = 1
                for (i in 0 until count) {
                    if ((frames.size + 1L) * width * height > maxTotalPixels) {
                        onTruncated(frames.size)
                        break
                    }
                    val meta = reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0")
                    val desc = meta.child("ImageDescriptor")
                    val gce = meta.child("GraphicControlExtension")
                    if (i == 0) plays = loopPlays(meta)
                    val img = reader.read(i)
                    val left = desc?.int("imageLeftPosition") ?: 0
                    val top = desc?.int("imageTopPosition") ?: 0
                    val disposal = gce?.getAttribute("disposalMethod") ?: "none"
                    val previous = if (disposal == "restoreToPrevious") canvas.copyOf() else null

                    val fw = img.width
                    val fh = img.height
                    val row = IntArray(fw)
                    for (y in 0 until fh) {
                        val cy = top + y
                        if (cy !in 0 until height) continue
                        img.getRGB(0, y, fw, 1, row, 0, fw)
                        for (x in 0 until fw) {
                            val cx = left + x
                            if (cx !in 0 until width) continue
                            val c = row[x]
                            if (c ushr 24 != 0) canvas[cy * width + cx] = c // transparent pixels keep what's below
                        }
                    }
                    val delay = (gce?.int("delayTime") ?: 0) * 10
                    frames += Frame(canvas.copyOf(), if (delay < MIN_DELAY_MS) DEFAULT_DELAY_MS else delay)

                    when (disposal) {
                        // Browsers clear to transparent rather than to the background color.
                        "restoreToBackgroundColor" -> for (y in maxOf(top, 0) until minOf(top + fh, height)) {
                            canvas.fill(0, y * width + maxOf(left, 0), y * width + minOf(left + fw, width))
                        }
                        "restoreToPrevious" -> previous!!.copyInto(canvas)
                    }
                }
                return Gif(width, height, frames, plays)
            } finally {
                reader.dispose()
            }
        }
    }

    /** Reads the NETSCAPE2.0 loop count: 0 = forever, n = n repetitions after the first play. */
    private fun loopPlays(meta: Node): Int {
        val apps = meta.child("ApplicationExtensions") ?: return 1
        for (app in apps.children()) {
            val id = app.getAttribute("applicationID")
            if (id != "NETSCAPE" && id != "ANIMEXTS") continue
            val data = app.userObject as? ByteArray ?: continue
            if (data.size < 3 || data[0].toInt() != 1) continue
            val loops = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
            return if (loops == 0) 0 else loops + 1
        }
        return 1
    }

    private fun Node.children(): List<IIOMetadataNode> {
        val out = ArrayList<IIOMetadataNode>()
        var c = firstChild
        while (c != null) {
            if (c is IIOMetadataNode) out += c
            c = c.nextSibling
        }
        return out
    }

    private fun Node.child(name: String): IIOMetadataNode? = children().firstOrNull { it.nodeName == name }

    private fun IIOMetadataNode.int(attr: String): Int? = getAttribute(attr).takeIf { it.isNotEmpty() }?.toIntOrNull()
}
