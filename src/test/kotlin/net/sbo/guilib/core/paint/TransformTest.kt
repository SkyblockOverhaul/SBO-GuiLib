package net.sbo.guilib.core.paint

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Dim
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.css.TransformFn
import net.sbo.guilib.core.dom.Rect
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.ComponentScope
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransformTest {
    private var now = 0L

    private fun ui(css: String, content: ComponentScope.() -> Unit): UiRoot {
        val ua = Stylesheet.parse("div { display: block }", "ua", Origin.USER_AGENT)
        val root = UiRoot(FakeMeasurer, listOf(ua, Stylesheet.parse(css, "t.css")), clock = { now })
        root.render(VComponent(component("T") { content() }, Unit, null))
        root.frame(200f, 100f)
        return root
    }

    private fun UiRoot.at(ms: Long) = apply { now = ms; frame(200f, 100f) }
    private fun UiRoot.el(sel: String) = document.body.querySelector(sel)!!
    private fun UiRoot.boxes() = painter.commands.filterIsInstance<PaintCommand.Box>()

    private fun assertRect(expected: Rect, x: Float, y: Float, w: Float, h: Float) {
        assertEquals(expected.x, x, 0.01f, "x"); assertEquals(expected.y, y, 0.01f, "y")
        assertEquals(expected.width, w, 0.01f, "width"); assertEquals(expected.height, h, 0.01f, "height")
    }

    private val box = ".a { position: absolute; left: 10px; top: 10px; width: 20px; height: 10px; background-color: red }"

    @Test
    fun parsesTranslateAndScale() {
        val root = ui("$box .a { transform: translate(10px, 50%) scaleX(2) } .b { transform: rotate(10deg) }") {
            div(className = "a"); div(className = "b")
        }
        assertEquals(listOf(TransformFn.Translate(Dim.Px(10f), Dim.Pct(50f)), TransformFn.Scale(2f, 1f)), root.el(".a").style.transform)
        // Unsupported functions invalidate the declaration (like invalid CSS), so it stays `none`.
        assertEquals(emptyList<TransformFn>(), root.el(".b").style.transform)
    }

    @Test
    fun paintsHitTestsAndMeasuresTransformedBoxes() {
        val root = ui("$box .a { transform: translate(5px) scale(2); transform-origin: left top }") { div(className = "a") }
        // Scale 2 around (10, 10), then move 5px right: (10, 10, 20, 10) -> (15, 10, 40, 20).
        val b = root.boxes().single()
        assertRect(Rect(15f, 10f, 40f, 20f), b.x, b.y, b.width, b.height)
        val r = root.el(".a").getBoundingClientRect()
        assertRect(Rect(15f, 10f, 40f, 20f), r.x, r.y, r.width, r.height)
        assertEquals(root.el(".a"), root.painter.hitTest(50f, 25f)) // only inside the scaled box
        assertTrue(root.painter.hitTest(12f, 12f) != root.el(".a")) // inside the layout box, but the transform moved it away
    }

    @Test
    fun percentagesUseTheOwnBoxAndOriginDefaultsToCenter() {
        val root = ui("$box .a { transform: translateX(-50%) scale(0.5) }") { div(className = "a") }
        // Center (20, 15); half size -> (15, 12.5, 10, 5); then -50% of the 20px box = -10 (T·S: not scaled).
        val b = root.boxes().single()
        assertRect(Rect(5f, 12.5f, 10f, 5f), b.x, b.y, b.width, b.height)
    }

    @Test
    fun childrenAndTextFollowTheTransform() {
        val root = ui("$box .a { transform: scale(2); transform-origin: 0 0 } .c { width: 5px; height: 5px; background-color: blue }") {
            div(className = "a") { div(className = "c"); +"hi" }
        }
        val child = root.boxes().first { it.background == 0xFF0000FF.toInt() }
        assertRect(Rect(10f, 10f, 10f, 10f), child.x, child.y, child.width, child.height)
        val text = root.painter.commands.filterIsInstance<PaintCommand.Text>().single()
        assertEquals(16f, text.style.fontSize, 0.01f) // drawn at the scaled size
    }

    @Test
    fun keyframesAndTransitionsAnimateTransforms() {
        val root = ui(
            """
            $box
            @keyframes slide { from { transform: translateX(-20px) } to { transform: none } }
            .a { animation: slide 100ms linear }
            """.trimIndent(),
        ) { div(className = "a") }
        assertEquals(-10f, root.at(50).boxes().single().x - 10f, 0.01f)
        assertEquals(10f, root.at(200).boxes().single().x, 0.01f)
        assertTrue(root.el(".a").style.transform.isEmpty())
    }
}
