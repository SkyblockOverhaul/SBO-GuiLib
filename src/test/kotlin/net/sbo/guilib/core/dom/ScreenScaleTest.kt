package net.sbo.guilib.core.dom

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.layout.FontMetrics
import net.sbo.guilib.core.layout.TextMeasurer
import net.sbo.guilib.core.layout.TextStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScreenScaleTest {
    @Test
    fun useScreenScaleSetsTheDocumentScaleWhileMounted() {
        var scale: Float? = 2.5f
        var show = true
        lateinit var rerender: () -> Unit
        val Scaled = component<Float?>("Scaled") { s -> useScreenScale(s) }
        val root = UiRoot(FakeMeasurer())
        root.render(VComponent(component("App") {
            rerender = useForceUpdate()
            if (show) Scaled(scale)
        }, Unit, null))
        root.frame(200f, 100f)
        assertEquals(2.5f, root.document.scale)
        scale = 3f; rerender(); root.frame(200f, 100f)
        assertEquals(3f, root.document.scale)
        scale = null; rerender(); root.frame(200f, 100f)
        assertNull(root.document.scale)
        scale = 1.5f; rerender(); root.frame(200f, 100f)
        show = false; rerender(); root.frame(200f, 100f)
        assertNull(root.document.scale) // restored on unmount
        root.document.scale = Float.NaN
        assertNull(root.document.scale)
    }

    /** Text widths depend on the pixel grid (glyph advances snap to physical pixels), like the real font backend. */
    private class FakeMeasurer : TextMeasurer {
        var resolution = 1f
        override fun width(text: String, style: TextStyle) = text.length * (if (resolution > 2f) 5f else 4f)
        override fun metrics(style: TextStyle) = FontMetrics(6f, 2f, 10f)
    }

    @Test
    fun aNewResolutionReLaysOutTextEvenWhenNoStyleChanges() {
        val measurer = FakeMeasurer()
        val root = UiRoot(measurer, listOf(Stylesheet.parse("span { display: inline }", "ua", Origin.USER_AGENT)))
        root.render(VComponent(component("T") { span(className = "t") { +"abcd" } }, Unit, null))
        root.frame(200f, 100f)
        val el = root.document.body.querySelector(".t")!!
        assertEquals(16f, el.getBoundingClientRect().width)
        measurer.resolution = 3f
        root.document.resolution = 3f
        root.frame(200f, 100f)
        assertEquals(20f, el.getBoundingClientRect().width)
    }
}
