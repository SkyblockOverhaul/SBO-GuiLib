package net.sbo.guilib.core.paint

import net.sbo.guilib.core.css.BackgroundLayer
import net.sbo.guilib.core.css.CssParser
import net.sbo.guilib.core.css.FakeElement
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.StyleContext
import net.sbo.guilib.core.css.StyleEngine
import net.sbo.guilib.core.css.Stylesheet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class GradientTest {
    private fun layers(css: String): List<BackgroundLayer> {
        val engine = StyleEngine(listOf(Stylesheet.parse(".a { $css }", "t.css", Origin.AUTHOR)))
        return engine.compute(FakeElement("div", classes = "a"), emptyList(), null, StyleContext(100f, 100f)).backgroundLayers
    }

    private fun gradient(css: String) = layers("background-image: $css").single() as BackgroundLayer.Gradient

    /** Color the GPU would produce at (px, py): barycentric interpolation inside the containing triangle. */
    private fun sample(mesh: ColorMesh, px: Float, py: Float): Int? {
        for (t in 0 until mesh.triangleCount) {
            val i = t * 3
            val (x0, y0, x1, y1, x2, y2) = listOf(mesh.x[i], mesh.y[i], mesh.x[i + 1], mesh.y[i + 1], mesh.x[i + 2], mesh.y[i + 2])
            val d = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
            if (abs(d) < 1e-6f) continue
            val a = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / d
            val b = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / d
            val c = 1f - a - b
            if (a < -1e-4f || b < -1e-4f || c < -1e-4f) continue
            fun ch(shift: Int) = (a * ((mesh.color[i] ushr shift) and 0xFF) + b * ((mesh.color[i + 1] ushr shift) and 0xFF) + c * ((mesh.color[i + 2] ushr shift) and 0xFF)).toInt()
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return null
    }

    private operator fun <T> List<T>.component6() = this[5]

    private fun assertColor(expected: Int, actual: Int?, tolerance: Int = 3) {
        assertNotNull(actual)
        for (shift in listOf(24, 16, 8, 0)) {
            val e = (expected ushr shift) and 0xFF
            val a = (actual!! ushr shift) and 0xFF
            assertTrue(abs(e - a) <= tolerance, "expected #%08X but was #%08X".format(expected, actual))
        }
    }

    @Test
    fun parsesLinearGradients() {
        val g = gradient("linear-gradient(to right, red, #0000ff 75%)")
        assertEquals(false, g.radial)
        assertEquals(90f, g.angle)
        assertEquals(2, g.stops.size)
        assertEquals(0xFFFF0000.toInt(), g.stops[0].color)
        assertEquals(75f, g.stops[1].position!!.value)

        assertEquals(45f, gradient("linear-gradient(45deg, red, blue)").angle)
        assertEquals(90f, gradient("linear-gradient(0.25turn, red, blue)").angle)
        assertEquals(1 to -1, gradient("linear-gradient(to top right, red, blue)").toCorner)
        assertEquals(180f, gradient("linear-gradient(red, blue)").angle) // default: to bottom
    }

    @Test
    fun parsesRadialGradientsAndLayers() {
        val g = gradient("radial-gradient(circle at left top, white, black)")
        assertTrue(g.radial && g.circle)
        assertEquals(0f, g.centerX.value)
        assertEquals(0f, g.centerY.value)

        val two = layers("background: linear-gradient(red, blue), url(\"mod:x.png\"), #111")
        assertEquals(2, two.size)
        assertTrue(two[1] is BackgroundLayer.Url)

        // Invalid gradients are dropped with a warning instead of crashing.
        assertTrue(layers("background-image: linear-gradient(to nowhere, red, blue)").isEmpty())
        assertNull(CssParser.parseDeclarations("background-image: linear-gradient(42)").firstOrNull()?.let { it })
    }

    @Test
    fun linearMeshMatchesCssColors() {
        val g = gradient("linear-gradient(to right, #ff0000, #0000ff)")
        val mesh = GradientMesh.build(g, 0f, 0f, 100f, 20f, 1f)
        assertColor(0xFFFF0000.toInt(), sample(mesh, 0.01f, 10f))
        assertColor(0xFF7F007F.toInt(), sample(mesh, 50f, 10f))
        assertColor(0xFF0000FF.toInt(), sample(mesh, 99.99f, 10f))
    }

    @Test
    fun linearMeshHandlesStopsAnglesAndHardEdges() {
        // Hard stop at 50%: left half red, right half blue.
        val hard = GradientMesh.build(gradient("linear-gradient(90deg, red 50%, blue 50%)"), 0f, 0f, 100f, 10f, 1f)
        assertColor(0xFFFF0000.toInt(), sample(hard, 49f, 5f))
        assertColor(0xFF0000FF.toInt(), sample(hard, 51f, 5f))

        // to bottom with three stops.
        val three = GradientMesh.build(gradient("linear-gradient(red, lime, blue)"), 0f, 0f, 10f, 100f, 1f)
        assertColor(0xFF00FF00.toInt(), sample(three, 5f, 50f))

        // 45deg: the corners get the end colors.
        val diag = GradientMesh.build(gradient("linear-gradient(45deg, black, white)"), 0f, 0f, 100f, 100f, 1f)
        assertColor(0xFF000000.toInt(), sample(diag, 0.5f, 99.5f), 6)
        assertColor(0xFFFFFFFF.toInt(), sample(diag, 99.5f, 0.5f), 6)
    }

    @Test
    fun transparentStopsDoNotTurnGrey() {
        val mesh = GradientMesh.build(gradient("linear-gradient(to right, transparent, white)"), 0f, 0f, 100f, 10f, 1f)
        // Halfway: half-transparent *white*, not grey (CSS premultiplied interpolation).
        assertColor(0x7FFFFFFF, sample(mesh, 50f, 5f))
    }

    @Test
    fun radialMeshCoversTheBox() {
        val mesh = GradientMesh.build(gradient("radial-gradient(circle, white, black)"), 0f, 0f, 40f, 40f, 1f)
        var area = 0f
        for (t in 0 until mesh.triangleCount) {
            val i = t * 3
            area += abs((mesh.x[i + 1] - mesh.x[i]) * (mesh.y[i + 2] - mesh.y[i]) - (mesh.x[i + 2] - mesh.x[i]) * (mesh.y[i + 1] - mesh.y[i])) / 2f
        }
        assertEquals(1600f, area, 1f)
        assertColor(0xFFFFFFFF.toInt(), sample(mesh, 20f, 20f), 8)
        assertColor(0xFF000000.toInt(), sample(mesh, 0.5f, 0.5f), 8)
    }

    @Test
    fun opacityIsApplied() {
        val mesh = GradientMesh.build(gradient("linear-gradient(red, red)"), 0f, 0f, 10f, 10f, 0.5f)
        assertColor(0x80FF0000.toInt(), sample(mesh, 5f, 5f))
    }
}
