package net.sbo.guilib.core.layout

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.div
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AlignContentTest {
    private fun tops(css: String): List<Float> {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse("div { display: block } $css", "t.css")), clock = { 0L })
        root.render(VComponent(component("T") {
            div(className = "c") { repeat(4) { div(className = "i") {} } }
        }, Unit, null))
        root.frame(300f, 300f)
        val c = root.document.body.querySelector(".c")!!
        val y0 = c.getBoundingClientRect().y
        return c.children.map { (it as net.sbo.guilib.core.dom.Element).getBoundingClientRect().y - y0 }
    }

    /** Two lines of two 40×10 items in a 100×100 wrapping flex container: 80px free cross space. */
    private fun flex(align: String?, itemHeight: String = "height: 10px") =
        tops(".c { display: flex; flex-wrap: wrap; width: 100px; height: 100px; ${align?.let { "align-content: $it" } ?: ""} } .i { width: 40px; $itemHeight }")
            .let { listOf(it[0], it[2]) }

    @Test
    fun flexLinesAreDistributed() {
        assertEquals(listOf(0f, 50f), flex(null)) // normal = stretch: each line gets half of the free space
        assertEquals(listOf(0f, 50f), flex("stretch"))
        assertEquals(listOf(0f, 10f), flex("flex-start"))
        assertEquals(listOf(0f, 10f), flex("start"))
        assertEquals(listOf(80f, 90f), flex("flex-end"))
        assertEquals(listOf(40f, 50f), flex("center"))
        assertEquals(listOf(0f, 90f), flex("space-between"))
        assertEquals(listOf(20f, 70f), flex("space-around"))
        val evenly = flex("space-evenly")
        assertEquals(80f / 3f, evenly[0], 0.01f)
        assertEquals(80f / 3f * 2f + 10f, evenly[1], 0.01f)
    }

    @Test
    fun stretchedLinesStretchTheirItems() {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse("div { display: block } .c { display: flex; flex-wrap: wrap; width: 100px; height: 100px } .i { width: 40px }", "t.css")), clock = { 0L })
        root.render(VComponent(component("T") { div(className = "c") { repeat(4) { div(className = "i") {} } } }, Unit, null))
        root.frame(300f, 300f)
        assertEquals(50f, root.document.body.querySelector(".i")!!.getBoundingClientRect().height)
    }

    @Test
    fun singleLineFlexIgnoresIt() {
        val t = tops(".c { display: flex; width: 200px; height: 100px; align-content: flex-end } .i { width: 40px; height: 10px }")
        assertEquals(listOf(0f, 0f, 0f, 0f), t)
    }

    /** Two rows in a 100px tall grid. */
    private fun grid(align: String?, rows: String = "grid-auto-rows: 20px") =
        tops(".c { display: grid; grid-template-columns: 50px 50px; $rows; height: 100px; ${align?.let { "align-content: $it" } ?: ""} } .i { height: 10px }")
            .let { listOf(it[0], it[2]) }

    @Test
    fun gridRowsAreDistributed() {
        assertEquals(listOf(0f, 20f), grid(null))
        assertEquals(listOf(30f, 50f), grid("center"))
        assertEquals(listOf(60f, 80f), grid("end"))
        assertEquals(listOf(0f, 80f), grid("space-between"))
        // Auto rows: normal stretches them, start keeps their content height.
        assertEquals(listOf(0f, 50f), grid(null, rows = "grid-auto-rows: auto"))
        assertEquals(listOf(0f, 10f), grid("start", rows = "grid-auto-rows: auto"))
        assertEquals(listOf(0f, 10f), tops(".c { display: grid; grid-template-columns: 50px 50px; height: 100px; place-content: start } .i { height: 10px }").let { listOf(it[0], it[2]) })
    }
}
