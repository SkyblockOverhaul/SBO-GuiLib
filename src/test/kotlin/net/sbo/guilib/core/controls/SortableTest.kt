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
import net.sbo.guilib.core.event.Modifiers
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

    /** The real ua.css transitions; only used where the test drives the clock. */
    private val transitions = """
        .guilib-sortable.sorting .guilib-sortable-item { transition: transform 120ms ease-out }
        .guilib-sortable.sorting .guilib-sortable-item.dragging { transition: none; position: relative; z-index: 10 }
    """.trimIndent()

    private var order = listOf("a", "b", "c", "d")
    private var clicks = 0
    private var now = 0L

    private fun ui(horizontal: Boolean = false, handle: Boolean = false, scrolled: Boolean = false, animated: Boolean = false): UiRoot {
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
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(if (animated) "$ua\n$transitions" else ua, "ua", Origin.USER_AGENT)), clock = { now })
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

    @Test
    fun aFastDropDuringTheShiftTransitionLeavesNoItemOffset() {
        val root = ui(animated = true)
        // Grab "a", flick it past "c" and let go 30ms later: "b" and "c" are still mid-way through their 120ms shift.
        root.input.mouseDown(1f, 5f, 0); root.frame(300f, 200f)
        now += 16; root.input.mouseMove(1f, 31f); root.frame(300f, 200f)
        now += 16; root.frame(300f, 200f)
        assertTrue(root.items()[1].style.transform.isNotEmpty()) // "b" is shifting
        now += 16; root.input.mouseUp(1f, 31f, 0); root.frame(300f, 200f)
        assertEquals(listOf("b", "c", "a", "d"), order)
        for (i in 0 until 10) { now += 16; root.frame(300f, 200f) }
        val tops = root.items().map { it.getBoundingClientRect().y }
        assertEquals(listOf(0f, 12f, 24f, 36f), tops) // every item sits in its slot, none keeps a stale offset
        assertTrue(root.items().none { it.style.transform.isNotEmpty() })
    }

    @Test
    fun altArrowsMoveTheFocusedItemAndKeepFocus() {
        val root = ui()
        val a = root.items()[0]
        root.document.focus(a)
        root.input.keyDown("ArrowDown", 264, Modifiers(alt = true)); root.frame(300f, 200f)
        assertEquals(listOf("b", "a", "c", "d"), order)
        root.input.keyDown("End", 269, Modifiers(alt = true)); root.frame(300f, 200f)
        assertEquals(listOf("b", "c", "d", "a"), order)
        assertTrue(root.document.focusedElement === root.items()[3]) // the same element moved, focus stayed on it
        root.input.keyDown("ArrowDown", 264); root.frame(300f, 200f) // without Alt nothing happens
        assertEquals(listOf("b", "c", "d", "a"), order)
    }

    @Test
    fun draggingNearTheEdgeOfAScrollContainerScrollsIt() {
        val root = ui(scrolled = true, animated = true)
        val scroller = root.document.body.querySelector(".s")!!
        root.input.mouseDown(1f, 5f, 0); root.frame(300f, 200f)
        now += 16; root.input.mouseMove(1f, 19f); root.frame(300f, 200f) // 1px above the bottom edge of the 20px box
        repeat(10) { now += 16; root.frame(300f, 200f) }
        assertTrue(scroller.scrollTop > 10f, "scrollTop ${scroller.scrollTop}")
        // The item kept following the (still) mouse: it was carried down the list.
        root.input.mouseUp(1f, 19f, 0); root.frame(300f, 200f)
        assertTrue(order.indexOf("a") >= 2, order.toString())
    }

    private var left = listOf("a", "b")
    private var right = listOf("x", "y", "z")

    /** Two lists in one group side by side: left at x 0..40, right at x 100..140 (items 10 high, gap 2). */
    private fun groups(exitMs: Long = 0): UiRoot {
        val app = component("G") {
            var l by useState(left)
            var r by useState(right)
            div(className = "row") {
                sortableList(l, key = { it }, onReorder = { l = it; left = it }, group = "g", className = "left", exitMs = exitMs) { item, _ -> span { +item } }
                sortableList(r, key = { it }, onReorder = { r = it; right = it }, group = "g", className = "right", exitMs = exitMs) { item, _ -> span { +item } }
            }
        }
        val css = "$ua .row { display: flex; gap: 60px } .guilib-sortable { min-height: 20px } .guilib-sortable-item.dragging.away { visibility: hidden }"
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(css, "ua", Origin.USER_AGENT)), clock = { now })
        root.render(VComponent(app, Unit, null))
        root.frame(300f, 200f)
        return root
    }

    @Test
    fun itemsMoveBetweenListsOfOneGroup() {
        val root = groups()
        root.input.mouseDown(5f, 5f, 0); root.frame(300f, 200f)
        root.input.mouseMove(20f, 5f); root.frame(300f, 200f)
        root.input.mouseMove(110f, 13f); root.frame(300f, 200f) // over the right list, between "x" (center 5) and "y" (center 17)
        // A ghost follows the mouse; the original is hidden, the right list opens a gap at index 1.
        val ghost = root.document.body.querySelectorAll(".guilib-sortable-ghost")
        assertEquals(1, ghost.size)
        val rightItems = root.document.body.querySelectorAll(".right .guilib-sortable-item")
        assertEquals(0f, (rightItems[0].style.transform.single() as TransformFn.Translate).y.resolve(0f)!!, 0.01f)
        assertEquals(12f, (rightItems[1].style.transform.single() as TransformFn.Translate).y.resolve(0f)!!, 0.01f)
        assertTrue(root.document.body.querySelector(".right")!!.classList.contains("receiving"))
        root.input.mouseUp(110f, 13f, 0); root.frame(300f, 200f)
        assertEquals(listOf("b"), left)
        assertEquals(listOf("x", "a", "y", "z"), right)
        assertTrue(root.document.body.querySelectorAll(".guilib-sortable-ghost").isEmpty())
    }

    @Test
    fun droppingOutsideEveryListCancels() {
        val root = groups()
        root.input.mouseDown(5f, 5f, 0); root.frame(300f, 200f)
        root.input.mouseMove(60f, 80f); root.frame(300f, 200f)
        assertTrue(root.document.body.querySelector(".left .dragging")!!.classList.contains("away"))
        root.input.mouseUp(60f, 80f, 0); root.frame(300f, 200f)
        assertEquals(listOf("a", "b"), left)
        assertEquals(listOf("x", "y", "z"), right)
    }

    /** A list with exit animations; `remove` drops an item like a ✕ button would. */
    private fun exiting(): Pair<UiRoot, (String) -> Unit> {
        var remove: (String) -> Unit = {}
        val app = component("E") {
            var items by useState(order)
            remove = { x -> items = items - x; order = items }
            sortableList(items, key = { it }, onReorder = { items = it; order = it }, exitMs = 200) { item, _ -> span { +item } }
        }
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT)), clock = { now })
        root.render(VComponent(app, Unit, null))
        root.frame(300f, 200f)
        return root to { x: String -> remove(x) }
    }

    private fun text(n: net.sbo.guilib.core.dom.Node): String =
        when (n) {
            is net.sbo.guilib.core.dom.TextNode -> n.data
            is net.sbo.guilib.core.dom.Element -> n.children.joinToString("") { text(it) }
        }

    private fun UiRoot.texts() = items().map { text(it) }

    @Test
    fun removedItemsStayInPlaceWhileTheyLeave() {
        val (root, remove) = exiting()
        remove("b"); root.frame(300f, 200f)
        // "b" is still rendered at its old slot, marked as leaving; the others keep their positions.
        assertEquals(listOf("a", "b", "c", "d"), root.texts())
        assertTrue(root.items()[1].classList.contains("leaving"))
        assertTrue(root.items().filter { text(it) != "b" }.none { it.classList.contains("leaving") })
        // Pressing on a leaving item starts no drag.
        root.drag(1f, 17f, 1f, 41f)
        root.input.mouseUp(1f, 41f, 0); root.frame(300f, 200f)
        assertEquals(listOf("a", "c", "d"), order)
        now += 200; root.frame(300f, 200f); root.frame(300f, 200f)
        assertEquals(listOf("a", "c", "d"), root.texts())
        // Afterwards dragging works on the remaining items: "a" past "c" (now at y 12..22).
        root.drag(1f, 5f, 1f, 19f)
        root.input.mouseUp(1f, 19f, 0); root.frame(300f, 200f)
        assertEquals(listOf("c", "a", "d"), order)
    }

    @Test
    fun itemsDraggedIntoAnotherListOfTheGroupDontLeave() {
        val root = groups(exitMs = 200)
        root.input.mouseDown(5f, 5f, 0); root.frame(300f, 200f)
        root.input.mouseMove(20f, 5f); root.frame(300f, 200f)
        root.input.mouseMove(110f, 13f); root.frame(300f, 200f)
        root.input.mouseUp(110f, 13f, 0); root.frame(300f, 200f)
        assertEquals(listOf("b"), left)
        assertEquals(listOf("b"), root.document.body.querySelectorAll(".left .guilib-sortable-item").map { text(it) })
    }
}
