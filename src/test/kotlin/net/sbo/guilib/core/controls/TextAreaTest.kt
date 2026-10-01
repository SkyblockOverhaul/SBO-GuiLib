package net.sbo.guilib.core.controls

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.ComponentScope
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.textarea
import net.sbo.guilib.core.dsl.useClipboard
import net.sbo.guilib.core.event.Modifiers
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextAreaTest {
    // 10px font → 5px per character and 12.5px lines; the text area fits 10 characters per line and 2 lines.
    private val ua = """
        div { display: block }
        textarea { display: block; position: relative; width: 50px; font-size: 10px; overflow-x: hidden; overflow-y: auto }
        .guilib-textarea-line { white-space: pre }
        .guilib-caret, .guilib-selection { position: absolute }
    """.trimIndent()

    private fun ui(content: ComponentScope.() -> Unit): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT)), clock = { 0L })
        root.render(VComponent(component("T") { content() }, Unit, null))
        frames(root)
        return root
    }

    private fun frames(root: UiRoot) = repeat(2) { root.frame(300f, 200f) }

    private val UiRoot.area: Element get() = document.body.querySelector("textarea")!!
    private val UiRoot.control: TextAreaControl get() = area.control as TextAreaControl

    private fun UiRoot.type(text: String) {
        for (c in text) if (c == '\n') input.keyDown("Enter", 0) else input.charTyped(c.toString())
        frames(this)
    }

    private fun UiRoot.key(key: String, mods: Modifiers = Modifiers.NONE) {
        input.keyDown(key, 0, mods)
        frames(this)
    }

    private fun UiRoot.lines() = control.lines.map { control.text.substring(it.start, it.end) }

    private fun UiRoot.focus() {
        val r = area.getBoundingClientRect()
        input.mouseDown(r.x + 1f, r.y + 1f, 0)
        input.mouseUp(r.x + 1f, r.y + 1f, 0)
        frames(this)
    }

    @Test
    fun wrapsWordsAndBreaksLongWords() {
        val root = ui { textarea(value = "hello world foo\n\nabcdefghijklmnop", style = "height: 25px") }
        assertEquals(listOf("hello ", "world foo", "", "abcdefghij", "klmnop"), root.lines())
        val shown = root.document.body.querySelectorAll(".guilib-textarea-line")
        assertEquals(5, shown.size)
        // Lines stack at the line height.
        assertEquals(12.5f, shown[1].box.y - shown[0].box.y, 0.01f)
    }

    @Test
    fun maxLinesBlocksEnterAndFlattensPastedBreaks() {
        var value = ""
        val root = ui {
            var v by useState("")
            value = v
            textarea(value = v, onChange = { v = it.value }, maxLines = 3)
        }
        root.focus()
        root.type("a\nb\nc\n\nd")
        assertEquals("a\nb\ncd", value)
        // Pasted line breaks beyond the limit become spaces; breaks in the replaced selection don't count.
        root.key("a", Modifiers(ctrl = true))
        root.document.clipboard.set("1\n2\n3\n4\n5")
        root.key("v", Modifiers(ctrl = true))
        assertEquals("1\n2\n3 4 5", value)
    }

    @Test
    fun useClipboardWritesTheDocumentClipboard() {
        val root = ui {
            val clipboard = useClipboard()
            button(onClick = { clipboard.set("note text") }) { +"Copy" }
        }
        val r = root.document.body.querySelector("button")!!.getBoundingClientRect()
        root.input.mouseDown(r.x + 1f, r.y + 1f, 0)
        root.input.mouseUp(r.x + 1f, r.y + 1f, 0)
        assertEquals("note text", root.document.clipboard.get())
    }

    @Test
    fun typingEnterAndControlledValue() {
        var value = ""
        val root = ui {
            var v by useState("")
            value = v
            textarea(value = v, onChange = { v = it.value }, maxLength = 20)
        }
        root.focus()
        root.type("Party\nfor M7")
        assertEquals("Party\nfor M7", value)
        assertEquals(listOf("Party", "for M7"), root.lines())
        root.type(" runs and more text")
        assertEquals(20, value.length)
    }

    @Test
    fun arrowsMoveBetweenLinesAndKeepTheColumn() {
        val root = ui {
            var v by useState("abcdefgh\nab\nabcdefgh")
            textarea(value = v, onChange = { v = it.value }, style = "height: 100px")
        }
        root.focus()
        root.key("End", Modifiers(ctrl = true))
        assertEquals(20, root.control.caret)
        root.key("Home")
        root.key("ArrowRight"); root.key("ArrowRight"); root.key("ArrowRight"); root.key("ArrowRight")
        assertEquals(16, root.control.caret) // column 4 of line 3
        root.key("ArrowUp")
        assertEquals(11, root.control.caret) // short line: its end
        root.key("ArrowUp")
        assertEquals(4, root.control.caret) // back at column 4
        root.key("ArrowDown", Modifiers(shift = true))
        root.type("X")
        assertEquals("abcdX\nabcdefgh", root.control.text)
    }

    @Test
    fun clickPlacesTheCaretAndTheCaretStaysVisible() {
        val root = ui {
            var v by useState("")
            textarea(value = v, onChange = { v = it.value }, style = "height: 25px")
        }
        root.focus()
        root.type("one\ntwo\nthree\nfour")
        // Four lines in a two-line box: scrolled to the caret line.
        assertEquals(25f, root.area.scrollTop, 0.01f)
        val r = root.area.getBoundingClientRect()
        // Second visible line ("four"), after "fo".
        root.input.mouseDown(r.x + 10f, r.y + 12.5f + 6f, 0)
        root.input.mouseUp(r.x + 10f, r.y + 12.5f + 6f, 0)
        frames(root)
        assertEquals("one\ntwo\nthree\nfo".length, root.control.caret)
        val caret = root.document.body.querySelector(".guilib-caret")!!
        assertTrue(caret.inlineStyle!!.contains("top: 37.5px"), caret.inlineStyle)
    }
}
