package net.sbo.guilib.core.event

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.item
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `item(stack, tooltip = true)` asks the backend for Minecraft's item tooltip while the icon itself is hovered. */
class ItemTooltipTest {
    private val withTooltip = Any()
    private val plain = Any()

    private fun ui(): UiRoot {
        val app = component("T") {
            div(className = "slot") { item(withTooltip, tooltip = true) }
            div(className = "slot") { item(plain) }
        }
        val css = "div { display: block; height: 20px } item { display: block; width: 16px; height: 16px }"
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(css, "t", Origin.USER_AGENT)))
        root.render(VComponent(app, Unit, null))
        root.frame(100f, 100f)
        return root
    }

    @Test
    fun onlyAHoveredItemWithTooltipReturnsItsStack() {
        val root = ui()
        assertNull(root.input.hoveredItemTooltip())
        root.input.mouseMove(5f, 5f); root.frame(100f, 100f) // over the first icon
        assertEquals(withTooltip, root.input.hoveredItemTooltip())
        root.input.mouseMove(50f, 5f); root.frame(100f, 100f) // same slot, but beside the icon
        assertNull(root.input.hoveredItemTooltip())
        root.input.mouseMove(5f, 25f); root.frame(100f, 100f) // icon without tooltip
        assertNull(root.input.hoveredItemTooltip())
    }
}
