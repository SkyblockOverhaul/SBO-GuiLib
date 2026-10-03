package net.sbo.guilib.core.layout

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.NodeBuilder
import net.sbo.guilib.core.dsl.caption
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.dsl.table
import net.sbo.guilib.core.dsl.tbody
import net.sbo.guilib.core.dsl.td
import net.sbo.guilib.core.dsl.tfoot
import net.sbo.guilib.core.dsl.th
import net.sbo.guilib.core.dsl.thead
import net.sbo.guilib.core.dsl.tr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** FakeMeasurer: 8px text is 4px per character and 10px high. */
class TableLayoutTest {
    private val ua = javaClass.getResource("/assets/guilib/css/ua.css")!!.readText()

    private class Laid(val root: UiRoot) {
        fun el(sel: String) = root.document.body.querySelector(sel) ?: error("no $sel")
        fun x(sel: String) = el(sel).getBoundingClientRect().x
        fun y(sel: String) = el(sel).getBoundingClientRect().y
        fun w(sel: String) = el(sel).getBoundingClientRect().width
        fun h(sel: String) = el(sel).getBoundingClientRect().height
    }

    /** Lays out [content] with the real ua.css plus [css]; cells have no padding and tables no spacing unless [css] says so. */
    private fun layout(css: String, plain: Boolean = true, content: NodeBuilder.() -> Unit): Laid {
        val base = if (plain) "table { border-spacing: 0 } td, th { padding: 0 } " else ""
        val root = UiRoot(
            FakeMeasurer,
            listOf(Stylesheet.parse(ua, "ua.css", Origin.USER_AGENT), Stylesheet.parse(base + css, "t.css")),
            clock = { 0L },
        )
        root.render(VComponent(component("T") { content() }, Unit, null))
        root.frame(400f, 300f)
        return Laid(root)
    }

    @Test
    fun columnsLineUpAndTheTableShrinksToItsContent() {
        val t = layout("") {
            table {
                tr { td(className = "a") { +"a" }; td(className = "b") { +"bbbb" } }
                tr { td(className = "c") { +"cccc" }; td(className = "d") { +"d" } }
            }
        }
        assertEquals(32f, t.w("table"))
        assertEquals(20f, t.h("table"))
        assertEquals(listOf(0f, 16f, 0f, 16f), listOf(t.x(".a"), t.x(".b"), t.x(".c"), t.x(".d")))
        assertEquals(listOf(16f, 16f), listOf(t.w(".a"), t.w(".d")))
        assertEquals(listOf(0f, 0f, 10f, 10f), listOf(t.y(".a"), t.y(".b"), t.y(".c"), t.y(".d")))
    }

    @Test
    fun borderSpacingAndCellPadding() {
        val t = layout("table { border-spacing: 2px 3px } td { padding: 1px }", plain = false) {
            table { tr { td(className = "a") { +"aaaa" }; td(className = "b") { +"bbbb" } }; tr { td(className = "c") { +"c" } } }
        }
        assertEquals(2f + 18f + 2f + 18f + 2f, t.w("table"))
        assertEquals(listOf(2f, 22f), listOf(t.x(".a"), t.x(".b")))
        assertEquals(listOf(3f, 3f + 12f + 3f), listOf(t.y(".a"), t.y(".c")))
        assertEquals(3f + 12f + 3f + 12f + 3f, t.h("table"))
    }

    @Test
    fun ua_defaults_giveSpacingPaddingAndBoldCenteredHeaders() {
        val t = layout("", plain = false) { table { tr { th { +"Name" } }; tr { td(className = "x") { +"x" } } } }
        // 1px spacing + 1px padding: column = 16 + 2.
        assertEquals(1f + 18f + 1f, t.w("table"))
        assertEquals(700, t.el("th").style.fontWeight)
        assertEquals(net.sbo.guilib.core.css.TextAlign.CENTER, t.el("th").style.textAlign)
    }

    @Test
    fun extraWidthGoesToAutoColumnsInProportionToTheirContent() {
        val t = layout("table { width: 100px }") { table { tr { td(className = "a") { +"a" }; td(className = "b") { +"bbbb" } } } }
        assertEquals(20f, t.w(".a"))
        assertEquals(80f, t.w(".b"))
    }

