package net.sbo.guilib.core.dom

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Changing the body's classes and inline style at runtime (e.g. a font or theme switch without reopening). */
class BodyStyleTest {
    private val css = """
        div { display: block }
        body.mc { font-family: minecraft }
        body.big .box { font-size: 20px }
    """.trimIndent()

    private fun root(app: ComponentType<Unit>): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(css, "t", Origin.USER_AGENT)))
        root.render(VComponent(app, Unit, null))
        root.frame(200f, 100f)
        return root
    }

    private fun UiRoot.box() = document.body.querySelector(".box")!!
    private fun UiRoot.popup() = document.overlayRoot.querySelector(".popup")!!

    @Test
    fun classListAndInlineStyleOfTheBodyCanBeChanged() {
        val root = root(component("A") {
            div(className = "box") { +"x" }
            portal { div(className = "popup") { +"p" } }
        })
        val body = root.document.body
        body.classList.add("mc", "big")
        root.frame(200f, 100f)
        assertEquals(listOf("minecraft"), root.box().style.fontFamily)
        assertEquals(listOf("minecraft"), root.popup().style.fontFamily) // portals live under the body too
        assertEquals(20f, root.box().style.fontSize)

        assertFalse(body.classList.toggle("big"))
        assertTrue(body.classList.toggle("big", true))
        body.classList.remove("big")
        body.classList.replace("mc", "other")
        root.frame(200f, 100f)
        assertEquals("other", body.className)
        assertEquals(listOf("inter"), root.box().style.fontFamily)

        body.setStyleProperty("--accent", "#ff0000")
        body.setStyleProperty("font-size", "12px")
        root.frame(200f, 100f)
        assertEquals(12f, root.popup().style.fontSize)
        body.setStyleProperty("font-size", "14px")
        assertEquals("14px", body.getStyleProperty("font-size"))
        assertEquals("#ff0000", body.getStyleProperty("--accent"))
        body.removeStyleProperty("font-size")
        root.frame(200f, 100f)
        assertNull(body.getStyleProperty("font-size"))
        assertEquals(8f, root.popup().style.fontSize)

        body.inlineStyle = "font-family: minecraft"
        root.frame(200f, 100f)
        assertEquals(listOf("minecraft"), root.box().style.fontFamily)
    }

    @Test
    fun hooksSetTheBodyWhileMountedAndCleanUp() {
        var mc = true
        var show = true
        lateinit var rerender: () -> Unit
        val Font = component<Boolean>("Font") { on ->
            useBodyClass("mc", on)
            useBodyStyle("font-size", if (on) "10px" else null)
        }
        val root = root(component("App") {
            rerender = useForceUpdate()
            if (show) Font(mc)
            div(className = "box") { +"x" }
        })
        root.frame(200f, 100f)
        assertEquals(listOf("minecraft"), root.box().style.fontFamily)
        assertEquals(10f, root.box().style.fontSize)

        mc = false; rerender(); root.frame(200f, 100f)
        assertEquals(listOf("inter"), root.box().style.fontFamily)
        assertEquals(8f, root.box().style.fontSize)

        mc = true; rerender(); root.frame(200f, 100f)
        assertTrue(root.document.body.classList.contains("mc"))
        show = false; rerender(); root.frame(200f, 100f) // unmounting removes what the hooks added
        assertFalse(root.document.body.classList.contains("mc"))
        assertNull(root.document.body.getStyleProperty("font-size"))
    }
}
