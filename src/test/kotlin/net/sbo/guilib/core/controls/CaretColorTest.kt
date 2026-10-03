package net.sbo.guilib.core.controls

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.ComponentScope
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.input
import net.sbo.guilib.core.dsl.textarea
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `caret-color` colours the caret of inputs and textareas; `auto` keeps the text colour. */
class CaretColorTest {
    private val ua = """
        div { display: block }
        input { display: inline-block; position: relative; width: 80px; font-size: 10px; color: #ffffff }
        textarea { display: block; position: relative; width: 50px; height: 40px; font-size: 10px; color: #ffffff }
        .guilib-caret { position: absolute; width: 1px; background-color: currentColor }
        .guilib-selection { display: none }
    """.trimIndent()

    private var now = 0L

    private fun ui(css: String, content: ComponentScope.() -> Unit): UiRoot {
        val root = UiRoot(
            FakeMeasurer,
            listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT), Stylesheet.parse(css, "t", Origin.AUTHOR)),
            clock = { now },
        )
        root.render(VComponent(component("T") { content() }, Unit, null))
        frames(root)
        return root
    }

    private fun frames(root: UiRoot) = repeat(2) { root.frame(300f, 200f) }

    private fun Element.caret(): Element = descendants().first { "guilib-caret" in it.classList }

    @Test
    fun caretTakesCaretColorAndAutoKeepsTheTextColor() {
        val root = ui(".gold { caret-color: #e0b04a } .wrap { caret-color: red } .wrap .reset { caret-color: auto }") {
            input(className = "gold")
            input(className = "plain")
            textarea(className = "gold")
            div(className = "wrap") {
                input(className = "inherits")
                input(className = "reset")
            }
        }
        val body = root.document.body
        fun caret(sel: String) = body.querySelector(sel)!!.caret().style.backgroundColor
        assertEquals(0xFFE0B04A.toInt(), caret("input.gold"))
        assertEquals(0xFFE0B04A.toInt(), caret("textarea.gold"))
        assertEquals(0xFFFFFFFF.toInt(), caret("input.plain"))
        assertEquals(0xFFFF0000.toInt(), caret("input.inherits")) // inherited
        assertEquals(0xFFFFFFFF.toInt(), caret("input.reset"))
        assertNull(body.querySelector("input.plain")!!.style.caretColor)
    }

    @Test
    fun caretColorTransitions() {
        val root = ui(".a { caret-color: #000000; transition: caret-color 100ms linear } .a:focus { caret-color: #ffffff }") {
            input(className = "a")
        }
        val a = root.document.body.querySelector("input.a")!!
        a.focus()
        frames(root)
        now += 50
        frames(root)
        val mid = a.caret().style.backgroundColor and 0xFF
        assert(mid in 0x60..0xA0) { "caret colour mid-transition was ${mid.toString(16)}" }
        now += 100
        frames(root)
        assertEquals(0xFFFFFFFF.toInt(), a.caret().style.backgroundColor)
    }
}
