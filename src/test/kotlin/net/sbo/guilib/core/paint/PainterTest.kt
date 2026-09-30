package net.sbo.guilib.core.paint

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.scroll
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PainterTest {
    private val ua = """
        div, scroll { display: block }
        span { display: inline }
        button { display: inline-flex }
        scroll { overflow: auto }
    """.trimIndent()

    private fun ui(css: String, content: net.sbo.guilib.core.dsl.ComponentScope.() -> Unit): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT), Stylesheet.parse(css, "t.css")), clock = { 0L })
        root.render(VComponent(component("T") { content() }, Unit, null))
        root.frame(200f, 100f)
        return root
    }

    private fun UiRoot.texts() = painter.commands.filterIsInstance<PaintCommand.Text>().map { it.text }

    @Test
    fun paintsTextOfFlexItemsAndParagraphs() {
        val root = ui("") {
            div { +"para" }
            button { +"label" }
        }
        assertEquals(listOf("para", "label"), root.texts())
    }

    @Test
    fun backgroundsAndBordersBecomeBoxes() {
        val root = ui(".a { width: 10px; height: 10px; background-color: red; border: 1px solid blue; border-radius: 2px; opacity: 0.5 }") {
            div(className = "a")
        }
        val box = root.painter.commands.filterIsInstance<PaintCommand.Box>().single()
        assertEquals(0x80FF0000.toInt(), box.background)
        assertEquals(1f, box.borders[0])
        assertEquals(2f, box.radii[0])
    }

    @Test
    fun childrenInRoundedClipFollowItsCorners() {
        val root = ui(
            """
            .card { width: 100px; height: 60px; border: 1px solid red; border-radius: 6px; overflow: hidden }
            .header { height: 10px; background-color: blue }
            .body { height: 20px; background-color: lime; border-radius: 2px }
            """.trimIndent(),
        ) {
            div(className = "card") { div(className = "header"); div(className = "body") }
        }
        val boxes = root.painter.commands.filterIsInstance<PaintCommand.Box>()
        val header = boxes.first { it.background == 0xFF0000FF.toInt() }
        // Top corners get the card's inner radius (6 - 1 border), bottom corners stay square.
        assertEquals(listOf(5f, 5f, 0f, 0f), header.radii.toList())
        // The body touches no corner of the clip: its own radius is kept.
        val body = boxes.first { it.background == 0xFF00FF00.toInt() }
        assertEquals(listOf(2f, 2f, 2f, 2f), body.radii.toList())
    }

    @Test
    fun zIndexOrdersPositionedElements() {
        val root = ui(
            """
            .a { position: absolute; z-index: 2; width: 10px; height: 10px; background-color: red }
            .b { position: absolute; z-index: 1; width: 10px; height: 10px; background-color: blue }
            .c { width: 10px; height: 10px; background-color: lime }
            """.trimIndent(),
        ) {
            div(className = "a"); div(className = "b"); div(className = "c")
        }
        val colors = root.painter.commands.filterIsInstance<PaintCommand.Box>().map { it.background }
        assertEquals(listOf(0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFF0000.toInt()), colors)
        // The top-most element wins the hit test.
        assertEquals("a", root.painter.hitTest(5f, 5f)?.className)
    }

    @Test
    fun scrollContainersClipAndOffsetContent() {
        val root = ui(".s { height: 20px } .row { height: 10px }") {
            scroll(className = "s") { repeat(5) { div(className = "row", key = it) { +"r$it" } } }
        }
        val s = root.document.body.querySelector(".s")!!
        assertEquals(30f, s.maxScrollTop)
        assertTrue(root.painter.commands.first() is PaintCommand.PushClip)
        s.scrollTop = 15f
        root.frame(200f, 100f)
        val first = root.painter.commands.filterIsInstance<PaintCommand.Text>().first()
        assertEquals(-15f + 1f, first.y, 0.01f) // row 0 at y=-15, glyph top = baseline - ascent (half-leading 1)
        // Hit-testing respects the scroll offset: y=1 is now inside row 1 (10..20 → -5..5).
        assertEquals("row", root.painter.hitTest(5f, 1f)?.className)
    }

    @Test
    fun inlineSpansAreHitTargets() {
        val root = ui("") {
            div { +"aa "; span(className = "link") { +"bb" } }
        }
        // "aa " = 3 chars * 4px; the span covers x 12..20.
        assertEquals("link", root.painter.hitTest(14f, 2f)?.className)
        assertEquals("div", root.painter.hitTest(2f, 2f)?.tagName)
    }

    @Test
    fun hoverAndClickThroughInteractionController() {
        var clicks = 0
        val root = ui("button { width: 20px; height: 10px } button:hover { background-color: red }") {
            button(onClick = { clicks++ }) { +"x" }
        }
        root.input.mouseMove(5f, 5f)
        root.frame(200f, 100f)
        val btn = root.document.body.querySelector("button")!!
        assertEquals(0xFFFF0000.toInt(), btn.style.backgroundColor)
        root.input.mouseDown(5f, 5f, 0)
        root.input.mouseUp(5f, 5f, 0)
        assertEquals(1, clicks)
        // Pressing on the button and releasing elsewhere is not a click.
        root.input.mouseDown(5f, 5f, 0)
        root.input.mouseUp(150f, 80f, 0)
        assertEquals(1, clicks)
    }

    @Test
    fun wheelScrollsNearestScrollableAncestor() {
        val root = ui(".s { height: 20px } .row { height: 10px }") {
            scroll(className = "s") { repeat(5) { div(className = "row", key = it) } }
        }
        root.input.wheel(5f, 5f, 0f, 12f)
        assertEquals(12f, root.document.body.querySelector(".s")!!.scrollTop)
        root.input.wheel(5f, 5f, 0f, 100f)
        assertEquals(30f, root.document.body.querySelector(".s")!!.scrollTop) // clamped
    }

    @Test
    fun tabMovesFocus() {
        val root = ui("") {
            button(className = "one") { +"1" }
            button(className = "two") { +"2" }
        }
        root.input.keyDown("Tab", 258)
        assertEquals("one", root.document.focusedElement?.className)
        root.input.keyDown("Tab", 258)
        assertEquals("two", root.document.focusedElement?.className)
        root.input.keyDown("Tab", 258, net.sbo.guilib.core.event.Modifiers(shift = true))
        assertEquals("one", root.document.focusedElement?.className)
    }
}
