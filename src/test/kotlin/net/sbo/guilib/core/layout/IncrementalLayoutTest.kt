package net.sbo.guilib.core.layout

import net.sbo.guilib.core.FrameStats
import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.ComponentScope
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.dsl.table
import net.sbo.guilib.core.dsl.td
import net.sbo.guilib.core.dsl.tr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Incremental layout: unchanged subtrees keep their layout. Every frame in these tests (like in all tests) is also
 * compared with a full layout by [LayoutCheck], so they double as correctness checks for the tricky cases.
 */
class IncrementalLayoutTest {
    private var now = 0L

    private fun ui(css: String, content: ComponentScope.() -> Unit): UiRoot {
        val ua = "div { display: block } span { display: inline }"
        val root = UiRoot(
            FakeMeasurer,
            listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT), Stylesheet.parse(css, "t", Origin.AUTHOR)),
            clock = { now },
        )
        root.render(VComponent(component("T") { content() }, Unit, null))
        frame(root)
        return root
    }

    private fun frame(root: UiRoot) = root.frame(400f, 300f)

    /** Nodes laid out by the next frame (without the full-layout check, which would lay out everything again). */
    private fun nodesLaidOut(root: UiRoot, change: () -> Unit): Long {
        val verify = LayoutCheck.enabled
        LayoutCheck.enabled = false
        try {
            change()
            FrameStats.reset()
            frame(root)
            return FrameStats.nodeLayouts
        } finally {
            LayoutCheck.enabled = verify
        }
    }

    private fun UiRoot.q(sel: String): Element = document.body.querySelector(sel)!!

    @Test
    fun changingOneRowOnlyLaysOutThatRowAndItsAncestors() {
        val root = ui(".row { padding: 2px } .bar { height: 4px }") {
            div(className = "list") {
                for (i in 0 until 60) div(className = "row r$i") { span { +"Row $i" }; div(className = "bar") }
            }
        }
        val all = nodesLaidOut(root) { root.document.invalidateLayout() }
        val one = nodesLaidOut(root) { root.q(".r30 .bar").setStyleProperty("height", "9px") }
        assertTrue(all > 100, "full layout: $all")
        assertTrue(one <= 6, "one changed row: $one nodes")
        // The rows below moved.
        assertEquals(root.q(".r30").box.y + root.q(".r30").box.height, root.q(".r31").box.y)
        // Nothing changed: no layout at all.
        assertEquals(0L, nodesLaidOut(root) {})
    }

    @Test
    fun anAnimatedItemInAFlexRowDoesNotRelayoutItsSiblings() {
        val root = ui(
            """
            .bar { display: flex; gap: 2px }
            .item { padding: 1px }
            .grow { flex: 1 }
            .pulse { width: 10px; animation: w 1000ms linear infinite }
            @keyframes w { to { width: 40px } }
            """,
        ) {
            div(className = "bar") {
                for (i in 0 until 30) div(className = if (i == 5) "item pulse" else "item") { span { +"Item $i" } }
                div(className = "item grow") { span { +"rest" } }
            }
        }
        repeat(5) { now += 16; frame(root) } // checked against a full layout every frame
        now += 16
        val perFrame = nodesLaidOut(root) {}
        assertTrue(perFrame <= 10, "animated frame: $perFrame nodes")
    }

    @Test
    fun trickyLayoutsStayIdenticalToAFullLayout() {
        val root = ui(
            """
            .rel { position: relative; height: 40px }
            .abs { position: absolute; right: 3px; bottom: 2px; width: 20%; height: 5px }
            .scroll { overflow: auto; height: 30px }
            .col { display: flex; flex-direction: column; height: 80px }
            .col > div { flex: 1; min-height: 5px }
            .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(30px, 1fr)); gap: 2px }
            .ib { display: inline-block; width: 12px; height: 6px }
            .clamp { line-clamp: 2; width: 60px }
            .t { display: table; border-collapse: collapse }
            .t td { display: table-cell; vertical-align: middle; padding: 1px }
            .t tr { display: table-row }
            .pct { height: 50%; }
            .tag::after { content: attr(title) }
            .shrink { display: inline-flex }
            """,
        ) {
            div(className = "rel") { div(className = "abs"); div(className = "scroll") { div(className = "tall") { +"x" } } }
            div(className = "col") { div(className = "c1") { +"a" }; div(className = "c2") { +"b" }; div(className = "c3") }
            div(className = "grid") { for (i in 0 until 5) div(className = "g g$i") { +"g$i" } }
            div(className = "para") { +"text "; span(className = "ib"); +" more text "; span(className = "inl") { +"inline" } }
            div(className = "clamp") { +"one two three four five six seven eight nine ten" }
            table(className = "t") {
                tr { td(className = "cell") { +"a" }; td { +"b b b" } }
                tr { td(rowSpan = 2) { +"c" }; td(className = "cell2") { +"d" } }
                tr { td { +"e" } }
            }
            div(className = "rel") { div(className = "pct") }
            div(className = "tag", title = "x")
            div { span(className = "shrink") { div(className = "sh") { +"shrink" } } }
        }
        val changes = listOf<() -> Unit>(
            { root.q(".abs").setStyleProperty("width", "40%") },
            { root.q(".tall").setStyleProperty("height", "90px") },
            { root.q(".c2").setStyleProperty("min-height", "30px") },
            { root.q(".g3").setStyleProperty("height", "20px") },
            { root.q(".ib").setStyleProperty("width", "30px") },
            { root.q(".inl").setStyleProperty("font-size", "14px") },
            { root.q(".clamp").setStyleProperty("width", "90px") },
            { root.q(".cell").setStyleProperty("height", "15px") },
            { root.q(".cell2").setStyleProperty("padding", "6px") },
            { root.q(".pct").setStyleProperty("height", "25%") },
            { root.q(".tag").setAttribute("title", "longer title") },
            { root.q(".sh").setStyleProperty("padding", "4px") },
            { root.q(".grid").setStyleProperty("width", "100px") },
            { root.q(".abs").setStyleProperty("display", "none") },
            { root.q(".abs").removeStyleProperty("display") },
        )
        for (change in changes) {
            change()
            now += 16
            frame(root) // throws if the incremental layout differs from a full one
        }
    }
}
