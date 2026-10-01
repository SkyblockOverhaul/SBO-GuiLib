package net.sbo.guilib.core.paint

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.layout.FakeMeasurer
import net.sbo.guilib.core.layout.Fragment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** FakeMeasurer at font-size 8: every character is 4px wide, ascent 6, descent 2. */
class InlineBoxTest {
    private val ua = "div { display: block } span { display: inline }"

    private fun ui(css: String, width: Float = 200f, content: net.sbo.guilib.core.dsl.ComponentScope.() -> Unit): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT), Stylesheet.parse(css, "t.css")), clock = { 0L })
        root.render(VComponent(component("T") { content() }, Unit, null))
        root.frame(width, 100f)
        return root
    }

    private fun UiRoot.boxes() = painter.commands.filterIsInstance<PaintCommand.Box>()

    @Test
    fun horizontalEdgesTakeSpaceAndTheBackgroundSpansTheElement() {
        val root = ui(".x { margin: 5px 2px; padding: 3px; border: 1px solid #ffffff; background-color: #ff0000 }") {
            div { +"ab"; span(className = "x") { +"cd" }; +"ef" }
        }
        val frags = root.document.body.querySelector("div")!!.box.paragraphs.single().lines.single().fragments
        // "ab" 0..8, start edge 2 + 1 + 3 = 6, "cd" 14..22, end edge 3 + 1 + 2 = 6, "ef" from 28.
        assertEquals(listOf(0f, 8f, 14f, 22f, 28f), frags.map { it.x })
        assertTrue(frags[1] is Fragment.Edge && (frags[1] as Fragment.Edge).start)
        val box = root.boxes().single()
        // From after the left margin to before the right margin; vertically the font box plus padding and border
        // (baseline 7 with the half-leading → 7 - 6 - 3 - 1 = -3 to 7 + 2 + 3 + 1 = 13). Vertical margins are ignored.
        assertEquals(listOf(10f, -3f, 16f, 16f), listOf(box.x, box.y, box.width, box.height))
        assertEquals(listOf(1f, 1f, 1f, 1f), box.borders.toList())
        // The line box keeps its normal height.
        assertEquals(10f, root.document.body.querySelector("div")!!.box.height)
    }

    @Test
    fun wrappedElementsGetOneBoxPerLineWithEdgesOnlyAtTheEnds() {
        val root = ui(".x { padding: 0 2px; border-left: 1px solid #fff; border-right: 1px solid #fff; border-radius: 3px; background-color: #ff0000 }", width = 40f) {
            div { span(className = "x") { +"aaaa bbbb" } }
        }
        val boxes = root.boxes()
        assertEquals(2, boxes.size)
        val (first, second) = boxes
        // Line 1: start edge (3) + "aaaa"; line 2: "bbbb" + end edge (3).
        assertEquals(listOf(0f, 19f), listOf(first.x, first.width))
        assertEquals(listOf(1f, 0f), listOf(first.borders[3], first.borders[1]))
        assertEquals(listOf(3f, 0f, 0f, 3f), first.radii.toList())
        assertEquals(listOf(0f, 1f), listOf(second.borders[3], second.borders[1]))
        assertEquals(listOf(0f, 3f, 3f, 0f), second.radii.toList())
        assertEquals(19f, second.width)
    }

    @Test
    fun nestedInlineBoxesPaintOuterFirst() {
        val root = ui(".o { background-color: #ff0000 } .i { background-color: #00ff00; padding: 0 1px }") {
            div { span(className = "o") { +"a"; span(className = "i") { +"b" } } }
        }
        val colors = root.boxes().map { it.background }
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), colors)
        assertEquals(listOf(10f, 6f), root.boxes().map { it.width }) // outer: "a" + inner (1 + "b" + 1)
    }
}
