package net.sbo.guilib.core.paint

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.anim.Interpolation
import net.sbo.guilib.core.css.BoxShadow
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Prop
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BoxShadowTest {
    private fun ui(css: String): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse("div { display: block }\n$css", "t.css")), clock = { 0L })
        root.render(VComponent(component("T") { div(className = "a") {} }, Unit, null))
        root.frame(200f, 100f)
        return root
    }

    private fun UiRoot.box() = document.body.querySelector(".a")!!

    @Test
    fun parsesLists() {
        val s = ui(
            ".a { color: #ff0000; box-shadow: 1px 2px 3px 4px #00ff00, inset 0 1px rgba(0,0,0,0.5), -2px 0 4px }",
        ).box().style.boxShadow
        assertEquals(3, s.size)
        assertEquals(BoxShadow(1f, 2f, 3f, 4f, 0xFF00FF00.toInt(), false), s[0])
        assertTrue(s[1].inset)
        assertEquals(0f, s[1].blur)
        assertEquals(0xFFFF0000.toInt(), s[2].color) // no color: currentColor
        assertEquals(4f, s[2].blur)

        assertTrue(ui(".a { box-shadow: 1px 2px; box-shadow: none }").box().style.boxShadow.isEmpty())
        // Invalid values are dropped and the earlier declaration stays.
        for (bad in listOf("1px", "1px 2px 3px 4px 5px", "1px 2px -3px", "inset inset 1px 2px", "1px 2px red blue", "10% 2px")) {
            assertEquals(1, ui(".a { box-shadow: 1px 1px; box-shadow: $bad }").box().style.boxShadow.size, bad)
        }
    }

    @Test
    fun outerShadowsPaintUnderTheBoxInsetOnTop() {
        val root = ui(
            """
            .a { width: 50px; height: 20px; margin: 10px; background-color: #222222; border: 2px solid #ffffff; border-radius: 6px;
                 box-shadow: 0 0 2px #ff0000, 0 4px 8px 1px #00ff00, inset 0 2px 3px #0000ff }
            """.trimIndent(),
        )
        val cmds = root.painter.commands.filter { it is PaintCommand.Shadow || it is PaintCommand.Box }
        // The second outer shadow is painted first (the first one is on top), then the box, then the inset shadow.
        val s1 = cmds[0] as PaintCommand.Shadow
        val s2 = cmds[1] as PaintCommand.Shadow
        assertEquals(0xFF00FF00.toInt(), s1.color)
        assertEquals(0xFFFF0000.toInt(), s2.color)
        assertTrue(cmds[2] is PaintCommand.Box)
        val inset = cmds[3] as PaintCommand.Shadow
        assertTrue(inset.inset)
        // Outer: the border box; inset: the padding box with the inner radius.
        assertEquals(listOf(10f, 10f, 50f, 20f), listOf(s1.x, s1.y, s1.width, s1.height))
        assertEquals(6f, s1.radii[0])
        assertEquals(listOf(12f, 12f, 46f, 16f), listOf(inset.x, inset.y, inset.width, inset.height))
        assertEquals(4f, inset.radii[0])
        // The blurred, offset and spread shadow reaches 1 + 8 * 1.5 + 1 px beyond the moved box.
        assertEquals(10f - 14f, s1.bounds.x)
        assertEquals(10f + 4f - 14f, s1.bounds.y)
    }

    @Test
    fun shadowWithoutBackgroundStillPaintsAndOpacityApplies() {
        val root = ui(".a { width: 10px; height: 10px; opacity: 0.5; box-shadow: 2px 2px #ff000000, 2px 2px #ffffff }")
        val shadows = root.painter.commands.filterIsInstance<PaintCommand.Shadow>()
        assertEquals(1, shadows.size) // the transparent one is skipped
        assertTrue((shadows[0].color ushr 24) in 127..128)
    }

    @Test
    fun interpolation() {
        val a = listOf(BoxShadow(0f, 0f, 0f, 0f, 0xFF000000.toInt(), false))
        val b = listOf(BoxShadow(0f, 4f, 8f, 2f, 0xFF000000.toInt(), false), BoxShadow(0f, 0f, 0f, 0f, 0xFFFFFFFF.toInt(), true))
        @Suppress("UNCHECKED_CAST")
        val mid = Interpolation.interpolate(Prop.BOX_SHADOW, a, b, 0.5f) as List<BoxShadow>
        assertEquals(BoxShadow(0f, 2f, 4f, 1f, 0xFF000000.toInt(), false), mid[0])
        assertTrue((mid[1].color ushr 24) in 127..128) // padded with a transparent inset shadow
        @Suppress("UNCHECKED_CAST")
        val fromNone = Interpolation.interpolate(Prop.BOX_SHADOW, emptyList<BoxShadow>(), a, 0.5f) as List<BoxShadow>
        assertEquals(1, fromNone.size)
        // Inset and outer can't be mixed: discrete switch.
        assertNull(Interpolation.interpolate(Prop.BOX_SHADOW, a, listOf(a[0].copy(inset = true)), 0.5f))
    }
}
