package net.sbo.guilib.core.controls

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.css.TransformFn
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.ComponentScope
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.sortableList
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SortableTest {
    private val ua = """
        div { display: block }
        span { display: inline }
        button { display: inline-flex }
        .guilib-sortable { display: flex; flex-direction: column; gap: 2px }
        .guilib-sortable.horizontal { flex-direction: row }
        .guilib-sortable-item { height: 10px; width: 40px }
        .s { height: 20px; overflow: auto }
    """.trimIndent()

    private var order = listOf("a", "b", "c", "d")
    private var clicks = 0

    private fun ui(horizontal: Boolean = false, handle: Boolean = false, scrolled: Boolean = false): UiRoot {
        val app = component("T") {
            var items by useState(order)
            fun list(b: net.sbo.guilib.core.dsl.NodeBuilder) = b.sortableList(
                items, key = { it }, onReorder = { items = it; order = it }, horizontal = horizontal, handle = handle,
            ) { item, _ ->
                if (handle) span(className = "guilib-drag-handle") { +"=" }
                button(className = "btn-$item", disabled = item == "z", onClick = { clicks++ }) { +item }
            }
            if (scrolled) div(className = "s") { list(this) } else list(this)
        }
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT)), clock = { 0L })
        root.render(VComponent(app, Unit, null))
        root.frame(300f, 200f)
        return root
    }

    private fun UiRoot.items() = document.body.querySelectorAll(".guilib-sortable-item")

    /** Item `i` is at y = 12·i (height 10, gap 2). */
    private fun UiRoot.drag(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        input.mouseDown(fromX, fromY, 0)
        input.mouseMove((fromX + toX) / 2f, (fromY + toY) / 2f); frame(300f, 200f)
        input.mouseMove(toX, toY); frame(300f, 200f)
    }

    @Test
    fun draggingDownReordersOnDrop() {
        val root = ui()
        root.drag(1f, 5f, 1f, 31f) // "a" (center 5) dragged past "c" (center 29), starting on its button
        // While dragging, the dragged item follows the mouse and "b"/"c" move up by one slot (10 + 2 gap).
        val items = root.items()
        assertTrue(items[0].classList.contains("dragging"))
        assertEquals(-12f, (items[1].style.transform.single() as TransformFn.Translate).y.resolve(0f)!!, 0.01f)
        assertEquals(-12f, (items[2].style.transform.single() as TransformFn.Translate).y.resolve(0f)!!, 0.01f)
        assertEquals(0f, (items[3].style.transform.single() as TransformFn.Translate).y.resolve(0f)!!, 0.01f)
        root.input.mouseUp(1f, 31f, 0); root.frame(300f, 200f)
        assertEquals(listOf("b", "c", "a", "d"), order)
        assertTrue(root.items().none { it.classList.contains("dragging") })
        assertEquals(0, clicks) // the release after a drag doesn't click the button under the mouse
    }

    @Test
    fun draggingUpAndHorizontal() {
        val root = ui()
        root.drag(5f, 41f, 5f, 3f) // "d" (y 36..46) dragged to the top
        root.input.mouseUp(5f, 3f, 0)
        assertEquals(listOf("d", "a", "b", "c"), order)

        order = listOf("a", "b", "c", "d")
        val h = ui(horizontal = true)
        // Items are 40 wide with a 2px gap (centers 20, 62, 104, 146): "a" moved by 85 has its center at 105.
        h.drag(5f, 5f, 90f, 5f)
        h.input.mouseUp(90f, 5f, 0)
        assertEquals(listOf("b", "c", "a", "d"), order)
    }

    @Test
    fun smallMovesStillClickAndEscapeCancels() {
        val root = ui()
        root.input.mouseDown(1f, 5f, 0); root.input.mouseMove(2f, 6f); root.input.mouseUp(2f, 6f, 0) // on the button
        assertEquals(1, clicks)
        assertEquals(listOf("a", "b", "c", "d"), order)

        root.drag(5f, 5f, 5f, 29f)
        root.input.keyDown("Escape", 256)
        root.input.mouseUp(5f, 29f, 0); root.frame(300f, 200f)
        assertEquals(listOf("a", "b", "c", "d"), order)
    }

    @Test
    fun handleModeOnlyStartsOnTheHandle() {
        val root = ui(handle = true)
        val btn = root.document.body.querySelector(".btn-a")!!.getBoundingClientRect()
        root.drag(btn.x + 1f, 5f, btn.x + 1f, 29f)
        root.input.mouseUp(btn.x + 1f, 29f, 0)
        assertEquals(listOf("a", "b", "c", "d"), order)
        root.drag(1f, 5f, 1f, 31f) // the "=" handle is at the left edge
        root.input.mouseUp(1f, 31f, 0)
        assertEquals(listOf("b", "c", "a", "d"), order)
    }

    @Test
    fun releasingOverADisabledElementStillEndsTheDrag() {
        order = listOf("a", "b", "c", "z")
        val root = ui()
        root.drag(1f, 5f, 1f, 31f)
        root.input.mouseUp(1f, 41f, 0); root.frame(300f, 200f) // over the disabled button of "z"
        assertEquals(listOf("b", "c", "z", "a"), order) // dragged to the end, then dropped
        assertTrue(root.items().none { it.classList.contains("dragging") || it.style.transform.isNotEmpty() })
    }

    @Test
    fun scrollingWhileDraggingKeepsPositionsRight() {
        val root = ui(scrolled = true)
        root.drag(1f, 5f, 1f, 18f) // "a" moved 13px: just past "b"
        root.input.wheel(1f, 18f, 0f, 12f); root.frame(300f, 200f) // the list moves up 12px under the still mouse
        root.input.mouseUp(1f, 18f, 0); root.frame(300f, 200f)
        // Relative to the list the mouse moved 25px, past the center of "c" (29 - 5 = 24).
        assertEquals(listOf("b", "c", "a", "d"), order)
    }

    @Test
    fun aNewPressClearsADragWhoseReleaseNeverArrived() {
        val root = ui()
        root.drag(1f, 5f, 1f, 31f)
        root.input.mouseDown(1f, 41f, 0); root.frame(300f, 200f) // the release got lost; press again
        assertTrue(root.items().none { it.classList.contains("dragging") })
        root.input.mouseUp(1f, 41f, 0)
        assertEquals(listOf("a", "b", "c", "d"), order)
    }
}