    @Test
    fun fixedAndPercentageCellWidths() {
        val fixed = layout(".a { width: 50px }") { table { tr { td(className = "a") { +"a" }; td(className = "b") { +"bb" } } } }
        assertEquals(50f, fixed.w(".a"))
        assertEquals(58f, fixed.w("table"))
        val pct = layout("table { width: 200px } .a { width: 25% }") { table { tr { td(className = "a") { +"a" }; td(className = "b") { +"bb" } } } }
        assertEquals(50f, pct.w(".a"))
        assertEquals(150f, pct.w(".b"))
    }

    @Test
    fun aNarrowTableStillFitsItsLongestWords() {
        val t = layout("table { width: 10px }") { table { tr { td(className = "a") { +"aaaa bb" }; td { +"cccccc" } } } }
        assertEquals(16f + 24f, t.w("table")) // min-content: "aaaa" and "cccccc"
        assertEquals(20f, t.h(".a")) // "aaaa" / "bb" on two lines
    }

    @Test
    fun colspanSpreadsOverItsColumns() {
        val t = layout("") {
            table {
                tr { td(className = "wide", colSpan = 2) { +"xxxxxxxxxx" } }
                tr { td(className = "a") { +"a" }; td(className = "b") { +"b" } }
            }
        }
        assertEquals(40f, t.w("table"))
        assertEquals(40f, t.w(".wide"))
        assertEquals(listOf(0f, 20f), listOf(t.x(".a"), t.x(".b")))
    }

    @Test
    fun rowspanCellCoversRowsAndPushesTheLastOne() {
        val t = layout(".tall { height: 30px }") {
            table {
                tr { td(className = "tall", rowSpan = 2) { +"t" }; td(className = "a") { +"a" } }
                tr { td(className = "b") { +"b" } }
            }
        }
        assertEquals(30f, t.h(".tall"))
        assertEquals(4f, t.x(".b")) // placed next to the rowspan cell, not under it
        assertEquals(10f, t.y(".b"))
        assertEquals(20f, t.h(".b"))
        assertEquals(30f, t.h("table"))
    }

    @Test
    fun cellsFillTheRowAndVerticalAlignPlacesTheirContent() {
        fun innerY(align: String?): Float {
            val t = layout(".tall { height: 30px } .i { height: 10px } ${align?.let { ".c { vertical-align: $it }" } ?: ""}") {
                table { tr { td(className = "tall") {}; td(className = "c") { div(className = "i") {} } } }
            }
            assertEquals(30f, t.h(".c"))
            return t.y(".i") - t.y(".c")
        }
        assertEquals(10f, innerY(null)) // ua: middle
        assertEquals(0f, innerY("top"))
        assertEquals(20f, innerY("bottom"))
        assertEquals(10f, innerY("middle"))
    }

    @Test
    fun baselineAlignedCellsShareTheRowBaseline() {
        val t = layout("td { vertical-align: baseline } .low { padding-top: 6px }") {
            table { tr { td(className = "low") { span(className = "s1") { +"a" } }; td(className = "c") { div(className = "d") { +"b" } } } }
        }
        assertEquals(6f, t.y(".d") - t.y(".c"))
        assertEquals(16f, t.h(".c"))
    }

    @Test
    fun headerAndFooterGroupsGoFirstAndLast() {
        val t = layout("") {
            table {
                tfoot { tr { td(className = "f") { +"f" } } }
                tbody { tr { td(className = "b") { +"b" } } }
                thead { tr { td(className = "h") { +"h" } } }
            }
        }
        assertEquals(listOf(0f, 10f, 20f), listOf(t.y(".h"), t.y(".b"), t.y(".f")))
        assertEquals(10f, t.y("tbody"))
        assertEquals(10f, t.y("tbody tr"))
        assertEquals(10f, t.h("tbody tr"))
    }

    @Test
    fun rowsHaveBoxesAcrossTheirCells() {
        val t = layout("table { border-spacing: 2px }", plain = true) {
            table { tbody { tr { td { +"aa" }; td { +"bb" } }; tr { td { +"c" }; td { +"d" } } } }
        }
        val second = t.el("tr:nth-child(2)")
        assertEquals(2f, second.getBoundingClientRect().x) // spans the columns, not the outer spacing
        assertEquals(8f + 2f + 8f, second.getBoundingClientRect().width)
        assertEquals(2f + 10f + 2f, second.getBoundingClientRect().y)
        assertEquals(2f, t.y("tbody"))
        assertEquals(10f + 2f + 10f, t.h("tbody"))
    }

