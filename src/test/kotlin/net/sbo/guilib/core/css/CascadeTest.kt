package net.sbo.guilib.core.css

import net.sbo.guilib.core.Log
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CascadeTest {
    private val ctx = StyleContext(400f, 200f)
    private val oldSink = Log.sink

    @BeforeEach
    fun quiet() {
        Log.sink = { _, _ -> }
    }

    @AfterEach
    fun restore() {
        Log.sink = oldSink
    }

    private fun engine(vararg css: String, ua: String? = null): StyleEngine {
        val sheets = buildList {
            ua?.let { add(Stylesheet.parse(it, "ua.css", Origin.USER_AGENT)) }
            css.forEachIndexed { i, s -> add(Stylesheet.parse(s, "sheet$i.css")) }
        }
        return StyleEngine(sheets)
    }

    private fun StyleEngine.style(el: Selectable, parent: ComputedStyle? = null, inline: String = "") =
        compute(el, if (inline.isEmpty()) emptyList() else CssParser.parseDeclarations(inline), parent, ctx)

    @Test
    fun specificityBeatsSourceOrder() {
        val e = engine("#x { color: red } .a { color: blue } div { color: green }")
        assertEquals(0xFFFF0000.toInt(), e.style(FakeElement("div", "x", "a")).color)
    }

    @Test
    fun laterRuleWinsOnEqualSpecificity() {
        val e = engine(".a { color: red } .b { color: blue }")
        assertEquals(0xFF0000FF.toInt(), e.style(FakeElement("div", classes = "a b")).color)
    }

    @Test
    fun laterStylesheetWinsOnEqualSpecificity() {
        val e = engine(".a { color: red }", ".a { color: blue }")
        assertEquals(0xFF0000FF.toInt(), e.style(FakeElement("div", classes = "a")).color)
    }

    @Test
    fun importantAndInlineOrdering() {
        val e = engine("#x { color: red } .a { color: blue !important }")
        val el = FakeElement("div", "x", "a")
        assertEquals(0xFF0000FF.toInt(), e.style(el).color)
        // inline beats normal author rules …
        assertEquals(0xFF00FF00.toInt(), engine("#x { color: red }").style(el, inline = "color: lime").color)
        // … but not !important ones, unless inline is !important too.
        assertEquals(0xFF0000FF.toInt(), e.style(el, inline = "color: lime").color)
        assertEquals(0xFF00FF00.toInt(), e.style(el, inline = "color: lime !important").color)
    }

    @Test
    fun authorBeatsUserAgent() {
        val e = engine("button { padding: 1px }", ua = "button { padding: 4px; display: inline-block }")
        val s = e.style(FakeElement("button"))
        assertEquals(Dim.Px(1f), s.paddingTop)
        assertEquals(Display.INLINE_BLOCK, s.display)
    }

    @Test
    fun inheritsInheritedPropertiesOnly() {
        val e = engine(".parent { color: red; padding: 5px; font-size: 10px } .child { }")
        val parent = e.style(FakeElement("div", classes = "parent"))
        val child = e.style(FakeElement("div", classes = "child"), parent)
        assertEquals(0xFFFF0000.toInt(), child.color)
        assertEquals(10f, child.fontSize)
        assertEquals(Dim.ZERO, child.paddingTop)
    }

    @Test
    fun cssWideKeywords() {
        val e = engine(".p { padding: 5px; color: red } .c { padding: inherit; color: initial }")
        val parent = e.style(FakeElement("div", classes = "p"))
        val child = e.style(FakeElement("div", classes = "c"), parent)
        assertEquals(Dim.Px(5f), child.paddingLeft)
        assertEquals(Colors.WHITE, child.color)
    }

    @Test
    fun resolvesRelativeUnits() {
        val e = engine(".p { font-size: 10px } .c { font-size: 1.5em; width: 2em; height: 50vh; margin-left: 2rem; max-width: 50% }")
        val parent = e.style(FakeElement("div", classes = "p"))
        val child = e.style(FakeElement("div", classes = "c"), parent)
        assertEquals(15f, child.fontSize)
        assertEquals(Dim.Px(30f), child.width)
        assertEquals(Dim.Px(100f), child.height)
        assertEquals(Dim.Px(16f), child.marginLeft) // rem uses the root font size (8px)
        assertEquals(Dim.Pct(50f), child.maxWidth)
    }

    @Test
    fun currentColorAndBorderStyle() {
        val e = engine(".a { color: red; border: 2px solid } .b { border-width: 3px }")
        val a = e.style(FakeElement("div", classes = "a"))
        assertEquals(0xFFFF0000.toInt(), a.borderTopColor)
        assertEquals(2f, a.borderTopWidth)
        // Without a border-style the used width is 0, like on the web.
        assertEquals(0f, e.style(FakeElement("div", classes = "b")).borderTopWidth)
    }

    @Test
    fun customPropertiesInheritAndFallBack() {
        val e = engine(
            """
            :root { --accent: #00ff00; --pad: 3px }
            .card { --accent: #0000ff; background-color: var(--accent); padding: var(--pad) var(--missing, 7px) }
            .text { color: var(--accent) }
            .broken { color: var(--nope) }
            """.trimIndent(),
        )
        val rootEl = FakeElement("div")
        val root = e.style(rootEl)
        val cardEl = rootEl.add(FakeElement("div", classes = "card"))
        val card = e.style(cardEl, root)
        assertEquals(0xFF0000FF.toInt(), card.backgroundColor)
        assertEquals(Dim.Px(3f), card.paddingTop)
        assertEquals(Dim.Px(7f), card.paddingRight)

        val text = e.style(cardEl.add(FakeElement("span", classes = "text")), card)
        assertEquals(0xFF0000FF.toInt(), text.color)

        // Invalid at computed-value time → unset → inherits the parent's color.
        val broken = e.style(FakeElement("span", classes = "broken"), text)
        assertEquals(text.color, broken.color)
    }

    @Test
    fun cyclicVariablesDoNotLoop() {
        val e = engine(".a { --x: var(--y); --y: var(--x); color: var(--x, red) }")
        assertEquals(0xFFFF0000.toInt(), e.style(FakeElement("div", classes = "a")).color)
    }

    @Test
    fun hoverStateChangesStyle() {
        val e = engine("button { background-color: #111 } button:hover { background-color: #222 }")
        val btn = FakeElement("button")
        assertEquals(0xFF111111.toInt(), e.style(btn).backgroundColor)
        btn.states += PseudoState.HOVER
        assertEquals(0xFF222222.toInt(), e.style(btn).backgroundColor)
    }

    @Test
    fun layoutDiffDetection() {
        val e = engine(".a { color: red } .b { color: blue } .c { width: 5px }")
        val a = e.style(FakeElement("div", classes = "a"))
        val b = e.style(FakeElement("div", classes = "b"))
        val c = e.style(FakeElement("div", classes = "c"))
        assertEquals(false, a.layoutDiffers(b))
        assertEquals(true, a.layoutDiffers(c))
    }
}
