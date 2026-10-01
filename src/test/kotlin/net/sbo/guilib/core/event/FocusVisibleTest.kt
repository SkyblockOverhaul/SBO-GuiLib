package net.sbo.guilib.core.event

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.PseudoState
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.input
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FocusVisibleTest {
    private fun ui(): UiRoot {
        val css = "div, button, input { display: block; height: 10px } button:focus-visible { color: #ff0000 }"
        val app = component("T") {
            button(className = "a") { +"a" }
            button(className = "b") { +"b" }
            input(value = "", onChange = {})
        }
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(css, "t", Origin.USER_AGENT)))
        root.render(VComponent(app, Unit, null))
        root.frame(100f, 100f)
        return root
    }

    @Test
    fun onlyKeyboardFocusShowsTheRing() {
        val root = ui()
        val a = root.document.body.querySelector(".a")!!
        val b = root.document.body.querySelector(".b")!!
        root.input.mouseDown(5f, 5f, 0); root.input.mouseUp(5f, 5f, 0); root.frame(100f, 100f)
        assertTrue(a.hasState(PseudoState.FOCUS))
        assertFalse(a.hasState(PseudoState.FOCUS_VISIBLE))
        root.input.keyDown("Tab", 258); root.frame(100f, 100f)
        assertTrue(b.hasState(PseudoState.FOCUS_VISIBLE))
        assertEquals(0xFFFF0000.toInt(), b.style.color)
        assertFalse(a.hasState(PseudoState.FOCUS_VISIBLE))

        // Clicked, then a key: the ring appears (like browsers). Modifier keys alone don't count.
        root.input.mouseDown(5f, 5f, 0); root.input.mouseUp(5f, 5f, 0); root.frame(100f, 100f)
        root.input.keyDown("Shift", 340)
        assertFalse(a.hasState(PseudoState.FOCUS_VISIBLE))
        root.input.keyDown("ArrowDown", 264)
        assertTrue(a.hasState(PseudoState.FOCUS_VISIBLE))

        // Text fields always show it.
        root.input.mouseDown(5f, 25f, 0); root.frame(100f, 100f)
        assertTrue(root.document.body.querySelector("input")!!.hasState(PseudoState.FOCUS_VISIBLE))
    }
}