    @Test
    fun collapsedBordersOverlap() {
        val css = "td { border: 1px solid red } table { border: 1px solid red }"
        val separate = layout(css) { table { tr { td(className = "a") { +"aaaa" }; td(className = "b") { +"bbbb" } } } }
        assertEquals(1f + 18f + 18f + 1f, separate.w("table"))
        val collapsed = layout("$css table { border-collapse: collapse; padding: 5px }") {
            table { tr { td(className = "a") { +"aaaa" }; td(className = "b") { +"bbbb" } }; tr { td(className = "c") { +"c" } } }
        }
        // Each shared line is drawn once: | aaaa | bbbb | = 1 + 16 + 1 + 16 + 1.
        assertEquals(35f, collapsed.w("table"))
        assertEquals(listOf(0f, 17f), listOf(collapsed.x(".a"), collapsed.x(".b")))
        assertEquals(listOf(0f, 11f), listOf(collapsed.y(".a"), collapsed.y(".c")))
        assertEquals(23f, collapsed.h("table"))
    }

    @Test
    fun spanningCellWithCollapsedBordersGetsItsFullWidth() {
        // The shared border inside the span is drawn once, so the columns must make up for that 1px.
        val t = layout("td { border: 1px solid red } table { border-collapse: collapse }") {
            table { tr { td { +"a" }; td { +"b" } }; tr { td(className = "span", colSpan = 2) { +"xxxxxxxxxx" } } }
        }
        assertEquals(42f, t.w(".span"))
        assertEquals(12f, t.h(".span")) // one line
    }

    @Test
    fun captionSitsAboveOrBelow() {
        val top = layout("") { table { caption(className = "cap") { +"Title" }; tr { td(className = "a") { +"a" } } } }
        assertEquals(0f, top.y(".cap"))
        assertEquals(10f, top.y(".a"))
        assertEquals(20f, top.w("table")) // as wide as the caption
        // Like the web, the caption is outside the table's border box (its border and background don't wrap it).
        assertEquals(10f, top.y("table"))
        assertEquals(10f, top.h("table"))
        val bottom = layout("caption { caption-side: bottom } .after { height: 5px }") {
            table { caption(className = "cap") { +"Title" }; tr { td(className = "a") { +"a" } } }
            div(className = "after") {}
        }
        assertEquals(0f, bottom.y(".a"))
        assertEquals(10f, bottom.y(".cap"))
        assertEquals(10f, bottom.h("table"))
        assertEquals(20f, bottom.y(".after")) // the caption takes room in the flow
    }

    @Test
    fun fixedTableLayoutUsesTheFirstRowOnly() {
        val t = layout("table { table-layout: fixed; width: 100px } .a { width: 30px }") {
            table {
                tr { td(className = "a") { +"a" }; td(className = "b") { +"b" } }
                tr { td { +"wwwwwwwwwwwwwwwwwwww" }; td { +"x" } }
            }
        }
        assertEquals(30f, t.w(".a"))
        assertEquals(70f, t.w(".b"))
        assertEquals(100f, t.w("table"))
    }

    @Test
    fun tallerTableStretchesItsRows() {
        val t = layout("table { height: 40px }") { table { tr { td(className = "a") { +"a" } }; tr { td(className = "b") { +"b" } } } }
        assertEquals(20f, t.h(".a"))
        assertEquals(20f, t.y(".b"))
    }

    @Test
    fun cellsWithoutRowsAndRowsWithoutGroupsGetImplicitOnes() {
        val t = layout("") { table { td(className = "a") { +"a" }; td(className = "b") { +"b" }; tr { td(className = "c") { +"c" } } } }
        assertEquals(listOf(0f, 4f, 0f), listOf(t.x(".a"), t.x(".b"), t.x(".c")))
        assertEquals(10f, t.y(".c"))
    }

    @Test
    fun inlineTableSitsInTheLine() {
        val t = layout(".it { display: inline-table }") {
            div { +"ab"; table(className = "it") { tr { td { +"x" } } }; +"c" }
        }
        assertEquals(8f, t.x(".it"))
        assertEquals(4f, t.w(".it"))
    }

    @Test
    fun hiddenCellsAndRowsTakeNoSlot() {
        val t = layout(".gone { display: none }") {
            table {
                tr(className = "gone") { td { +"zzzzzzzz" } }
                tr { td(className = "gone") { +"y" }; td(className = "a") { +"a" } }
            }
        }
        assertEquals(0f, t.x(".a"))
        assertEquals(0f, t.y(".a"))
        assertEquals(4f, t.w("table"))
        assertEquals(false, (t.el("tr.gone") as Element).box.visible)
    }
}
