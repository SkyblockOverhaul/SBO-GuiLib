package net.sbo.guilib.core.event

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The backend shows item tooltips (`show_item` hover events) for the hovered element or its ancestors. */
class HoveredAttributeTest {
    private val payload = Any()

    private fun ui(): UiRoot {
        val app = component("T") {
            div {
                element("span", null, null, null, null, null, mapOf("mc-hover" to payload), emptyMap()) {
                    span(className = "inner") { +"Diamond" }
                }
            }
            div { +"plain" }
        }
        val css = "div { display: block; height: 10px } span { display: inline-block; width: 40px; height: 10px }"
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(css, "t", Origin.USER_AGENT)))
        root.render(VComponent(app, Unit, null))
        root.frame(100f, 100f)
        return root
    }

    @Test
    fun findsTheAttributeOnTheHoveredElementOrAnAncestor() {
        val root = ui()
        assertNull(root.input.hoveredAttribute("mc-hover"))
        root.input.mouseMove(5f, 5f); root.frame(100f, 100f) // over the inner span
        assertEquals(payload, root.input.hoveredAttribute("mc-hover"))
        root.input.mouseMove(5f, 15f); root.frame(100f, 100f) // over the plain div
        assertNull(root.input.hoveredAttribute("mc-hover"))
    }
}
