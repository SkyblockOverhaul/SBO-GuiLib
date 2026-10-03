package net.sbo.guilib.core.layout

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Colors
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.Rect
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.NodeBuilder
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.input
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.dsl.table
import net.sbo.guilib.core.dsl.td
import net.sbo.guilib.core.dsl.tr
import net.sbo.guilib.core.paint.PaintCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** aspect-ratio, outline, text-transform, word-break / overflow-wrap, line-clamp. FakeMeasurer: 4px per char, 10px lines. */
class QuickCssTest {
    private val ua = javaClass.getResource("/assets/guilib/css/ua.css")!!.readText()

    private fun ui(css: String, content: NodeBuilder.() -> Unit): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT), Stylesheet.parse(css, "t.css")), clock = { 0L })
        root.render(VComponent(component("T") { content() }, Unit, null))
        root.frame(400f, 300f)
        return root
    }

    private fun UiRoot.rect(sel: String) = document.body.querySelector(sel)!!.getBoundingClientRect()
    private fun UiRoot.texts() = painter.commands.filterIsInstance<PaintCommand.Text>().map { it.text }

    // ---- text-transform ----------------------------------------------------------------------------------------

    @Test
    fun textTransform() {
        assertEquals(listOf("HELLO WORLD"), ui(".t { text-transform: uppercase }") { div(className = "t") { +"Hello world" } }.texts())
        assertEquals(listOf("hello world"), ui(".t { text-transform: lowercase }") { div(className = "t") { +"HeLLo World" } }.texts())
        assertEquals(listOf("Hello World-Wide Don't"), ui(".t { text-transform: capitalize }") { div(className = "t") { +"hello world-wide don't" } }.texts())
        // Inherited, and the width follows the transformed text.
        val root = ui(".t { text-transform: uppercase } .s { display: inline-block }") { div(className = "t") { span(className = "s") { +"abc" } } }
        assertEquals(listOf("ABC"), root.texts())
        // Inputs show what was typed (their caret maps 1:1 to the value).
        assertTrue("abc" in ui("input { text-transform: uppercase }") { input(value = "abc") }.texts())
    }

    // ---- word-break / overflow-wrap ----------------------------------------------------------------------------

    @Test
    fun overflowWrapBreaksLongWords() {
        val normal = ui(".w { width: 20px }") { div(className = "w") { +"abcdefghijkl" } }
        assertEquals(10f, normal.rect(".w").height) // overflows on one line
        for (value in listOf("overflow-wrap: break-word", "overflow-wrap: anywhere", "word-wrap: break-word", "word-break: break-word")) {
            val root = ui(".w { width: 20px; $value }") { div(className = "w") { +"abcdefghijkl" } }
            assertEquals(30f, root.rect(".w").height, value) // abcde / fghij / kl
            assertEquals(listOf("abcde", "fghij", "kl"), root.texts(), value)
        }
        // Short words still wrap at spaces.
        assertEquals(listOf("ab cd", "efg"), ui(".w { width: 20px; overflow-wrap: anywhere }") { div(className = "w") { +"ab cd efg" } }.texts())
    }

    @Test
    fun anywhereShrinksMinContentButBreakWordDoesNot() {
        fun cellWidth(value: String) = ui("table { width: 1px; border-spacing: 0 } td { padding: 0; $value }") {
            table { tr { td(className = "c") { +"abcdefghijkl" } } }
        }.rect(".c").width
        assertEquals(48f, cellWidth("overflow-wrap: break-word"))
        assertEquals(4f, cellWidth("overflow-wrap: anywhere"))
        assertEquals(4f, cellWidth("word-break: break-all"))
    }

    @Test
    fun breakAllBreaksBetweenAnyCharacters() {
        assertEquals(20f, ui(".w { width: 20px }") { div(className = "w") { +"aaa bbbbbbb" } }.rect(".w").height)
        val all = ui(".w { width: 20px; word-break: break-all }") { div(className = "w") { +"aaa bbbbbbb" } }
        assertEquals(30f, all.rect(".w").height) // "aaa b" / "bbbbb" / "b"
        assertEquals(listOf("aaa b", "bbbbb", "b"), all.texts())
    }

    // ---- line-clamp --------------------------------------------------------------------------------------------

    @Test
    fun lineClampKeepsTheFirstLinesWithAnEllipsis() {
        val text = "aaaa bbbb cccc dddd"
        for (css in listOf(
            ".w { width: 20px; line-clamp: 2; overflow: hidden }",
            ".w { width: 20px; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden }",
        )) {
            val root = ui(css) { div(className = "w") { +text } }
            assertEquals(20f, root.rect(".w").height, css)
            assertEquals(listOf("aaaa", "bbbb", "…"), root.texts(), css) // "bbbb…" = 20px fits
        }
        // Fewer lines than the clamp: unchanged, no ellipsis.
        assertEquals(listOf("aaaa"), ui(".w { width: 20px; line-clamp: 3 }") { div(className = "w") { +"aaaa" } }.texts())
        assertEquals(40f, ui(".w { width: 20px; line-clamp: none }") { div(className = "w") { +text } }.rect(".w").height)
    }

    // ---- aspect-ratio ------------------------------------------------------------------------------------------

    @Test
    fun aspectRatioSizesTheMissingSide() {
        assertEquals(20f, ui(".b { width: 40px; aspect-ratio: 2 }") { div(className = "b") }.rect(".b").height)
        assertEquals(90f, ui(".b { width: 160px; aspect-ratio: 16 / 9 }") { div(className = "b") }.rect(".b").height)
        // Block with auto width fills its container: height from that width.
        assertEquals(100f, ui(".p { width: 100px } .b { aspect-ratio: 1 }") { div(className = "p") { div(className = "b") } }.rect(".b").height)
        // Fixed height: the width follows (also for shrink-to-fit boxes).
        assertEquals(15f, ui(".b { height: 30px; aspect-ratio: 1 / 2 }") { div(className = "b") }.rect(".b").width)
        assertEquals(60f, ui(".b { display: inline-block; height: 30px; aspect-ratio: 2 }") { div { div(className = "b") } }.rect(".b").width)
        // content-box: the ratio is between the content sizes.
        assertEquals(30f, ui(".b { box-sizing: content-box; width: 40px; padding: 5px; aspect-ratio: 2 }") { div(className = "b") }.rect(".b").height)
        assertEquals(20f, ui(".b { width: 40px; aspect-ratio: auto 2 }") { div(className = "b") }.rect(".b").height)
        assertEquals(0f, ui(".b { width: 40px; aspect-ratio: auto }") { div(className = "b") }.rect(".b").height)
    }

    @Test
    fun aspectRatioBoxGrowsForContentUnlessItClips() {
        val text = "aaaa bbbb cccc"
        assertEquals(30f, ui(".b { width: 20px; aspect-ratio: 4 }") { div(className = "b") { +text } }.rect(".b").height)
        assertEquals(5f, ui(".b { width: 20px; aspect-ratio: 4; overflow: hidden }") { div(className = "b") { +text } }.rect(".b").height)
    }

    // ---- outline -----------------------------------------------------------------------------------------------

    private fun UiRoot.outlineBoxes() = painter.commands.filterIsInstance<PaintCommand.Box>().filter { it.background == Colors.TRANSPARENT && it.borders.any { b -> b > 0f } }

    @Test
    fun outlineIsDrawnOutsideTheBorderBox() {
        val root = ui(".b { width: 40px; height: 20px; outline: 2px solid #ff0000; outline-offset: 3px }") { div(className = "b") }
        val o = root.outlineBoxes().single()
        assertEquals(Rect(-5f, -5f, 50f, 30f), Rect(o.x, o.y, o.width, o.height))
        assertTrue(o.borders.all { it == 2f })
        assertEquals(0xFFFF0000.toInt(), o.borderColors[0])
        // It takes no space.
        assertEquals(Rect(0f, 0f, 40f, 20f), root.rect(".b"))
    }

    @Test
    fun outlineFollowsBorderRadiusAndCanBeNegativeOrOff() {
        val round = ui(".b { width: 40px; height: 20px; border-radius: 4px; outline: 1px solid red; outline-offset: 1px }") { div(className = "b") }
        assertEquals(6f, round.outlineBoxes().single().radii[0]) // 4 + offset 1 + width 1
        val inside = ui(".b { width: 40px; height: 20px; outline: 1px solid red; outline-offset: -3px }") { div(className = "b") }
        assertEquals(Rect(2f, 2f, 36f, 16f), inside.outlineBoxes().single().let { Rect(it.x, it.y, it.width, it.height) })
        assertTrue(ui(".b { width: 40px; height: 20px; outline: 2px solid red; outline: none }") { div(className = "b") }.outlineBoxes().isEmpty())
        assertTrue(ui(".b { width: 40px; height: 20px; outline-width: 2px; outline-color: red }") { div(className = "b") }.outlineBoxes().isEmpty()) // no style
        // Drawn after the children (on top of them).
        val root = ui(".b { width: 40px; height: 20px; outline: 1px solid red } .c { height: 5px; background-color: #00ff00 }") { div(className = "b") { div(className = "c") } }
        val cmds = root.painter.commands
        assertTrue(cmds.indexOfFirst { it is PaintCommand.Box && it.background == 0xFF00FF00.toInt() } < cmds.indexOf(root.outlineBoxes().single()))
    }
}
