package net.sbo.guilib.core.controls

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.ComponentScope
import net.sbo.guilib.core.dsl.checkbox
import net.sbo.guilib.core.dsl.chips
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.input
import net.sbo.guilib.core.dsl.switch
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `accent-color` recolors the filled parts of GuiLib's controls (real ua.css), not focus borders. */
class AccentColorTest {
    private val themeAccent = 0xFF5B8DEF.toInt()

    private fun ui(css: String, content: ComponentScope.() -> Unit): UiRoot {
        val ua = javaClass.getResource("/assets/guilib/css/ua.css")!!.readText()
        val root = UiRoot(
            FakeMeasurer,
            listOf(Stylesheet.parse(ua, "ua.css", Origin.USER_AGENT), Stylesheet.parse(css, "t", Origin.AUTHOR)),
            clock = { 0L },
        )
        root.render(VComponent(component("T") { content() }, Unit, null))
        repeat(2) { root.frame(400f, 300f) }
        return root
    }

    private fun Element.box(cls: String): Element = descendants().first { cls in it.classList }

    @Test
    fun accentColorRecolorsCheckedControlsAndIsInherited() {
        val root = ui(
            """
            .gold { accent-color: #e0b04a }
            .gold .back { accent-color: auto }
            .themed { --guilib-accent: #00ff00 }
            """,
        ) {
            div(className = "gold") {
                checkbox(checked = true, className = "a")
                switch(checked = true, className = "s")
                chips(values = listOf("x")) { option("x", "X") }
                div(className = "back") { checkbox(checked = true, className = "b") }
                input(className = "field")
            }
            checkbox(checked = true, className = "plain")
            div(className = "themed") { checkbox(checked = true, className = "t") }
        }
        val body = root.document.body
        fun checkBg(cls: String) = body.querySelector(".$cls input")!!.style.backgroundColor
        assertEquals(0xFFE0B04A.toInt(), checkBg("a"))
        assertEquals(0xFFE0B04A.toInt(), body.querySelector(".s")!!.box("guilib-switch-track").style.backgroundColor)
        val chip = body.descendants().first { "guilib-chip" in it.classList && "selected" in it.classList }
        assertEquals(0xFFE0B04A.toInt(), chip.style.borderTopColor)
        assertEquals(0x40E0B04A, chip.style.backgroundColor) // 25 % for the soft variant
        assertEquals(themeAccent, checkBg("b")) // auto → theme again
        assertEquals(themeAccent, checkBg("plain"))
        assertEquals(0xFF00FF00.toInt(), checkBg("t")) // the theme variable still works without accent-color

        // Focus borders keep the theme accent (like browsers: accent-color is for checked/filled parts).
        val field = body.querySelector(".field")!!
        field.focus()
        repeat(2) { root.frame(400f, 300f) }
        assertEquals(themeAccent, field.style.borderTopColor)
    }
}
