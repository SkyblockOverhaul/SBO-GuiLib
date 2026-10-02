package net.sbo.guilib.core.dom

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.PseudoState
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Selectors that look at siblings must restyle when a sibling's classes or state change. */
class SiblingRestyleTest {
    private val css = """
        div { display: block; font-size: 8px }
        .row:nth-child(even of :not(.hidden)) { font-size: 20px }
        .a:hover + .b { font-size: 30px }
    """.trimIndent()

    private fun root(app: ComponentType<Unit>): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(css, "t", Origin.USER_AGENT)))
        root.render(VComponent(app, Unit, null))
        root.frame(200f, 100f)
        return root
    }

    @Test
    fun zebraStripesSkipHiddenRows() {
        var hidden = emptySet<Int>()
        lateinit var rerender: () -> Unit
        val root = root(component("App") {
            rerender = useForceUpdate()
            div {
                for (i in 1..5) div(className = if (i in hidden) "row hidden" else "row", key = i) { +"$i" }
            }
        })
        fun big() = root.document.body.querySelectorAll(".row").filter { it.style.fontSize == 20f }.map { it.children.first().textContent }
        assertEquals(listOf("2", "4"), big())
        hidden = setOf(2); rerender(); root.frame(200f, 100f)
        // Visible rows are 1, 3, 4, 5 → the even ones among them are 3 and 5; only row 2's own class changed.
        assertEquals(listOf("3", "5"), big())
    }

    @Test
    fun hoverOnASiblingRestylesTheNextOne() {
        val root = root(component("App") {
            div {
                div(className = "a") { +"a" }
                div(className = "b") { +"b" }
            }
        })
        val a = root.document.body.querySelector(".a")!!
        val b = root.document.body.querySelector(".b")!!
        a.setState(PseudoState.HOVER, true)
        root.frame(200f, 100f)
        assertEquals(30f, b.style.fontSize)
        a.setState(PseudoState.HOVER, false)
        root.frame(200f, 100f)
        assertEquals(8f, b.style.fontSize)
    }
}
