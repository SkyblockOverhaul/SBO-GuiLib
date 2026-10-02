package net.sbo.guilib.core.css

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.ComponentScope
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.input
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.layout.FakeMeasurer
import net.sbo.guilib.core.layout.Fragment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PseudoElementTest {
    private fun ui(css: String, content: ComponentScope.() -> Unit): UiRoot {
        val ua = "div { display: block } span { display: inline } input { display: inline-block; width: 40px; height: 10px }"
        val root = UiRoot(
            FakeMeasurer,
            listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT), Stylesheet.parse(css, "t", Origin.AUTHOR)),
        )
        root.render(VComponent(component("T") { content() }, Unit, null))
        root.frame(200f, 200f)
        return root
    }

    private fun Element.laidOut() = box.paragraphs.flatMap { p -> p.lines.flatMap { it.fragments } }
        .filterIsInstance<Fragment.Text>().joinToString("") { it.text }

    @Test
    fun parsesBeforeAndAfter() {
        val s = Selector.parse(".a::before")[0]
        assertEquals("before", s.pseudoElement)
        assertEquals(Selector.CLASS_WEIGHT + 1, s.specificity)
        assertEquals("after", Selector.parse(".a:after")[0].pseudoElement) // old one-colon form
        val bare = Selector.parse("::after")[0]
        assertEquals("after", bare.pseudoElement)
        assertEquals(SimpleSelector.Universal, bare.subject.parts.single())
        assertEquals("after", Selector.parse(".list > ::after")[0].pseudoElement)
        assertEquals(".a:hover::before", Selector.parse(".a:hover::before")[0].toString())
        assertThrows(IllegalArgumentException::class.java) { Selector.parse(".a::placeholder") }
        assertThrows(IllegalArgumentException::class.java) { Selector.parse(".a::before .b") }
    }

    @Test
    fun generatesTextAroundTheChildren() {
        val root = ui(""".tag::before { content: "[" } .tag::after { content: "] " attr(title) }""") {
            div(className = "tag", title = "x") { +"hi" }
            div(className = "plain") { +"no" }
        }
        val tag = root.document.body.querySelector(".tag")!!
        assertEquals("[hi] x", tag.laidOut())
        assertEquals("no", root.document.body.querySelector(".plain")!!.laidOut())
        // Not part of the DOM API.
        assertEquals(1, tag.children.size)
        assertTrue(root.document.body.querySelectorAll("*").none { it.tagName.startsWith("::") })
    }

    @Test
    fun blockBoxTakesSpaceAndInheritsFromTheElement() {
        val root = ui(
            """
            .card { color: #ff0000 }
            .card::before { content: ""; display: block; height: 4px; background-color: #00ff00 }
            .card::after { content: "!"; color: #0000ff }
            .child { height: 10px }
            """,
        ) {
            div(className = "card") { div(className = "child") }
        }
        val card = root.document.body.querySelector(".card")!!
        val child = root.document.body.querySelector(".child")!!
        assertEquals(4f, child.box.y)
        assertEquals(0xFF00FF00.toInt(), card.pseudoBefore!!.style.backgroundColor)
        assertEquals(0xFFFF0000.toInt(), card.pseudoBefore!!.style.color) // inherited
        assertEquals(0xFF0000FF.toInt(), card.pseudoAfter!!.style.color)
        assertEquals(4f + 10f + 10f, card.box.height) // + the "!" line (font-size 8 × 1.25)
    }

    @Test
    fun followsTheElementsStateAndForwardsEvents() {
        var clicks = 0
        val root = ui(""".btn { width: 60px; height: 10px } .inner { height: 10px } .btn:hover::after { content: "!"; display: block; height: 10px }""") {
            div(className = "btn", onClick = { clicks++ }) { div(className = "inner") }
        }
        val btn = root.document.body.querySelector(".btn")!!
        assertNull(btn.pseudoAfter)
        root.input.mouseMove(5f, 5f); root.frame(200f, 200f)
        val after = btn.pseudoAfter!!
        assertEquals(10f, after.box.height)
        assertEquals(10f, after.box.y) // after the 10px child

        // The ::after box overflows below the button (y 10..20); clicking it clicks the button, like the web.
        root.input.mouseMove(5f, 15f)
        root.input.mouseDown(5f, 15f, 0); root.input.mouseUp(5f, 15f, 0); root.frame(200f, 200f)
        assertEquals(1, clicks)

        root.input.mouseMove(150f, 150f); root.frame(200f, 200f)
        assertNull(btn.pseudoAfter)
    }

    @Test
    fun noneRemovesTheBoxAndInputsGetNone() {
        val root = ui(""".x::before { content: "a" } .x.off::before { content: none } input::before { content: "b" }""") {
            var off by useState(false)
            div(className = if (off) "x off" else "x", onClick = { off = true }) { span { +"t" } }
            input()
        }
        val x = root.document.body.querySelector(".x")!!
        assertEquals("at", x.laidOut())
        root.input.mouseDown(1f, 1f, 0); root.input.mouseUp(1f, 1f, 0); root.frame(200f, 200f)
        assertNull(x.pseudoBefore)
        assertEquals("t", x.laidOut())
        assertNull(root.document.body.querySelector("input")!!.pseudoBefore)
    }

    @Test
    fun animatesLikeAnElement() {
        var now = 0L
        val root = UiRoot(
            FakeMeasurer,
            listOf(
                Stylesheet.parse("div { display: block }", "ua", Origin.USER_AGENT),
                Stylesheet.parse(
                    """
                    .dot::after { content: ""; display: block; height: 2px; opacity: 0; transition: opacity 100ms linear }
                    .dot.on::after { opacity: 1 }
                    """,
                    "t", Origin.AUTHOR,
                ),
            ),
            clock = { now },
        )
        var on: (Boolean) -> Unit = {}
        root.render(VComponent(component("T") {
            val (v, set) = useState(false)
            on = set
            div(className = if (v) "dot on" else "dot") {}
        }, Unit, null))
        root.frame(200f, 200f)
        val dot = root.document.body.querySelector(".dot")!!
        on(true); root.frame(200f, 200f)
        now += 50; root.frame(200f, 200f)
        assertEquals(0.5f, dot.pseudoAfter!!.style.opacity, 0.01f)
    }
}
