package net.sbo.guilib.core.dom

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Stylesheets added at runtime, e.g. CSS the player wrote, on top of the screen's own. */
class UseStylesheetTest {
    private val screenCss = """
        div { display: block }
        .box { font-size: 10px }
    """.trimIndent()

    @Test
    fun addedSheetWinsWhileMountedFollowsChangesAndSurvivesReload() {
        var css: String? = ".box { font-size: 20px }"
        var show = true
        lateinit var rerender: () -> Unit
        val User = component<String?>("User") { text -> useStylesheet(text, "user.css") }
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(screenCss, "screen")))
        root.render(VComponent(component("App") {
            rerender = useForceUpdate()
            if (show) User(css)
            div(className = "box") { +"x" }
        }, Unit, null))
        root.frame(200f, 100f)
        fun box() = root.document.body.querySelector(".box")!!
        assertEquals(20f, box().style.fontSize) // after the screen's sheet, so it wins at the same specificity

        root.document.setStylesheets(listOf(Stylesheet.parse(screenCss, "screen", Origin.AUTHOR)))
        root.frame(200f, 100f)
        assertEquals(20f, box().style.fontSize) // a reload keeps it

        css = ".box { font-size: 30px }"; rerender(); root.frame(200f, 100f)
        assertEquals(30f, box().style.fontSize)

        css = null; rerender(); root.frame(200f, 100f)
        assertEquals(10f, box().style.fontSize)

        css = ".box { font-size: 20px }"; rerender(); root.frame(200f, 100f)
        show = false; rerender(); root.frame(200f, 100f)
        assertEquals(10f, box().style.fontSize) // unmounting removes it
    }

    @Test
    fun problemsListEveryMistakeEachTimeTheSheetIsParsed() {
        val css = ".box { colr: red; font-size: 20px }\n.ok { color: red }\n.x { widht: 3px }"
        repeat(2) { // the log shows a message once, the list has it every time
            val problems = Stylesheet.parse(css, "user.css").problems
            assertEquals(2, problems.size)
            assertTrue(problems[0].startsWith("user.css:1:"), problems[0])
            assertTrue("colr" in problems[0], problems[0])
            assertTrue(problems[1].startsWith("user.css:3:"), problems[1])
        }
        assertEquals(emptyList<String>(), Stylesheet.parse(".ok { color: red }", "fine.css").problems)

        var sheet: Stylesheet? = null
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(screenCss, "screen")))
        root.render(VComponent(component("App") { sheet = useStylesheet(css, "user.css") }, Unit, null))
        root.frame(200f, 100f)
        assertEquals(2, sheet!!.problems.size)
    }

    @Test
    fun higherOrderStaysLastWhenALowerOneIsReplaced() {
        var theme = ".box { font-size: 20px }"
        lateinit var rerender: () -> Unit
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(screenCss, "screen")))
        root.render(VComponent(component("App") {
            rerender = useForceUpdate()
            useStylesheet(theme, "theme.css")
            useStylesheet(".box { font-size: 30px }", "user.css", order = 1)
            div(className = "box") { +"x" }
        }, Unit, null))
        root.frame(200f, 100f)
        fun box() = root.document.body.querySelector(".box")!!
        assertEquals(30f, box().style.fontSize)

        theme = ".box { font-size: 25px }"; rerender(); root.frame(200f, 100f)
        assertEquals(30f, box().style.fontSize) // the replaced theme sheet doesn't jump over user.css
    }
}
