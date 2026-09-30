package net.sbo.guilib.core.layout

import net.sbo.guilib.core.css.ComputedStyle
import net.sbo.guilib.core.css.CssParser
import net.sbo.guilib.core.css.PseudoState
import net.sbo.guilib.core.css.Selectable
import net.sbo.guilib.core.css.StyleContext
import net.sbo.guilib.core.css.StyleEngine
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.css.Origin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Monospace fake: every char is fontSize/2 wide; ascent .75em, descent .25em, normal line height 1.25em. */
object FakeMeasurer : TextMeasurer {
    override fun width(text: String, style: TextStyle) = text.length * style.fontSize / 2f
    override fun metrics(style: TextStyle) = FontMetrics(style.fontSize * 0.75f, style.fontSize * 0.25f, style.fontSize * 1.25f)
}

class TestNode(
    val tag: String,
    private val classes: String = "",
    private val inlineStyle: String = "",
    override val textContent: String? = null,
    override val intrinsicWidth: Float? = null,
    override val intrinsicHeight: Float? = null,
) : Selectable, LayoutNode {
    val children = ArrayList<TestNode>()
    var parent: TestNode? = null
    override lateinit var style: ComputedStyle
    override val box = LayoutBox()

    override val layoutChildren: List<LayoutNode> get() = children
    override val isLineBreak get() = tag == "br"
    override val styleTag get() = tag
    override val styleId: String? get() = null
    override val styleClasses get() = classes.split(' ').filter { it.isNotBlank() }
    override val styleParent get() = parent
    override val stylePreviousSibling get() = parent?.children?.let { it.getOrNull(it.indexOf(this) - 1) }
    override val styleNextSibling get() = parent?.children?.let { it.getOrNull(it.indexOf(this) + 1) }
    override fun hasState(state: PseudoState) = false

    fun computeStyles(engine: StyleEngine, parentStyle: ComputedStyle?, ctx: StyleContext) {
        style = if (textContent != null) parentStyle!! else engine.compute(this, CssParser.parseDeclarations(inlineStyle), parentStyle, ctx)
        children.forEach { it.computeStyles(engine, style, ctx) }
    }

    /** Absolute x of the border box. */
    val absX: Float get() = box.x + (parent?.absX ?: 0f)
    val absY: Float get() = box.y + (parent?.absY ?: 0f)
}

class LayoutTest {
    private val ua = """
        div, p { display: block }
        span { display: inline }
        .flex { display: flex }
        .col { display: flex; flex-direction: column }
    """.trimIndent()

    private fun el(tag: String, cls: String = "", style: String = "", vararg kids: TestNode) =
        TestNode(tag, cls, style).also { p -> kids.forEach { it.parent = p; p.children += it } }

    private fun div(style: String = "", vararg kids: TestNode) = el("div", "", style, *kids)
    private fun text(t: String) = TestNode("#text", textContent = t)

