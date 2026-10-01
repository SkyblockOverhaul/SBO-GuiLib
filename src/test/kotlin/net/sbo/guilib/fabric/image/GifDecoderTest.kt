package net.sbo.guilib.fabric.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode

class GifDecoderTest {
    private val CLEAR = 0
    private val RED = 0xFFFF0000.toInt()
    private val GREEN = 0xFF00FF00.toInt()
    private val BLUE = 0xFF0000FF.toInt()

    /** Palette index 0 is transparent. */
    private val palette = IndexColorModel(8, 4, intArrayOf(0, RED, GREEN, BLUE), 0, true, 0, java.awt.image.DataBuffer.TYPE_BYTE)

    private class F(val x: Int, val y: Int, val w: Int, val h: Int, val color: Int, val delayCs: Int = 5, val disposal: String = "none", val holeAt: Pair<Int, Int>? = null)

    /** Writes a GIF with a [w]×[h] logical screen; [loops] = NETSCAPE loop count, `null` = no loop extension. */
    private fun gif(w: Int, h: Int, frames: List<F>, loops: Int? = 0): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val type = ImageTypeSpecifier.createFromRenderedImage(BufferedImage(1, 1, BufferedImage.TYPE_BYTE_INDEXED, palette))
            val stream = writer.getDefaultStreamMetadata(null)
            val streamFormat = "javax_imageio_gif_stream_1.0"
            val sroot = stream.getAsTree(streamFormat) as IIOMetadataNode
            (sroot.getElementsByTagName("LogicalScreenDescriptor").item(0) as IIOMetadataNode).apply {
                setAttribute("logicalScreenWidth", "$w"); setAttribute("logicalScreenHeight", "$h")
            }
            stream.setFromTree(streamFormat, sroot)
            writer.prepareWriteSequence(stream)
            for ((i, f) in frames.withIndex()) {
                val img = BufferedImage(f.w, f.h, BufferedImage.TYPE_BYTE_INDEXED, palette)
                val index = intArrayOf(0, RED, GREEN, BLUE).indexOf(f.color)
                for (y in 0 until f.h) for (x in 0 until f.w) img.raster.setSample(x, y, 0, if (f.holeAt == x to y) 0 else index)
                val meta = writer.getDefaultImageMetadata(type, null)
                val format = "javax_imageio_gif_image_1.0"
                val root = meta.getAsTree(format) as IIOMetadataNode
                fun node(name: String): IIOMetadataNode = (root.getElementsByTagName(name).item(0) as? IIOMetadataNode)
                    ?: IIOMetadataNode(name).also { root.appendChild(it) }
                node("ImageDescriptor").apply {
                    setAttribute("imageLeftPosition", "${f.x}"); setAttribute("imageTopPosition", "${f.y}")
                    setAttribute("imageWidth", "${f.w}"); setAttribute("imageHeight", "${f.h}")
                    setAttribute("interlaceFlag", "FALSE") // the JDK writer garbles interlaced images this small
                }
                node("GraphicControlExtension").apply {
                    setAttribute("disposalMethod", f.disposal); setAttribute("userInputFlag", "FALSE")
                    setAttribute("transparentColorFlag", "TRUE"); setAttribute("transparentColorIndex", "0")
                    setAttribute("delayTime", "${f.delayCs}")
                }
                if (i == 0 && loops != null) {
                    val apps = node("ApplicationExtensions")
                    apps.appendChild(IIOMetadataNode("ApplicationExtension").apply {
                        setAttribute("applicationID", "NETSCAPE"); setAttribute("authenticationCode", "2.0")
                        userObject = byteArrayOf(1, (loops and 0xFF).toByte(), (loops shr 8 and 0xFF).toByte())
                    })
                }
                meta.setFromTree(format, root)
                writer.writeToSequence(IIOImage(img, null, meta), null)
            }
            writer.endWriteSequence()
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray) = GifDecoder.decode(ByteArrayInputStream(bytes))

    private fun GifDecoder.Gif.px(frame: Int, x: Int, y: Int) = frames[frame].argb[y * width + x]

    @Test
    fun framesAreCompositedAtTheirOffsets() {
        val g = decode(gif(4, 4, listOf(F(0, 0, 4, 4, RED, delayCs = 5), F(1, 1, 2, 2, GREEN, delayCs = 0, holeAt = 0 to 0))))
        assertEquals(4, g.width); assertEquals(4, g.height); assertEquals(2, g.frames.size)
        assertEquals(RED, g.px(1, 0, 0))
        assertEquals(RED, g.px(1, 1, 1)) // transparent pixel of frame 1 shows frame 0 below
        assertEquals(GREEN, g.px(1, 2, 2))
        assertEquals(RED, g.px(1, 3, 3))
        assertEquals(listOf(50, 100), g.frames.map { it.delayMs }) // a 0 delay plays at 100 ms like in browsers
        assertEquals(0, g.plays)
    }

    @Test
    fun disposalMethods() {
        val bg = decode(gif(4, 4, listOf(F(0, 0, 4, 4, RED, disposal = "restoreToBackgroundColor"), F(0, 0, 1, 1, BLUE))))
        assertEquals(BLUE, bg.px(1, 0, 0))
        assertEquals(CLEAR, bg.px(1, 3, 3)) // the area of frame 0 was cleared to transparent

        val prev = decode(gif(4, 4, listOf(F(0, 0, 4, 4, RED), F(0, 0, 2, 2, GREEN, disposal = "restoreToPrevious"), F(3, 3, 1, 1, BLUE))))
        assertEquals(GREEN, prev.px(1, 0, 0))
        assertEquals(RED, prev.px(2, 0, 0)) // frame 1 was undone before frame 2
        assertEquals(BLUE, prev.px(2, 3, 3))
    }

    @Test
    fun timingAndLoopCount() {
        val forever = decode(gif(2, 2, listOf(F(0, 0, 2, 2, RED, delayCs = 10), F(0, 0, 2, 2, GREEN, delayCs = 30))))
        assertEquals(400, forever.durationMs)
        assertEquals(listOf(0, 0, 1, 1, 0, 1), listOf(0L, 99L, 100L, 399L, 400L, 4_000_150L).map { forever.frameAt(it) })

        val twice = decode(gif(2, 2, listOf(F(0, 0, 2, 2, RED, delayCs = 10), F(0, 0, 2, 2, GREEN, delayCs = 10)), loops = 1))
        assertEquals(2, twice.plays)
        assertEquals(0, twice.frameAt(200)) // second play
        assertEquals(1, twice.frameAt(10_000)) // stays on the last frame afterwards

        val once = decode(gif(2, 2, listOf(F(0, 0, 2, 2, RED), F(0, 0, 2, 2, GREEN)), loops = null))
        assertEquals(1, once.plays)
    }

    @Test
    fun longAnimationsAreCutOff() {
        val bytes = gif(4, 4, List(10) { F(0, 0, 4, 4, if (it % 2 == 0) RED else BLUE) })
        var cut = -1
        val g = GifDecoder.decode(ByteArrayInputStream(bytes), maxTotalPixels = 16L * 3) { cut = it }
        assertEquals(3, g.frames.size)
        assertEquals(3, cut)
    }
}
