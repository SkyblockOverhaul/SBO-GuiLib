package net.sbo.guilib.core.layout

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.span
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FakeMeasurer at font-size 8: ascent 6, descent 2, normal line height 10 → the strut is 7 above and 3 below the
 * baseline. A 30px tall baseline-aligned box makes the line 33px high with the baseline at 30.
 */
class VerticalAlignTest {
    private val ua = "div { display: block } span { display: inline } .ib { display: inline-block; width: 10px }"

    private fun topOf(align: String, height: Float = 12f, tall: Boolean = true): Float {
        val css = ".tall { height: 30px } .x { height: ${height}px; vertical-align: $align }"
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT), Stylesheet.parse(css, "t.css")), clock = { 0L })
        root.render(VComponent(component("T") {
            div {
                +"a"
                if (tall) span(className = "ib tall") {}
                span(className = "ib x") {}
            }
        }, Unit, null))
        root.frame(200f, 200f)
        val div = root.document.body.querySelector("div")!!
        return root.document.body.querySelector(".x")!!.getBoundingClientRect().y - div.getBoundingClientRect().y
    }

    @Test
    fun keywordsAndLengths() {
        assertEquals(18f, topOf("baseline")) // bottom on the baseline (30 - 12)
        assertEquals(22f, topOf("middle")) // center 2px (half the x-height) above the baseline: 30 - 2 - 6
        assertEquals(24f, topOf("text-top")) // top at the parent's ascent: 30 - 6
        assertEquals(20f, topOf("text-bottom")) // bottom at the parent's descent: 30 + 2 - 12
        assertEquals(0f, topOf("top"))
        assertEquals(21f, topOf("bottom")) // 33 - 12
        assertEquals(13f, topOf("5px")) // raised by 5px
        assertEquals(23f, topOf("-5px"))
        assertTrue(topOf("super") < 18f)
        assertTrue(topOf("sub") > 18f)
    }

    @Test
    fun topAlignedBoxesTallerThanTheLineGrowIt() {
        val css = ".x { height: 30px; vertical-align: top }"
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT), Stylesheet.parse(css, "t.css")), clock = { 0L })
        root.render(VComponent(component("T") { div { +"a"; span(className = "ib x") {} } }, Unit, null))
        root.frame(200f, 200f)
        val line = root.document.body.querySelector("div")!!.box.paragraphs.single().lines.single()
        assertEquals(30f, line.height)
        assertEquals(7f, line.baseline) // the text stays at the top of the line
        assertEquals(0f, topOf("top", height = 30f, tall = false))
    }
}
