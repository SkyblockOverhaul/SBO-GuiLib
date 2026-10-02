package net.sbo.guilib.core.controls

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.ComponentScope
import net.sbo.guilib.core.dsl.input
import net.sbo.guilib.core.dsl.textarea
import net.sbo.guilib.core.event.Modifiers
import net.sbo.guilib.core.layout.FakeMeasurer
import net.sbo.guilib.core.layout.Fragment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `text-align` on `input` / `textarea`: the text moves, and the caret, selection and clicks follow it. */
class TextAlignInputTest {
    // 10px font → 5px per character. Content boxes: input 76px (border-box, 2px padding), textarea 50px.
    private val ua = """
        div { display: block }
        span { display: inline }
        input { display: inline-block; position: relative; overflow: hidden; white-space: pre; padding: 2px; width: 80px; font-size: 10px }
        textarea { display: block; position: relative; width: 50px; height: 40px; font-size: 10px; overflow-x: hidden; overflow-y: auto }
        .guilib-textarea-line { white-space: pre }
        .guilib-caret, .guilib-selection { position: absolute; width: 1px }
        .guilib-selection { display: none }
    """.trimIndent()

    private fun ui(content: ComponentScope.() -> Unit): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT)), clock = { 0L })
        root.render(VComponent(component("T") { content() }, Unit, null))
        frames(root)
        return root
    }

    private fun frames(root: UiRoot) = repeat(2) { root.frame(300f, 200f) }

    private fun UiRoot.clickAt(x: Float, y: Float) {
        input.mouseDown(x, y, 0)
        input.mouseUp(x, y, 0)
        frames(this)
    }

    private fun UiRoot.key(key: String, mods: Modifiers = Modifiers.NONE) {
        input.keyDown(key, 0, mods)
        frames(this)
    }

    private fun Element.part(cls: String): Element = descendants().first { cls in it.classList }

    /** x of [part] relative to [host]'s border box. */
    private fun relX(host: Element, part: Element) = part.getBoundingClientRect().x - host.getBoundingClientRect().x

    /** x of the first text fragment drawn inside [lineOwner], relative to [host]'s border box. */
    private fun textX(host: Element, lineOwner: Element): Float {
        val p = lineOwner.box.paragraphs.first()
        val f = p.lines.first().fragments.first { it is Fragment.Text }
        return lineOwner.getBoundingClientRect().x - host.getBoundingClientRect().x - lineOwner.box.x + p.x + f.x
    }

    @Test
    fun inputCaretAndClicksFollowCenteredAndRightAlignedText() {
        for (align in listOf("center", "right")) {
            val root = ui { input(value = "abcd", style = "text-align: $align") }
            val el = root.document.body.querySelector("input")!!
            // The text itself is shifted: content width − 20px text.
            val room = el.box.contentWidth - 20f
            val shift = if (align == "center") room / 2f else room
            assertEquals(2f + shift, textX(el, el), 0.01f, align)
            val r = el.getBoundingClientRect()
            // A click on the gap between "b" and "c" puts the caret there.
            root.clickAt(r.x + 2f + shift + 10f, r.y + 3f)
            val control = el.control as InputControl
            assertEquals(2, control.caret, align)
            assertEquals(2f + shift + 10f, relX(el, el.part("guilib-caret")), 0.01f, align)
            root.key("End")
            assertEquals(2f + shift + 20f, relX(el, el.part("guilib-caret")), 0.01f, align)
            // The selection covers the shifted text.
            root.key("Home", Modifiers(shift = true))
            root.key("End", Modifiers(shift = true))
            root.key("ArrowLeft", Modifiers(shift = true))
            val sel = el.part("guilib-selection")
            assertEquals(2f + shift + 15f, relX(el, sel), 0.01f, align)
            assertEquals(5f, sel.box.width, 0.01f, align)
        }
    }

    @Test
    fun emptyCenteredInputPutsTheCaretInTheMiddle() {
        val root = ui { input(value = "", style = "text-align: center") }
        val el = root.document.body.querySelector("input")!!
        val r = el.getBoundingClientRect()
        root.clickAt(r.x + 5f, r.y + 3f)
        assertEquals(2f + el.box.contentWidth / 2f, relX(el, el.part("guilib-caret")), 0.01f)
    }

    @Test
    fun textareaLinesCaretAndClicksFollowTheAlignment() {
        val root = ui { textarea(value = "ab\ncdef", style = "text-align: right") }
        val el = root.document.body.querySelector("textarea")!!
        val lines = el.descendants().filter { "guilib-textarea-line" in it.classList }.toList()
        assertEquals(40f, textX(el, lines[0]), 0.01f)
        assertEquals(30f, textX(el, lines[1]), 0.01f)
        val r = el.getBoundingClientRect()
        // Click between "d" and "e" on the second line (12.5px lines).
        root.clickAt(r.x + 40f, r.y + 15f)
        val control = el.control as TextAreaControl
        assertEquals(5, control.caret)
        assertEquals(40f, relX(el, el.part("guilib-caret")), 0.01f)
        // Up keeps the visual x: "ab" spans 40..50, so x 40 is before the "a".
        root.key("ArrowUp")
        assertEquals(0, control.caret)
        assertEquals(40f, relX(el, el.part("guilib-caret")), 0.01f)
    }
}
