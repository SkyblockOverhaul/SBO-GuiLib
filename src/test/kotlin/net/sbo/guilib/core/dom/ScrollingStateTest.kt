package net.sbo.guilib.core.dom

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Colors
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.PseudoState
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.scroll
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `:scrolling` and the opt-in `.guilib-autohide` scrollbar from the real ua.css. */
class ScrollingStateTest {
    private var now = 0L
    private var rows = 10
    private lateinit var rerender: () -> Unit

    private fun root(): UiRoot {
        val ua = javaClass.getResource("/assets/guilib/css/ua.css")!!.readText()
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua.css", Origin.USER_AGENT)), clock = { now })
        root.render(VComponent(component("App") {
            rerender = useForceUpdate()
            scroll(className = "guilib-autohide", style = "height: 20px; --guilib-scrollbar-thumb: #ff0000") {
                for (i in 1..rows) div(key = i, style = "height: 10px") { +"$i" }
            }
            scroll(className = "plain", style = "height: 20px") {
                for (i in 1..10) div(key = i, style = "height: 10px") { +"$i" }
            }
        }, Unit, null))
        frame(root)
        return root
    }

    private fun frame(root: UiRoot) = root.frame(200f, 100f)

    /** Advances the clock like the game does, one ~16 ms frame at a time. */
    private fun advance(root: UiRoot, ms: Long) {
        val end = now + ms
        while (now < end) {
            now = minOf(end, now + 16); frame(root)
        }
    }
    private fun UiRoot.list() = document.body.querySelector(".guilib-autohide")!!
    private fun UiRoot.thumbAlpha() = Colors.alpha(list().style.scrollbarColor!!.first)

    @Test
    fun theScrollbarShowsWhileScrollingAndFadesOutAfterwards() {
        val root = root()
        assertEquals(0, root.thumbAlpha())
        root.list().scrollTop = 5f
        assertTrue(root.list().hasState(PseudoState.SCROLLING))
        advance(root, 100)
        root.list().scrollTop = 10f // still scrolling: :scrolling stays on
        advance(root, 100)
        assertTrue(root.list().hasState(PseudoState.SCROLLING))
        assertEquals(0xFFFF0000.toInt(), root.list().style.scrollbarColor!!.first) // the 120 ms fade-in is done, in the custom color
        advance(root, 100) // :scrolling ends 150 ms after the last change …
        assertFalse(root.list().hasState(PseudoState.SCROLLING))
        advance(root, 500)
        assertEquals(0xFF, root.thumbAlpha()) // … but the scrollbar stays during the 600 ms delay
        advance(root, 400)
        assertEquals(0, root.thumbAlpha()) // then fades out in 300 ms

        // Other scroll containers keep the normal, always visible scrollbar.
        val plain = root.document.body.querySelector(".plain")!!
        assertNull(plain.style.scrollbarColor)
        plain.scrollTop = 5f
        assertTrue(plain.hasState(PseudoState.SCROLLING))
    }

    @Test
    fun keepingTheScrollPositionInsideShrunkContentIsNotScrolling() {
        val root = root()
        root.list().scrollTop = 80f
        advance(root, 1000)
        assertFalse(root.list().hasState(PseudoState.SCROLLING))
        rows = 3; rerender(); frame(root)
        assertEquals(10f, root.list().scrollTop) // clamped to the new end …
        assertFalse(root.list().hasState(PseudoState.SCROLLING)) // … without flashing the scrollbar
    }
}
