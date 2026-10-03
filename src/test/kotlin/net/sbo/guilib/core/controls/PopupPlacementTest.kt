package net.sbo.guilib.core.controls

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.Rect
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.ComponentScope
import net.sbo.guilib.core.dsl.colorInput
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.select
import net.sbo.guilib.core.dsl.tooltip
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tooltips, dropdowns and popovers move into the screen instead of being cut off at its edges. */
class PopupPlacementTest {
    private var now = 0L
    private val w = 300f
    private val h = 200f

    private fun ui(content: ComponentScope.() -> Unit): UiRoot {
        val ua = javaClass.getResource("/assets/guilib/css/ua.css")!!.readText()
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua.css", Origin.USER_AGENT)), clock = { now })
        root.render(VComponent(component("T") { content() }, Unit, null))
        root.frame(w, h)
        return root
    }

    private fun UiRoot.overlay(sel: String): Element = document.overlayRoot.querySelector(sel)!!
    private fun UiRoot.el(sel: String): Element = document.body.querySelector(sel)!!

    private fun UiRoot.click(el: Element) {
        val r = el.getBoundingClientRect()
        input.mouseDown(r.x + 1f, r.y + 1f, 0)
        input.mouseUp(r.x + 1f, r.y + 1f, 0)
        frame(w, h)
    }

    private fun UiRoot.wait(ms: Long) {
        now += ms
        frame(w, h)
        frame(w, h)
    }

    private fun assertInside(r: Rect) {
        assertTrue(r.x >= 0f && r.y >= 0f && r.right <= w && r.bottom <= h, "popup $r is not inside the ${w}x$h screen")
    }

    @Test
    fun titleTooltipMovesLeftAndUpAtTheBottomRightCorner() {
        val root = ui {
            div(title = "A fairly long hover text that needs room", style = "position: absolute; right: 0; bottom: 0; width: 20px; height: 10px")
        }
        root.input.mouseMove(w - 4f, h - 4f)
        root.frame(w, h)
        root.wait(600)
        val tip = root.overlay(".guilib-tooltip").getBoundingClientRect()
        assertInside(tip)
        // It flips to the other side of the cursor instead of covering it.
        assertTrue(tip.right <= w - 4f && tip.bottom <= h - 4f, "tip $tip covers the cursor")
    }

    @Test
    fun tooltipFlipsBelowWhenThereIsNoRoomAbove() {
        val root = ui {
            div(style = "position: absolute; right: 0; top: 0") {
                tooltip("Shows a long explanation here", placement = "top") { div(className = "anchor", style = "width: 10px; height: 10px") }
            }
        }
        val anchor = root.el(".anchor").getBoundingClientRect()
        root.input.mouseMove(anchor.x + 2f, anchor.y + 2f)
        root.frame(w, h)
        root.wait(400)
        val tip = root.overlay(".guilib-tooltip").getBoundingClientRect()
        assertInside(tip)
        assertTrue(tip.y >= anchor.bottom, "tip $tip should sit below the anchor $anchor")
    }

    @Test
    fun selectMenuWiderThanTheSelectStaysInsideAtTheRightEdge() {
        val root = ui {
            div(style = "position: absolute; right: 0; top: 0") {
                select(value = "a", onChange = {}, style = "min-width: 0; width: 40px") {
                    option("a", "Short")
                    option("b", "A much longer option label than the select")
                }
            }
        }
        root.click(root.el("select"))
        root.frame(w, h)
        assertInside(root.overlay(".guilib-select-menu").getBoundingClientRect())
    }

    @Test
    fun colorPopoverStaysInsideAtTheRightEdge() {
        val root = ui {
            div(style = "position: absolute; right: 0; top: 0") { colorInput(value = 0xFF5B8DEF.toInt(), onChange = {}) }
        }
        root.click(root.el(".guilib-color-input"))
        root.frame(w, h)
        assertInside(root.overlay(".guilib-color-popover").getBoundingClientRect())
    }
}
