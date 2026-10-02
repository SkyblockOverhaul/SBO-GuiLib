package net.sbo.guilib.core.layout

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.paint.PaintCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** FakeMeasurer at font-size 8: every character is 4px wide. */
class LetterSpacingTest {
    private val ua = "div { display: block } span { display: inline }"

    private fun ui(css: String, width: Float = 200f, content: net.sbo.guilib.core.dsl.ComponentScope.() -> Unit): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT), Stylesheet.parse(css, "t.css")), clock = { 0L })
        root.render(VComponent(component("T") { content() }, Unit, null))
        root.frame(width, 100f)
        return root
    }

    private fun UiRoot.width(selector: String) = document.body.querySelector(selector)!!.getBoundingClientRect().width

    @Test
    fun spacingIsAddedAfterEveryCharacter() {
        val root = ui(".a { letter-spacing: 2px } .b { letter-spacing: 0.5em } .c { letter-spacing: -1px } .n { letter-spacing: normal }") {
            div { span(className = "a") { +"abc" } }
            div { span(className = "b") { +"abc" } }
            div { span(className = "c") { +"abc" } }
            div { span(className = "n") { +"abc" } }
        }
        assertEquals(18f, root.width(".a")) // 3 × (4 + 2)
        assertEquals(24f, root.width(".b")) // 0.5em of 8px = 4px
        assertEquals(9f, root.width(".c"))
        assertEquals(12f, root.width(".n"))
        val text = root.painter.commands.filterIsInstance<PaintCommand.Text>().first { it.text == "abc" }
        assertEquals(2f, text.style.letterSpacing)
    }

    @Test
    fun emIsResolvedWhereDeclaredAndInherited() {
        // Like CSS: the child inherits the computed 4px, not 0.5em of its own 16px font.
        val root = ui(".p { letter-spacing: 0.5em } .big { font-size: 16px }") {
            div(className = "p") { span(className = "big") { +"ab" } }
        }
        assertEquals(2 * (8f + 4f), root.width(".big"))
    }

    @Test
    fun spacingAffectsWrapping() {
        val root = ui(".w { letter-spacing: 4px; width: 40px }") {
            div(className = "w") { +"aaa bbb" }
        }
        // "aaa " is 4 × 8 = 32px, "aaa bbb" would be 56px → two lines.
        assertEquals(2, root.document.body.querySelector(".w")!!.box.paragraphs.single().lines.size)
    }
}
