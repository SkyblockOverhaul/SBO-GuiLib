package net.sbo.guilib.core.paint

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.scroll
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.event.Modifiers
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PainterTest {
    private val ua = """
        div, scroll { display: block }
        span { display: inline }
        button { display: inline-flex }
        scroll { overflow: auto }
    """.trimIndent()

    private fun ui(css: String, content: net.sbo.guilib.core.dsl.ComponentScope.() -> Unit): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT), Stylesheet.parse(css, "t.css")), clock = { 0L })
        root.render(VComponent(component("T") { content() }, Unit, null))
        root.frame(200f, 100f)
        return root
    }

    private fun UiRoot.texts() = painter.commands.filterIsInstance<PaintCommand.Text>().map { it.text }

    /** Clip rect active when each box was painted (null = none), in painting order. */
    private fun UiRoot.clipsOfBoxes(): List<Pair<Int, net.sbo.guilib.core.dom.Rect?>> {
        val stack = ArrayList<net.sbo.guilib.core.dom.Rect>()
        val out = ArrayList<Pair<Int, net.sbo.guilib.core.dom.Rect?>>()
        for (c in painter.commands) when (c) {
            is PaintCommand.PushClip -> stack += c.rect
            PaintCommand.PopClip -> stack.removeAt(stack.lastIndex)
            is PaintCommand.Box -> out += c.background to stack.lastOrNull()
            else -> {}
        }
        return out
    }

    @Test
    fun positionedChildrenOfAScrollContainerAreClipped() {
        // Regression: positioned elements paint as their own layer after the container; its clip must still apply.
        val root = ui(
            """
            .list { height: 20px; overflow: hidden }
            .rel { position: relative; height: 10px; margin-top: 30px; background-color: #ff0000 }
            .abs { position: absolute; left: 0; top: 0; width: 5px; height: 5px; background-color: #00ff00 }
            """.trimIndent(),
        ) {
            div(className = "list") { div(className = "rel") { div(className = "abs") } }
        }
        val clips = root.clipsOfBoxes().toMap()
        assertEquals(net.sbo.guilib.core.dom.Rect(0f, 0f, 200f, 20f), clips[0xFFFF0000.toInt()])
        assertEquals(net.sbo.guilib.core.dom.Rect(0f, 0f, 200f, 20f), clips[0xFF00FF00.toInt()])
    }

    @Test
    fun textFollowsAColorChangeWithoutRelayout() {
        // Regression (Felix): a button going from disabled to enabled kept the :disabled text color, because text
        // fragments carried the color of the last layout and a color change only repaints.
        lateinit var setPicked: (Boolean) -> Unit
        val root = ui("button.primary { color: #ffffff } button.primary:disabled { color: #808080; text-decoration: underline }") {
            val (picked, s) = useState(false)
            setPicked = s
            button(className = "primary", disabled = !picked) { +"Confirm" }
        }
        fun label() = root.painter.commands.filterIsInstance<PaintCommand.Text>().single { it.text == "Confirm" }
        assertEquals(0xFF808080.toInt(), label().color)
        assertTrue(label().style.decoration.underline)
        setPicked(true)
        root.frame(200f, 100f)
        assertEquals(0xFFFFFFFF.toInt(), label().color)
        assertTrue(!label().style.decoration.underline)
    }

    @Test
    fun paintsTextOfFlexItemsAndParagraphs() {
        val root = ui("") {
            div { +"para" }
            button { +"label" }
        }
        assertEquals(listOf("para", "label"), root.texts())
    }

    @Test
    fun backgroundsAndBordersBecomeBoxes() {
        val root = ui(".a { width: 10px; height: 10px; background-color: red; border: 1px solid blue; border-radius: 2px; opacity: 0.5 }") {
            div(className = "a")
        }
        val box = root.painter.commands.filterIsInstance<PaintCommand.Box>().single()
        assertEquals(0x80FF0000.toInt(), box.background)
        assertEquals(1f, box.borders[0])
        assertEquals(2f, box.radii[0])
    }

    @Test
    fun childrenInRoundedClipFollowItsCorners() {
        val root = ui(
            """
            .card { width: 100px; height: 60px; border: 1px solid red; border-radius: 6px; overflow: hidden }
            .header { height: 10px; background-color: blue }
            .body { height: 20px; background-color: lime; border-radius: 2px }
            """.trimIndent(),
        ) {
            div(className = "card") { div(className = "header"); div(className = "body") }
        }
        val boxes = root.painter.commands.filterIsInstance<PaintCommand.Box>()
        val header = boxes.first { it.background == 0xFF0000FF.toInt() }
        // Top corners get the card's inner radius (6 - 1 border), bottom corners stay square.
        assertEquals(listOf(5f, 5f, 0f, 0f), header.radii.toList())
        // The body touches no corner of the clip: its own radius is kept.
        val body = boxes.first { it.background == 0xFF00FF00.toInt() }
        assertEquals(listOf(2f, 2f, 2f, 2f), body.radii.toList())
    }

    @Test
    fun zIndexOrdersPositionedElements() {
        val root = ui(
            """
            .a { position: absolute; z-index: 2; width: 10px; height: 10px; background-color: red }
            .b { position: absolute; z-index: 1; width: 10px; height: 10px; background-color: blue }
            .c { width: 10px; height: 10px; background-color: lime }
            """.trimIndent(),
        ) {
            div(className = "a"); div(className = "b"); div(className = "c")
        }
        val colors = root.painter.commands.filterIsInstance<PaintCommand.Box>().map { it.background }
        assertEquals(listOf(0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFF0000.toInt()), colors)
        // The top-most element wins the hit test.
        assertEquals("a", root.painter.hitTest(5f, 5f)?.className)
    }

    @Test
    fun scrollContainersClipAndOffsetContent() {
        val root = ui(".s { height: 20px } .row { height: 10px }") {
            scroll(className = "s") { repeat(5) { div(className = "row", key = it) { +"r$it" } } }
        }
        val s = root.document.body.querySelector(".s")!!
        assertEquals(30f, s.maxScrollTop)
        assertTrue(root.painter.commands.first() is PaintCommand.PushClip)
        s.scrollTop = 15f
        root.frame(200f, 100f)
        val first = root.painter.commands.filterIsInstance<PaintCommand.Text>().first()
        assertEquals(-15f + 1f, first.y, 0.01f) // row 0 at y=-15, glyph top = baseline - ascent (half-leading 1)
        // Hit-testing respects the scroll offset: y=1 is now inside row 1 (10..20 → -5..5).
        assertEquals("row", root.painter.hitTest(5f, 1f)?.className)
    }

    @Test
    fun inlineSpansAreHitTargets() {
        val root = ui("") {
            div { +"aa "; span(className = "link") { +"bb" } }
        }
        // "aa " = 3 chars * 4px; the span covers x 12..20.
        assertEquals("link", root.painter.hitTest(14f, 2f)?.className)
        assertEquals("div", root.painter.hitTest(2f, 2f)?.tagName)
    }

    @Test
    fun hoverAndClickThroughInteractionController() {
        var clicks = 0
        val root = ui("button { width: 20px; height: 10px } button:hover { background-color: red }") {
            button(onClick = { clicks++ }) { +"x" }
        }
        root.input.mouseMove(5f, 5f)
        root.frame(200f, 100f)
        val btn = root.document.body.querySelector("button")!!
        assertEquals(0xFFFF0000.toInt(), btn.style.backgroundColor)
        root.input.mouseDown(5f, 5f, 0)
        root.input.mouseUp(5f, 5f, 0)
        assertEquals(1, clicks)
        // Pressing on the button and releasing elsewhere is not a click.
        root.input.mouseDown(5f, 5f, 0)
        root.input.mouseUp(150f, 80f, 0)
        assertEquals(1, clicks)
    }

    @Test
    fun wheelScrollsNearestScrollableAncestor() {
        val root = ui(".s { height: 20px } .row { height: 10px }") {
            scroll(className = "s") { repeat(5) { div(className = "row", key = it) } }
        }
        root.input.wheel(5f, 5f, 0f, 12f)
        assertEquals(12f, root.document.body.querySelector(".s")!!.scrollTop)
        root.input.wheel(5f, 5f, 0f, 100f)
        assertEquals(30f, root.document.body.querySelector(".s")!!.scrollTop) // clamped
    }

    @Test
    fun plainWheelScrollsHorizontalOnlyContainersSideways() {
        val root = ui(
            ".page { height: 30px; overflow: auto } .h { display: flex; width: 50px; overflow-x: auto; overflow-y: hidden }" +
                " .cell { width: 20px; height: 10px; flex-shrink: 0 } .row { height: 10px }",
        ) {
            div(className = "page") {
                div(className = "h") { repeat(5) { div(className = "cell", key = it) } }
                repeat(5) { div(className = "row", key = "r$it") }
            }
        }
        val h = root.document.body.querySelector(".h")!!
        val page = root.document.body.querySelector(".page")!!
        root.input.wheel(5f, 5f, 0f, 12f)
        assertEquals(12f, h.scrollLeft)
        assertEquals(0f, page.scrollTop)
        root.input.wheel(5f, 5f, 0f, 8f, Modifiers(shift = true)) // Shift + wheel scrolls sideways too
        assertEquals(20f, h.scrollLeft)
        root.input.wheel(5f, 5f, 0f, 100f)
        assertEquals(50f, h.scrollLeft) // clamped at the end ...
        assertEquals(0f, page.scrollTop)
        root.input.wheel(5f, 5f, 0f, 10f)
        assertEquals(10f, page.scrollTop) // ... after which the page scrolls on
    }

    @Test
    fun tabMovesFocus() {
        val root = ui("") {
            button(className = "one") { +"1" }
            button(className = "two") { +"2" }
        }
        root.input.keyDown("Tab", 258)
        assertEquals("one", root.document.focusedElement?.className)
        root.input.keyDown("Tab", 258)
        assertEquals("two", root.document.focusedElement?.className)
        root.input.keyDown("Tab", 258, net.sbo.guilib.core.event.Modifiers(shift = true))
        assertEquals("one", root.document.focusedElement?.className)
    }

    @Test
    fun gradientMeshesAreBoxRelativeAndReused() {
        val root = ui(".g { width: 40px; height: 10px; background-image: linear-gradient(90deg, red, blue) } .h { width: 30px; height: 10px; background-image: linear-gradient(90deg, red, blue) }") {
            div(className = "g")
            div(className = "g", style = "margin-left: 50px")
            div(className = "h")
        }
        val grads = root.painter.commands.filterIsInstance<PaintCommand.Gradient>()
        assertEquals(3, grads.size)
        // Same gradient and size at another place: the same mesh, relative to the box.
        assertTrue(grads[0].mesh === grads[1].mesh)
        assertEquals(50f, grads[1].x - grads[0].x, 0.01f)
        assertEquals(0f, grads[1].mesh.x.min(), 0.01f)
        assertEquals(40f, grads[1].mesh.x.max(), 0.01f)
        assertEquals(10f, grads[1].mesh.y.max(), 0.01f)
        // Another size is another mesh.
        assertTrue(grads[2].mesh !== grads[0].mesh)
        assertEquals(30f, grads[2].mesh.x.max(), 0.01f)
        // A repaint reuses it.
        root.document.body.querySelector(".h")!!.inlineStyle = "opacity: 1"
        root.frame(200f, 100f)
        assertTrue(root.painter.commands.filterIsInstance<PaintCommand.Gradient>()[0].mesh === grads[0].mesh)
    }

    @Test
    fun imagesCarryTheInheritedColorAsCurrentColor() {
        val root = ui(".btn { color: #ff8800 } .icon { width: 8px; height: 8px; background-image: url(mymod:icons/refresh.svg) }") {
            div(className = "btn") { div(className = "icon") }
        }
        val img = root.painter.commands.filterIsInstance<PaintCommand.Image>().single()
        assertEquals("mymod:icons/refresh.svg", img.src)
        assertEquals(0xFFFF8800.toInt(), img.color)
    }

    private fun UiRoot.boxes(color: Int) = painter.commands.filterIsInstance<PaintCommand.Box>().filter { it.background == color }

    @Test
    fun dashedBordersAreDrawnAsDashesOverTheBackground() {
        val root = ui(".d { width: 40px; height: 10px; border: 1px dashed #ff0000; background-color: #00ff00 }") { div(className = "d") }
        // The background reaches under the border (background-clip: border-box), so it shows in the gaps.
        val bg = root.boxes(0xFF00FF00.toInt()).single()
        assertEquals(40f, bg.width); assertTrue(bg.borders.all { it == 0f })
        val dashes = root.boxes(0xFFFF0000.toInt())
        val top = dashes.filter { it.y == 0f && it.height == 1f }.sortedBy { it.x }
        assertTrue(top.size >= 5, "top dashes: ${top.size}")
        assertEquals(0f, top.first().x) // a dash in each square corner
        assertEquals(40f, top.last().x + top.last().width, 0.001f)
        assertTrue(top.all { it.width in 2.5f..3.5f })
        // The left side starts and ends with a gap next to the corner dashes of the top and bottom sides.
        val left = dashes.filter { it.x == 0f && it.width == 1f && it.height < 5f }
        assertTrue(left.isNotEmpty() && left.all { it.y > 1f && it.y + it.height < 9f })
    }

    @Test
    fun dottedBordersAreRoundDots() {
        val root = ui(".d { width: 40px; height: 10px; border-bottom: 2px dotted #ff0000 }") { div(className = "d") }
        val dots = root.boxes(0xFFFF0000.toInt())
        assertTrue(dots.size >= 8, "dots: ${dots.size}")
        assertTrue(dots.all { it.width == 2f && it.height == 2f && it.y == 8f && it.radii.all { r -> r == 1f } })
    }

    @Test
    fun roundedDashedBordersGetSolidCornerArcs() {
        val root = ui(".d { width: 40px; height: 20px; border: 1px dashed #ff0000; border-radius: 4px }") { div(className = "d") }
        val clips = root.painter.commands.filterIsInstance<PaintCommand.PushClip>().map { it.rect }
        assertEquals(4, clips.size) // one corner quadrant each
        assertEquals(net.sbo.guilib.core.dom.Rect(0f, 0f, 4f, 4f), clips[0])
        val arcs = root.boxes(net.sbo.guilib.core.css.Colors.TRANSPARENT).filter { it.hasRadius }
        assertEquals(4, arcs.size)
        assertTrue(arcs.all { a -> a.borders.all { it == 1f } && a.borderColors.all { it == 0xFFFF0000.toInt() } })
        val top = root.boxes(0xFFFF0000.toInt()).filter { it.y == 0f }
        assertTrue(top.all { it.x > 4f && it.x + it.width < 36f }) // the straight part between the arcs
    }
}
