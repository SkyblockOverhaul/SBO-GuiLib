package net.sbo.guilib.fabric.image

import com.github.weisj.jsvg.parser.LoaderContext
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.view.ViewBox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream

/** `currentColor` in an SVG image takes the element's CSS `color`. */
class SvgColorTest {
    private val icon = """<?xml version="1.0" encoding="UTF-8"?>
        <!-- refresh icon -->
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 4 4" width="4" height="4">
          <rect x="0" y="0" width="2" height="4" fill="currentColor"/>
          <rect x="2" y="0" width="2" height="4" fill="#00ff00"/>
        </svg>"""

    /** Rasterizes [svg] at its natural 4×4 size and returns the ARGB pixel at ([x], [y]). */
    private fun pixel(svg: String, x: Int, y: Int): Int {
        val doc = SVGLoader().load(ByteArrayInputStream(svg.toByteArray()), null, LoaderContext.createDefault())!!
        val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        doc.render(null, g, ViewBox(0f, 0f, 4f, 4f))
        g.dispose()
        return img.getRGB(x, y)
    }

    @Test
    fun detectsCurrentColor() {
        assertTrue(SvgColor.usesCurrentColor(icon))
        assertTrue(SvgColor.usesCurrentColor("""<svg><path stroke="CURRENTCOLOR"/></svg>"""))
        assertFalse(SvgColor.usesCurrentColor("""<svg><path fill="#fff"/></svg>"""))
    }

    @Test
    fun currentColorPartsTakeTheGivenColorAndFixedColorsStay() {
        val red = SvgColor.withCurrentColor(icon, 0xFFFF0000.toInt())
        assertEquals(0xFFFF0000.toInt(), pixel(red, 0, 1))
        assertEquals(0xFF00FF00.toInt(), pixel(red, 3, 1))
        val blue = SvgColor.withCurrentColor(icon, 0xFF2040C0.toInt())
        assertEquals(0xFF2040C0.toInt(), pixel(blue, 0, 1))
    }

    @Test
    fun semiTransparentColorsKeepTheirAlpha() {
        val half = SvgColor.withCurrentColor(icon, 0x80FFFFFF.toInt())
        val alpha = pixel(half, 0, 1) ushr 24
        assertTrue(alpha in 0x7E..0x82, "alpha was $alpha")
    }

    @Test
    fun anExplicitColorOnTheRootElementWins() {
        val own = """<svg xmlns="http://www.w3.org/2000/svg" color="#0000ff" viewBox="0 0 4 4" width="4" height="4">
            <rect width="4" height="4" fill="currentColor"/></svg>"""
        assertEquals(0xFF0000FF.toInt(), pixel(SvgColor.withCurrentColor(own, 0xFFFF0000.toInt()), 1, 1))
    }

    @Test
    fun withoutAColorCurrentColorIsBlackLikeBefore() {
        assertEquals(0xFF000000.toInt(), pixel(icon, 0, 1))
    }
}
