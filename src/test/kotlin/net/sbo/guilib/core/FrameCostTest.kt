package net.sbo.guilib.core

import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** How much work one frame does (counted with [FrameStats]). */
class FrameCostTest {
    private var now = 0L

    private fun root(css: String): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse("div { display: block }\n$css", "t.css", Origin.USER_AGENT)), clock = { now })
        root.render(VComponent(component("T") { div(className = "a") }, Unit, null))
        root.frame(200f, 100f)
        return root
    }

    private fun countFrames(root: UiRoot, frames: Int) {
        root.input.mouseMove(5f, 5f) // the first hover is a real change; count the frames after it
        root.frame(200f, 100f)
        FrameStats.reset()
        repeat(frames) {
            now += 16
            root.input.mouseMove(5f, 5f) // a mouse over the content, so hover is refreshed every frame
            root.frame(200f, 100f)
        }
    }

    @Test
    fun anAnimatedFrameIsLaidOutAndPaintedOnce() {
        // Regression: the second document update after the hover refresh advanced the animations again, so every
        // animated frame was laid out and painted twice.
        val root = root(".a { height: 10px; animation: grow 1000ms linear infinite } @keyframes grow { to { height: 50px } }")
        countFrames(root, 10)
        assertEquals(10L, FrameStats.layouts)
        assertEquals(10L, FrameStats.paints)
        val paint = root(".a { height: 10px; animation: fade 1000ms linear infinite } @keyframes fade { to { opacity: 0 } }")
        countFrames(paint, 10)
        assertEquals(0L, FrameStats.layouts)
        assertEquals(10L, FrameStats.paints)
    }

    @Test
    fun aTransitionStartedByTheHoverRefreshIsDrawnAtItsStartValue() {
        // Regression: the hover refresh after a layout starts transitions in the settling update, which did not
        // tick animations any more – the frame was painted with the transition's END value, then snapped back.
        val css = ".a { height: 20px } .a.gone { height: 0 } .b { height: 10px; transition: opacity 1000ms linear } .b:hover { opacity: 0 }"
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse("div { display: block }\n$css", "t.css", Origin.USER_AGENT)), clock = { now })
        root.render(VComponent(component("T") { div(className = "a"); div(className = "b") }, Unit, null))
        root.frame(200f, 100f)
        root.input.mouseMove(5f, 5f) // over .a
        root.frame(200f, 100f)
        val a = root.document.body.querySelector(".a")!!
        val b = root.document.body.querySelector(".b")!!
        a.className = "a gone" // .b moves up under the mouse
        now += 16
        root.frame(200f, 100f)
        assertEquals(1f, (b.animatedStyle ?: b.computed!!).opacity, 0.001f)
        now += 500
        root.frame(200f, 100f)
        assertEquals(0.5f, (b.animatedStyle ?: b.computed!!).opacity, 0.02f)
    }

    @Test
    fun aStaticFrameDoesNothing() {
        val root = root(".a { height: 10px }")
        countFrames(root, 10)
        assertEquals(0L, FrameStats.styles)
        assertEquals(0L, FrameStats.layouts)
        assertEquals(0L, FrameStats.paints)
    }
}
