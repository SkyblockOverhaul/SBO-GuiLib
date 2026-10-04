package net.sbo.guilib.fabric.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PixelSnapTest {
    @Test
    fun edgesLandOnWholePixels() {
        assertEquals(12 to 43, PixelSnap.span(12.4f, 30.3f))
    }

    @Test
    fun touchingBoxesShareTheirEdge() {
        // A rounded part on top of a plain one: same column, so same left and right edges
        val plain = PixelSnap.span(36.6f, 28f)
        val rounded = PixelSnap.span(36.6f, 28f)
        assertEquals(plain, rounded)
        // Stacked vertically, the bottom of one is the top of the next
        assertEquals(PixelSnap.span(10.6f, 5.2f).second, PixelSnap.span(15.8f, 3f).first)
    }
}