    private fun layout(root: TestNode, w: Float = 100f, h: Float = 100f, css: String = ""): TestNode {
        val engine = StyleEngine(listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT), Stylesheet.parse(css, "test")))
        val ctx = StyleContext(w, h)
        root.computeStyles(engine, null, ctx)
        LayoutEngine(FakeMeasurer).layout(root, w, h)
        return root
    }

    private fun assertBox(n: TestNode, x: Float, y: Float, w: Float, h: Float) {
        val msg = "expected ($x, $y, $w×$h) but was (${n.absX}, ${n.absY}, ${n.box.width}×${n.box.height})"
        assertEquals(x, n.absX, 0.01f, msg)
        assertEquals(y, n.absY, 0.01f, msg)
        assertEquals(w, n.box.width, 0.01f, msg)
        assertEquals(h, n.box.height, 0.01f, msg)
    }

    @Test
    fun rootFillsViewport() {
        val root = layout(div(), 320f, 240f)
        assertBox(root, 0f, 0f, 320f, 240f)
    }

    @Test
    fun blocksStackVerticallyAndFillWidth() {
        val a = div("height: 10px")
        val b = div("height: 20px; margin: 5px")
        val c = div("height: 5px")
        layout(div("", a, b, c))
        assertBox(a, 0f, 0f, 100f, 10f)
        assertBox(b, 5f, 15f, 90f, 20f) // no margin collapsing
        assertBox(c, 0f, 40f, 100f, 5f)
    }

    @Test
    fun borderBoxIsDefaultAndContentBoxIsSupported() {
        val a = div("width: 50px; height: 20px; padding: 5px; border: 2px solid red")
        val b = div("box-sizing: content-box; width: 50px; height: 20px; padding: 5px; border: 2px solid red")
        layout(div("", a, b))
        assertBox(a, 0f, 0f, 50f, 20f)
        assertEquals(36f, a.box.contentWidth, 0.01f)
        assertBox(b, 0f, 20f, 64f, 34f)
    }

    @Test
    fun autoMarginsCenterBlocks() {
        val a = div("width: 40px; height: 10px; margin: 0 auto")
        layout(div("", a))
        assertBox(a, 30f, 0f, 40f, 10f)
    }

    @Test
    fun percentagesResolveAgainstContainingBlock() {
        val inner = div("width: 50%; height: 25%")
        val outer = div("width: 80px; height: 40px; padding: 10px", inner)
        layout(div("", outer))
        assertBox(inner, 10f, 10f, 30f, 5f)
    }

    @Test
    fun autoHeightWrapsChildren() {
        val a = div("height: 7px")
        val outer = div("padding: 3px", a)
        layout(div("", outer))
        assertBox(outer, 0f, 0f, 100f, 13f)
    }

    @Test
    fun minAndMaxWidth() {
        val a = div("width: 10px; min-width: 30px; height: 1px")
        val b = div("max-width: 60px; height: 1px")
        layout(div("", a, b))
        assertEquals(30f, a.box.width)
        assertEquals(60f, b.box.width)
    }

    // ---- flex ----

    @Test
    fun flexGrowDistributesFreeSpace() {
        val a = div("flex: 1; height: 10px")
        val b = div("flex: 3; height: 10px")
        layout(div("display: flex; gap: 4px", a, b))
        assertBox(a, 0f, 0f, 24f, 10f)
        assertBox(b, 28f, 0f, 72f, 10f)
    }

    @Test
    fun flexItemsStretchInCrossAxis() {
        val a = div("width: 10px")
        val b = div("width: 10px; height: 30px")
        val row = div("display: flex", a, b)
        layout(div("", row))
        assertEquals(30f, row.box.height)
        assertEquals(30f, a.box.height) // stretched
        assertEquals(30f, b.box.height)
    }

    @Test
    fun justifyContentAndAlignItems() {
        val a = div("width: 10px; height: 10px")
        val b = div("width: 20px; height: 20px")
        val c = div("width: 10px; height: 10px")
        layout(div("", div("display: flex; justify-content: space-between; align-items: center; height: 40px", a, b, c)))
        assertBox(a, 0f, 15f, 10f, 10f)
        assertBox(b, 40f, 10f, 20f, 20f)
        assertBox(c, 90f, 15f, 10f, 10f)
    }

    @Test
    fun justifyCenterAndSpaceEvenly() {
        val a = div("width: 20px; height: 1px")
        val b = div("width: 20px; height: 1px")
        layout(div("display: flex; justify-content: center", a, b))
        assertEquals(30f, a.absX, 0.01f)
        assertEquals(50f, b.absX, 0.01f)

        val c = div("width: 20px; height: 1px")
        val d = div("width: 20px; height: 1px")
        layout(div("display: flex; justify-content: space-evenly", c, d))
        assertEquals(20f, c.absX, 0.01f)
        assertEquals(60f, d.absX, 0.01f)
    }

    @Test
    fun flexShrinkRespectsMinWidth() {
        val a = div("width: 80px; height: 1px")
        val b = div("width: 80px; min-width: 70px; height: 1px")
        layout(div("display: flex", a, b))
        assertEquals(70f, b.box.width, 0.01f)
        assertEquals(30f, a.box.width, 0.01f)
    }

    @Test
    fun flexShrinkIsWeightedByBaseSize() {
        val a = div("width: 100px; height: 1px; min-width: 0")
        val b = div("width: 50px; height: 1px; min-width: 0")
        layout(div("display: flex", a, b))
        // overflow 50px, shrink weighted 100:50
        assertEquals(66.67f, a.box.width, 0.01f)
        assertEquals(33.33f, b.box.width, 0.01f)
    }

    @Test
    fun columnFlexWithGrowAndFixedHeight() {
        val header = div("height: 10px")
        val body = div("flex-grow: 1")
        val footer = div("height: 10px")
        val col = div("display: flex; flex-direction: column; height: 100px", header, body, footer)
        layout(div("", col))
        assertBox(header, 0f, 0f, 100f, 10f)
        assertBox(body, 0f, 10f, 100f, 80f)
        assertBox(footer, 0f, 90f, 100f, 10f)
    }

    @Test
    fun marginAutoPushesFlexItems() {
        val a = div("width: 10px; height: 1px")
        val b = div("width: 10px; height: 1px; margin-left: auto")
        layout(div("display: flex", a, b))
        assertEquals(0f, a.absX)
        assertEquals(90f, b.absX, 0.01f)
    }

    @Test
    fun rowReverseAndOrder() {
        val a = div("width: 10px; height: 1px")
        val b = div("width: 10px; height: 1px")
        layout(div("display: flex; flex-direction: row-reverse", a, b))
        assertEquals(90f, a.absX, 0.01f)
        assertEquals(80f, b.absX, 0.01f)

        val c = div("width: 10px; height: 1px; order: 2")
        val d = div("width: 10px; height: 1px")
        layout(div("display: flex", c, d))
        assertEquals(10f, c.absX, 0.01f)
        assertEquals(0f, d.absX, 0.01f)
    }

    @Test
    fun flexWrapCreatesLines() {
        val kids = Array(3) { div("width: 40px; height: 10px") }
        val row = div("display: flex; flex-wrap: wrap; row-gap: 2px", *kids)
        layout(div("", row))
        assertEquals(0f, kids[0].absY)
        assertEquals(0f, kids[1].absY)
        assertEquals(12f, kids[2].absY, 0.01f)
        assertEquals(22f, row.box.height, 0.01f)
    }

    @Test
    fun nestedFlexShrinkToContent() {
        // A row item without width takes its max-content size.
        val label = text("abcd") // 4 chars * 4px = 16px
        val item = div("padding: 2px", label)
        layout(div("display: flex", item))
        assertEquals(20f, item.box.width, 0.01f)
    }

    // ---- text ----

    @Test
    fun textWrapsAtWordBoundaries() {
        val t = text("hello world foo")
        val p = div("width: 48px", t) // 12 chars per line
        layout(div("", p))
        val para = p.box.paragraphs.single()
        assertEquals(listOf("hello world", "foo"), para.lines.map { l -> l.fragments.joinToString("") { (it as Fragment.Text).text } })
        assertEquals(20f, p.box.height, 0.01f) // 2 lines * 10px
        assertTrue(t.box.inParagraph)
    }

    @Test
    fun longWordOverflowsInsteadOfBreaking() {
        val p = div("width: 8px", text("abcdef"))
        layout(div("", p))
        assertEquals(1, p.box.paragraphs.single().lines.size)
        assertEquals(24f, p.box.scrollWidth, 0.01f)
    }

    @Test
    fun whitespaceCollapses() {
        val p = div("", text("  a   b  "))
        layout(div("", p))
        val frag = p.box.paragraphs.single().lines.single().fragments.single() as Fragment.Text
        assertEquals("a b", frag.text)
    }

    @Test
    fun preservesNewlinesInPre() {
        val p = div("white-space: pre", text("a\nb"))
        layout(div("", p))
        assertEquals(2, p.box.paragraphs.single().lines.size)
    }

    @Test
    fun ellipsisTruncatesOverflowingText() {
        val p = div("width: 40px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis", text("abcdefghijklmnop"))
        layout(div("", p))
        val line = p.box.paragraphs.single().lines.single()
        val content = line.fragments.joinToString("") { (it as Fragment.Text).text }
        assertEquals("abcdefghi…", content) // 9 chars + ellipsis = 10 * 4px
        assertTrue(line.width <= 40f)
    }

    @Test
    fun textAlignCenter() {
        val p = div("text-align: center", text("abcd"))
        layout(div("", p))
        val frag = p.box.paragraphs.single().lines.single().fragments.single()
        assertEquals(42f, frag.x, 0.01f) // (100 - 16) / 2
    }

    @Test
    fun inlineSpansJoinParagraphAndFormattingCodesAreStripped() {
        val span = el("span", "", "color: red", text("§lbold§r world"))
        val p = div("", text("Hi "), span)
        layout(div("", p))
        val frags = p.box.paragraphs.single().lines.single().fragments.map { it as Fragment.Text }
        assertEquals("Hi bold world", frags.joinToString("") { it.text })
        assertEquals(700, frags[1].style.fontWeight)
        assertEquals(0xFFFF0000.toInt(), frags[1].style.color)
        assertTrue(span.box.inParagraph)
    }

    @Test
    fun inlineBlockIsAtomicInline() {
        val btn = el("span", "", "display: inline-block; width: 20px; height: 14px")
        val p = div("", text("ab "), btn, text(" cd"))
        layout(div("", p))
        val line = p.box.paragraphs.single().lines.single()
        assertEquals(3, line.fragments.size)
        assertEquals(12f, btn.box.x, 0.01f) // "ab " = 3 chars
        // Box bottom sits on the baseline (14 above), strut adds descent 2 + half-leading 1 below.
        assertEquals(17f, line.height, 0.01f)
    }

    @Test
    fun lineHeightProperty() {
        val p = div("line-height: 2", text("a"))
        layout(div("", p))
        assertEquals(16f, p.box.height, 0.01f)
    }

    @Test
    fun brForcesLineBreak() {
        val p = div("", text("a"), TestNode("br"), text("b"))
        layout(div("", p))
        assertEquals(2, p.box.paragraphs.single().lines.size)
    }

    // ---- positioning ----

    @Test
    fun absolutePositioningUsesNearestPositionedAncestor() {
        val abs = div("position: absolute; right: 5px; bottom: 5px; width: 10px; height: 10px")
        val middle = div("height: 20px", abs)
        val rel = div("position: relative; margin-top: 10px; height: 50px; padding: 3px", middle)
        layout(div("", rel))
        // containing block = rel's padding box (100 x 50) at y=10
        assertBox(abs, 85f, 45f, 10f, 10f)
    }

    @Test
    fun absoluteWithLeftAndRightStretches() {
        val abs = div("position: absolute; left: 10px; right: 10px; top: 0; height: 5px")
        layout(div("", abs))
        assertBox(abs, 10f, 0f, 80f, 5f)
    }

    @Test
    fun absoluteDoesNotTakeSpaceInFlow() {
        val abs = div("position: absolute; width: 10px; height: 10px")
        val after = div("height: 5px")
        layout(div("", abs, after))
        assertEquals(0f, after.absY)
        assertBox(abs, 0f, 0f, 10f, 10f) // static position
    }

    @Test
    fun relativeOffsetsDoNotAffectSiblings() {
        val a = div("position: relative; top: 5px; left: 3px; height: 10px")
        val b = div("height: 10px")
        layout(div("", a, b))
        assertBox(a, 3f, 5f, 100f, 10f)
        assertEquals(10f, b.absY)
    }

    @Test
    fun fixedIsRelativeToViewport() {
        val fixed = div("position: fixed; right: 0; top: 0; width: 10px; height: 10px")
        val holder = div("position: relative; margin: 20px; height: 30px", fixed)
        layout(div("", holder))
        assertBox(fixed, 90f, 0f, 10f, 10f)
    }

    @Test
    fun displayNoneIsSkipped() {
        val hidden = div("display: none; height: 50px")
        val after = div("height: 5px")
        layout(div("", hidden, after))
        assertEquals(0f, after.absY)
        assertEquals(false, hidden.box.visible)
    }

    @Test
    fun replacedElementKeepsAspectRatio() {
        val img = TestNode("img", inlineStyle = "display: block; height: 32px", intrinsicWidth = 16f, intrinsicHeight = 8f)
        layout(div("", img))
        assertEquals(64f, img.box.width, 0.01f)
        assertEquals(32f, img.box.height, 0.01f)
    }

    @Test
    fun scrollSizeIncludesOverflow() {
        val child = div("height: 300px")
        val scroller = div("height: 50px; overflow: auto", child)
        layout(div("", scroller))
        assertEquals(300f, scroller.box.scrollHeight, 0.01f)
        assertEquals(50f, scroller.box.height)
    }
}
