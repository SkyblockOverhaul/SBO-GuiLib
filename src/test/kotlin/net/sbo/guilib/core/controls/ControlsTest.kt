package net.sbo.guilib.core.controls

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.PseudoState
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.TextNode
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.ComponentScope
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.checkbox
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.input
import net.sbo.guilib.core.dsl.modal
import net.sbo.guilib.core.dsl.select
import net.sbo.guilib.core.css.Display
import net.sbo.guilib.core.event.Modifiers
import net.sbo.guilib.core.layout.FakeMeasurer
import net.sbo.guilib.core.layout.Fragment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlsTest {
    private var now = 0L

    // The relevant parts of the real ua.css.
    private val ua = """
        div, label { display: block }
        span { display: inline }
        button, select { display: inline-flex }
        input { display: inline-block; position: relative; overflow: hidden; white-space: pre; padding: 2px; width: 80px }
        input[type=checkbox] { width: 10px; height: 10px; padding: 0 }
        .guilib-caret, .guilib-selection { position: absolute; width: 1px }
        .guilib-selection { display: none }
        #guilib-overlay { position: fixed; left: 0; top: 0; width: 100%; height: 100%; pointer-events: none; z-index: 100 }
        .guilib-portal { position: absolute; left: 0; top: 0; width: 100%; height: 100%; pointer-events: none }
        .guilib-portal > * { pointer-events: auto }
        .guilib-modal-backdrop { position: fixed; left: 0; top: 0; width: 100%; height: 100% }
        .guilib-option { height: 10px }
    """.trimIndent()

    private fun ui(content: ComponentScope.() -> Unit): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT)), clock = { now })
        root.render(VComponent(component("T") { content() }, Unit, null))
        root.frame(300f, 200f)
        return root
    }

    private fun UiRoot.click(el: Element) {
        val r = el.getBoundingClientRect()
        input.mouseDown(r.x + 1f, r.y + 1f, 0)
        input.mouseUp(r.x + 1f, r.y + 1f, 0)
        frame(300f, 200f)
    }

    private fun UiRoot.type(text: String) {
        for (c in text) input.charTyped(c.toString())
        frame(300f, 200f)
    }

    private fun UiRoot.key(key: String, mods: Modifiers = Modifiers.NONE) {
        input.keyDown(key, 0, mods)
        frame(300f, 200f)
    }

    private fun Element.shownText(): String = descendants().flatMap { it.children.asSequence() }.filterIsInstance<TextNode>().joinToString("") { it.data }

    @Test
    fun uncontrolledInputKeepsItsOwnText() {
        var changes = 0
        val root = ui { input(placeholder = "Name", onChange = { changes++ }) }
        val el = root.document.body.querySelector("input")!!
        assertEquals("Name", el.shownText())
        root.click(el)
        assertTrue(el.isFocused)
        root.type("abc")
        assertEquals("abc", el.shownText())
        assertEquals(3, changes)
        root.key("Backspace")
        assertEquals("ab", el.shownText())
        root.key("ArrowLeft")
        root.type("X")
        assertEquals("aXb", el.shownText())
    }

    @Test
    fun controlledInputOnlyChangesThroughState() {
        lateinit var current: () -> String
        val root = ui {
            var name by useState("hi")
            current = { name }
            input(value = name, onChange = { name = it.value.uppercase() })
        }
        val el = root.document.body.querySelector("input")!!
        root.click(el)
        root.key("End")
        root.type("x")
        assertEquals("HIX", current())
        assertEquals("HIX", el.shownText())

        // A controlled input without a handler rejects edits (like React).
        val fixed = ui { input(value = "locked") }
        val f = fixed.document.body.querySelector("input")!!
        fixed.click(f)
        fixed.type("zz")
        assertEquals("locked", f.shownText())
    }

    @Test
    fun selectionClipboardAndMaxLength() {
        val root = ui { input(maxLength = 5) }
        val el = root.document.body.querySelector("input")!!
        root.click(el)
        root.type("hello world")
        assertEquals("hello", el.shownText())
        root.key("a", Modifiers(ctrl = true))
        root.key("c", Modifiers(ctrl = true))
        assertEquals("hello", root.document.clipboard.get())
        root.key("Delete")
        assertEquals("", el.shownText().trim())
        root.key("v", Modifiers(ctrl = true))
        assertEquals("hello", el.shownText())
    }

    @Test
    fun selectionIsVisibleWhileSelecting() {
        val root = ui { input() }
        val el = root.document.body.querySelector("input")!!
        val selection = el.descendants().first { it.className == "guilib-selection" }
        root.click(el)
        root.type("hello")
        assertEquals(Display.NONE, selection.style.display)
        root.key("Home", Modifiers(shift = true))
        root.frame(300f, 200f)
        assertTrue(selection.style.display != Display.NONE)
        assertEquals(5 * el.style.fontSize / 2f, selection.box.width, 0.01f) // FakeMeasurer: fontSize/2 per char
        root.key("ArrowRight")
        root.frame(300f, 200f)
        assertEquals(Display.NONE, selection.style.display)
    }

    @Test
    fun formattingCodesAreLiteralInInputs() {
        val root = ui {
            input(className = "in")
            div(className = "label") { +"§aHi" }
        }
        val el = root.document.body.querySelector(".in")!!
        root.click(el)
        root.type("§aHi")
        root.frame(300f, 200f)
        fun Element.laidOut() = box.paragraphs.flatMap { p -> p.lines.flatMap { it.fragments } }
            .filterIsInstance<Fragment.Text>().joinToString("") { it.text }
        assertEquals("§aHi", el.laidOut())
        // Normal text still applies the codes.
        assertEquals("Hi", root.document.body.querySelector(".label")!!.laidOut())
    }

    @Test
    fun passwordAndNumberTypes() {
        val root = ui {
            input(type = "password", className = "pw")
            input(type = "number", className = "num")
        }
        val pw = root.document.body.querySelector(".pw")!!
        root.click(pw)
        root.type("abc")
        assertEquals("•••", pw.shownText())
        val num = root.document.body.querySelector(".num")!!
        root.click(num)
        root.type("1a2.5")
        assertEquals("12.5", num.shownText())
    }

    @Test
    fun checkboxTogglesControlledAndUncontrolled() {
        var checked = false
        val root = ui {
            var on by useState(false)
            checked = on
            checkbox(checked = on, onChange = { on = it.checked }, label = "Enabled")
            div(className = "free") { input(type = "checkbox") }
        }
        val box = root.document.body.querySelector("label input")!!
        root.click(box)
        assertTrue(checked)
        assertTrue(box.hasState(PseudoState.CHECKED))
        // Clicking the label text toggles too.
        root.click(root.document.body.querySelector("label span")!!)
        assertFalse(checked)

        val free = root.document.body.querySelector(".free input")!!
        root.click(free)
        assertTrue(free.hasState(PseudoState.CHECKED))
    }

    @Test
    fun selectOpensMenuInPortalAndChooses() {
        var value = "b"
        val root = ui {
            var v by useState("b")
            value = v
            div(style = "height: 20px; overflow: hidden") {
                select(value = v, onChange = { v = it.value }) {
                    option("a") { +"Alpha" }
                    option("b", "Beta")
                    option("c", "Gamma", disabled = true)
                }
            }
        }
        val sel = root.document.body.querySelector("select")!!
        assertEquals("Beta▾", sel.shownText())
        root.click(sel)
        val menu = root.document.overlayRoot.querySelector(".guilib-select-menu")
        assertNotNull(menu, "menu should render in the overlay layer")
        val options = menu!!.querySelectorAll(".guilib-option")
        assertEquals(3, options.size)
        root.click(options[2]) // disabled
        assertEquals("b", value)
        root.click(sel)
        root.click(root.document.overlayRoot.querySelectorAll(".guilib-option")[0])
        assertEquals("a", value)
        assertNull(root.document.overlayRoot.querySelector(".guilib-select-menu"))
    }

    @Test
    fun selectKeyboardAndOutsideClick() {
        var value = "a"
        val root = ui {
            var v by useState("a")
            value = v
            select(value = v, onChange = { v = it.value }) { option("a", "A"); option("b", "B") }
            button(className = "other") { +"x" }
        }
        val sel = root.document.body.querySelector("select")!!
        root.click(sel)
        root.key("ArrowDown")
        root.key("Enter")
        assertEquals("b", value)
        root.click(sel)
        assertNotNull(root.document.overlayRoot.querySelector(".guilib-select-menu"))
        root.click(root.document.body.querySelector(".other")!!)
        assertNull(root.document.overlayRoot.querySelector(".guilib-select-menu"))
    }

    @Test
    fun modalClosesOnEscapeAndBackdrop() {
        var open = true
        val root = ui {
            var o by useState(true)
            open = o
            button(className = "open", onClick = { o = true }) { +"open" }
            modal(open = o, onClose = { o = false }) { div(className = "content", style = "width: 50px; height: 20px") { +"Hi" } }
        }
        assertNotNull(root.document.overlayRoot.querySelector(".content"))
        // Escape is swallowed by the modal (the screen must not close).
        assertTrue(root.input.keyDown("Escape", 256))
        root.frame(300f, 200f)
        assertFalse(open)
        assertNull(root.document.overlayRoot.querySelector(".content"))

        root.click(root.document.body.querySelector(".open")!!)
        assertTrue(open)
        // Clicking the content keeps it open, clicking the backdrop closes it.
        root.click(root.document.overlayRoot.querySelector(".content")!!)
        assertTrue(open)
        root.input.mouseDown(299f, 199f, 0); root.input.mouseUp(299f, 199f, 0); root.frame(300f, 200f)
        assertFalse(open)
    }

    @Test
    fun portalContentIsPaintedAboveAndHitFirst() {
        val root = ui {
            div(className = "under", style = "width: 100px; height: 100px")
            portal { div(className = "over", style = "position: fixed; left: 10px; top: 10px; width: 20px; height: 20px") }
        }
        assertEquals("over", root.painter.hitTest(15f, 15f)?.className)
        assertEquals("under", root.painter.hitTest(50f, 50f)?.className)
    }

    @Test
    fun autoFocusFocusesOnMount() {
        val root = ui { input(className = "a"); input(className = "b", autoFocus = true) }
        root.frame(300f, 200f)
        assertEquals("b", root.document.focusedElement?.className)
    }

    @Test
    fun titleShowsTooltipAfterDelay() {
        val root = ui { div(className = "t", title = "Hello", style = "width: 50px; height: 20px") }
        root.input.mouseMove(5f, 5f)
        root.frame(300f, 200f)
        assertNull(root.document.overlayRoot.querySelector(".guilib-tooltip"))
        now += 600
        root.frame(300f, 200f)
        root.frame(300f, 200f)
        val tip = root.document.overlayRoot.querySelector(".guilib-tooltip")
        assertNotNull(tip)
        assertEquals("Hello", (tip!!.children.single() as TextNode).data)
    }
}
