package net.sbo.guilib.core.css

import net.sbo.guilib.core.Log
import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MediaTest {
    private val warnings = ArrayList<String>()
    private var oldSink: ((Log.Level, String) -> Unit)? = null

    @BeforeEach
    fun capture() {
        oldSink = Log.sink
        Log.sink = { _, msg -> warnings += msg }
        Log.resetOnce()
    }

    @AfterEach
    fun restore() {
        Log.sink = oldSink!!
    }

    private fun matches(query: String, w: Float = 400f, h: Float = 300f, scale: Float = 2f): Boolean {
        val sheet = Stylesheet.parse("@media $query { a { color: red } }")
        val rule = sheet.rules.single()
        return rule.appliesTo(StyleContext(w, h, resolution = scale))
    }

    @Test
    fun featuresAndPrefixes() {
        assertTrue(matches("(min-width: 400px)"))
        assertFalse(matches("(min-width: 401px)"))
        assertTrue(matches("(max-width: 50em)")) // em = initial font size (8px) → 400px
        assertTrue(matches("(width: 400px)"))
        assertTrue(matches("(orientation: landscape)"))
        assertFalse(matches("(orientation: portrait)"))
        assertTrue(matches("(min-aspect-ratio: 4/3)"))
        assertFalse(matches("(min-aspect-ratio: 16/9)"))
        assertTrue(matches("(min-resolution: 2dppx)"))
        assertFalse(matches("(min-resolution: 3x)"))
        assertTrue(matches("(resolution: 192dpi)"))
        assertTrue(matches("(hover: hover)"))
        assertTrue(matches("(prefers-reduced-motion: no-preference)"))
        assertTrue(matches("(width)"))
    }

    @Test
    fun rangeSyntaxAndLogic() {
        assertTrue(matches("(width >= 400px)"))
        assertFalse(matches("(width > 400px)"))
        assertTrue(matches("(300px < width <= 400px)"))
        assertTrue(matches("(500px > width)"))
        assertTrue(matches("screen and (min-width: 300px) and (max-height: 300px)"))
        assertFalse(matches("print"))
        assertTrue(matches("not print"))
        assertTrue(matches("not all and (max-width: 100px)"))
        assertTrue(matches("(max-width: 100px), (min-height: 200px)"))
        assertTrue(matches("(max-width: 100px) or (min-height: 200px)"))
        assertTrue(matches("((max-width: 100px) or (min-height: 200px)) and (orientation: landscape)"))
        assertTrue(matches("not (max-width: 100px)"))
        assertTrue(matches("only screen and (min-width: 1px)"))
    }

    @Test
    fun invalidQueriesNeverMatchButOthersInTheListStillDo() {
        assertFalse(matches("(min-wdth: 10px)"))
        assertFalse(matches("(min-width: 10)"))
        assertFalse(matches("(width > 1px) and"))
        assertFalse(matches("(a) and (b) or (c)"))
        assertTrue(matches("(foo: bar), (min-width: 1px)"))
        assertTrue(warnings.any { "invalid media query" in it })
    }

    @Test
    fun cascadeFollowsTheViewportAndNestedMedia() {
        val css = """
            div { display: block; color: #ff0000 }
            @media (min-width: 300px) {
                .a { color: #00ff00 }
                @media (min-resolution: 3dppx) { .a { color: #0000ff } }
            }
            .b { color: #ffffff }
        """.trimIndent()
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(css)), clock = { 0L })
        root.render(VComponent(component("T") { div(className = "a") {}; div(className = "b") {} }, Unit, null))
        root.frame(200f, 100f)
        val a = root.document.body.querySelector(".a")!!
        assertEquals(0xFFFF0000.toInt(), a.style.color)
        root.frame(400f, 100f)
        assertEquals(0xFF00FF00.toInt(), a.style.color)
        root.document.resolution = 3f
        root.frame(400f, 100f)
        assertEquals(0xFF0000FF.toInt(), a.style.color)
        // Rules after the @media block still parse.
        assertEquals(0xFFFFFFFF.toInt(), root.document.body.querySelector(".b")!!.style.color)
    }
}
