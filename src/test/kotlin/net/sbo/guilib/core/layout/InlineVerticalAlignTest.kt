package net.sbo.guilib.core.layout

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.NodeBuilder
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.dsl.sub
import net.sbo.guilib.core.dsl.sup
import net.sbo.guilib.core.paint.PaintCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `vertical-align` on `display: inline` elements moves their text (sub/sup). FakeMeasurer at font-size 8: ascent 6,
 * descent 2, line height 10 → the strut is 7 above and 3 below the baseline.
 */
class InlineVerticalAlignTest {
    private val ua = "div { display: block } span, sub, sup { display: inline } " +
        ".up { vertical-align: 4px } .down { vertical-align: -3px } .up2 { vertical-align: 2px } .sub { vertical-align: sub } " +
        "sub { vertical-align: sub; font-size: 0.75em } sup { vertical-align: super; font-size: 0.75em }"

    private fun ui(content: NodeBuilder.() -> Unit): Pair<UiRoot, List<PaintCommand>> {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT)), clock = { 0L })
        root.render(VComponent(component("T") { div { content() } }, Unit, null))
        return root to root.frame(200f, 200f)
    }

    private fun UiRoot.line() = document.body.querySelector("div")!!.box.paragraphs.single().lines.single()
    private fun List<PaintCommand>.textY(t: String) = filterIsInstance<PaintCommand.Text>().single { it.text == t }.y

    @Test
    fun raisedTextGrowsTheLineAndIsDrawnHigher() {
        val (root, cmds) = ui { +"a"; span(className = "up") { +"b" } }
        val line = root.line()
        assertEquals(11f, line.baseline) // 7 + 4: the raised text needs room above
        assertEquals(14f, line.height)
        assertEquals(11f - 6f, cmds.textY("a"))
        assertEquals(11f - 4f - 6f, cmds.textY("b"))
    }

    @Test
    fun loweredTextGrowsTheLineBelow() {
        val (root, cmds) = ui { +"a"; span(className = "down") { +"b" } }
        val line = root.line()
        assertEquals(7f, line.baseline)
        assertEquals(13f, line.height) // 3 + 3 below
        assertEquals(7f + 3f - 6f, cmds.textY("b"))
    }

    @Test
    fun nestedShiftsAddUp() {
        val (_, cmds) = ui { +"a"; span(className = "up") { +"b"; span(className = "up2") { +"c" } } }
        assertEquals(cmds.textY("b") - 2f, cmds.textY("c"))
        assertEquals(cmds.textY("a") - 6f, cmds.textY("c"))
    }

    @Test
    fun subAndSupTags() {
        val (_, cmds) = ui { +"H"; sub { +"2" }; +"O x"; sup { +"2" } }
        val texts = cmds.filterIsInstance<PaintCommand.Text>()
        val base = texts.first { it.text == "H" }
        val (lowered, raised) = texts.filter { it.text == "2" }
        // Smaller font, below / above the baseline of "H" (compare baselines: top + ascent at the smaller size).
        assertTrue(lowered.style.fontSize < 8f && raised.style.fontSize < 8f)
        val baseline = base.y + 6f
        val k = lowered.style.fontSize / 8f
        assertTrue(lowered.y + 6f * k > baseline)
        assertTrue(raised.y + 6f * k < baseline)
    }

    @Test
    fun subKeywordUsesTheParentFont() {
        val (_, cmds) = ui { +"a"; span(className = "sub") { +"b" } }
        assertEquals(cmds.textY("a") + 8f * 0.2f, cmds.textY("b"), 0.001f)
    }
}
