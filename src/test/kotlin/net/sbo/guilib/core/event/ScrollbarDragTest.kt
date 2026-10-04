package net.sbo.guilib.core.event

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScrollbarDragTest {
    private val ua = "div { display: block }"
    private var clicks = 0

    /** A 50×50 box with 200px of content: the vertical thumb is 12.5px tall at x 47–50, it can move 37.5px for 150px of scroll. */
    private fun ui(style: String = "overflow-y: auto", contentStyle: String = "height: 200px"): Pair<UiRoot, Element> {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT)))
        root.render(VComponent(component("T") {
            div(className = "box", style = "width: 50px; height: 50px; $style") {
                div(style = contentStyle, onClick = { clicks++ })
            }
        }, Unit, null))
        root.frame(300f, 200f)
        return root to root.document.body.querySelector(".box")!!
    }

    @Test
    fun draggingTheThumbScrolls() {
        val (root, box) = ui()
        assertTrue(root.input.mouseDown(48.5f, 5f, 0))
        root.input.mouseMove(48.5f, 5f + 18.75f)
        assertEquals(75f, box.scrollTop, 0.01f)
        // The drag keeps going outside of the box
        root.input.mouseMove(200f, 100f)
        assertEquals(150f, box.scrollTop, 0.01f)
        root.input.mouseUp(200f, 100f, 0)
        root.input.mouseMove(48.5f, 5f)
        assertEquals(150f, box.scrollTop, 0.01f)
        assertEquals(0, clicks)
    }

    @Test
    fun pressingTheTrackJumpsThereAndKeepsDragging() {
        val (root, box) = ui()
        root.input.mouseDown(48.5f, 25f, 0)
        // The thumb's middle goes to the mouse: (25 - 6.25) / 37.5 of the scroll
        assertEquals(75f, box.scrollTop, 0.01f)
        root.input.mouseMove(48.5f, 25f - 18.75f)
        assertEquals(0f, box.scrollTop, 0.01f)
        root.input.mouseUp(48.5f, 25f - 18.75f, 0)
        assertEquals(0, clicks)
    }

    @Test
    fun horizontalScrollbarDragsToo() {
        val (root, box) = ui("overflow-x: auto; overflow-y: hidden", "width: 200px; height: 10px")
        root.input.mouseDown(5f, 48.5f, 0)
        root.input.mouseMove(5f + 18.75f, 48.5f)
        assertEquals(75f, box.scrollLeft, 0.01f)
        root.input.mouseUp(5f + 18.75f, 48.5f, 0)
    }

    @Test
    fun pressesBesideTheScrollbarStillReachTheContent() {
        val (root, box) = ui()
        root.input.mouseDown(20f, 20f, 0)
        root.input.mouseMove(20f, 40f)
        root.input.mouseUp(20f, 40f, 0)
        assertEquals(0f, box.scrollTop, 0.01f)
        assertEquals(1, clicks)
    }

    @Test
    fun pageKeysScrollTheContainerUnderTheMouse() {
        val (root, box) = ui()
        root.input.mouseMove(20f, 20f)
        assertTrue(root.input.keyDown("PageDown", 0))
        assertEquals(43.75f, box.scrollTop, 0.01f) // 87.5 % of the 50px box
        root.input.keyDown("PageDown", 0)
        assertEquals(87.5f, box.scrollTop, 0.01f)
        root.input.keyDown("PageUp", 0)
        assertEquals(43.75f, box.scrollTop, 0.01f)
        root.input.keyDown("End", 0)
        assertEquals(150f, box.scrollTop, 0.01f)
        // Nothing left to scroll: the key is left to the screen
        assertTrue(!root.input.keyDown("PageDown", 0))
        root.input.keyDown("Home", 0)
        assertEquals(0f, box.scrollTop, 0.01f)
    }

    @Test
    fun pageKeysFallBackToTheBiggestScrollContainer() {
        val (root, box) = ui()
        root.input.mouseMove(250f, 150f)
        root.input.keyDown("PageDown", 0)
        assertEquals(43.75f, box.scrollTop, 0.01f)
    }
}
