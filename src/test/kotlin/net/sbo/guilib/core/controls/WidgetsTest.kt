package net.sbo.guilib.core.controls

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.TextNode
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.ComponentScope
import net.sbo.guilib.core.dsl.chips
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.radioGroup
import net.sbo.guilib.core.dsl.segmented
import net.sbo.guilib.core.dsl.contextMenu
import net.sbo.guilib.core.dsl.details
import net.sbo.guilib.core.dsl.multiSelect
import net.sbo.guilib.core.dsl.select
import net.sbo.guilib.core.dsl.tabs
import net.sbo.guilib.core.dsl.useToast
import net.sbo.guilib.core.dsl.useEscapeBack
import net.sbo.guilib.core.dsl.modal
import net.sbo.guilib.core.controls.ToastAction
import net.sbo.guilib.core.controls.Toaster
import net.sbo.guilib.core.event.EventType
import net.sbo.guilib.core.event.MouseEvent
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WidgetsTest {
    private var now = 0L

    private val ua = """
        div { display: block } span { display: inline }
        .guilib-radio-group, .guilib-segmented, .guilib-chips, .guilib-tab-list { display: flex; position: relative }
        .guilib-radio, .guilib-segment, .guilib-chip, .guilib-tab { width: 30px; height: 10px }
        .guilib-segment-indicator, .guilib-tab-indicator { position: absolute; transition: left 100ms linear, width 100ms linear }
        .guilib-tabs.underline .guilib-tab-indicator { bottom: 0; height: 2px }
        .guilib-segmented { padding: 2px }
        .guilib-collapse { overflow: hidden; transition: height 100ms linear }
        .guilib-collapse-inner { display: flex; flex-direction: column }
        .guilib-details-summary { height: 10px }
        .block { height: 30px; margin-top: 5px }
        #guilib-overlay { position: fixed; left: 0; top: 0; width: 100%; height: 100%; pointer-events: none; z-index: 100 }
        .guilib-portal { position: absolute; left: 0; top: 0; width: 100%; height: 100%; pointer-events: none }
        .guilib-portal > * { pointer-events: auto }
        .guilib-menu { position: fixed; width: 60px }
        .guilib-menu-item, .guilib-menu-header { height: 10px }
        .guilib-menu-separator { height: 2px }
        .guilib-toasts { position: fixed; right: 0; bottom: 0; pointer-events: none }
        .guilib-toast { width: 80px; height: 20px; pointer-events: auto }
        .anchor { height: 20px }
        select { display: inline-flex; width: 80px; height: 10px }
        input { display: inline-block; position: relative; overflow: hidden; white-space: pre; width: 60px }
        .guilib-option { height: 10px }
    """.trimIndent()

    private fun ui(content: ComponentScope.() -> Unit): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT)), clock = { now })
        root.render(VComponent(component("T") { content() }, Unit, null))
        root.frame(300f, 200f)
        return root
    }

    private fun UiRoot.all(sel: String): List<Element> = document.body.querySelectorAll(sel)

    private fun UiRoot.click(el: Element) {
        val r = el.getBoundingClientRect()
        input.mouseDown(r.x + 1f, r.y + 1f, 0)
        input.mouseUp(r.x + 1f, r.y + 1f, 0)
        frame(300f, 200f)
    }

    private fun UiRoot.key(key: String) {
        input.keyDown(key, 0)
        frame(300f, 200f)
    }

    private fun UiRoot.settle() {
        repeat(3) { now += 200; frame(300f, 200f) }
    }

    @Test
    fun radioGroupSelectsByClickAndArrowsSkippingDisabled() {
        var value = "1"
        val root = ui {
            var v by useState("1")
            value = v
            radioGroup(value = v, onChange = { v = it }) {
                option("1", "Solo")
                option("2", "Duo")
                option("3", "Trio", disabled = true)
                option("4", "Full")
            }
        }
        val radios = root.all(".guilib-radio")
        // Only the selected radio is a Tab stop.
        assertEquals(listOf(0, -1, -1, -1), radios.map { it.getAttribute("tabindex") })
        root.click(radios[1])
        assertEquals("2", value)
        assertTrue(root.all(".guilib-radio")[1].classList.contains("checked"))
        root.key("ArrowRight")
        assertEquals("4", value)
        assertEquals(root.all(".guilib-radio")[3], root.document.focusedElement)
        root.key("ArrowRight")
        assertEquals("1", value)
        root.click(root.all(".guilib-radio")[2])
        assertEquals("1", value)
    }

    @Test
    fun segmentedIndicatorFollowsTheSelection() {
        val root = ui {
            var v by useState("a")
            segmented(value = v, onChange = { v = it }) {
                option("a", "A")
                option("b", "B")
                option("c", "C")
            }
        }
        root.settle()
        val indicator = root.all(".guilib-segment-indicator").single()
        fun left() = indicator.getBoundingClientRect().x
        val segs = root.all(".guilib-segment")
        assertEquals(segs[0].getBoundingClientRect().x, left(), 0.01f)
        // The first position is applied without a transition.
        assertEquals(segs[0].getBoundingClientRect().width, indicator.getBoundingClientRect().width, 0.01f)
        root.click(segs[2])
        // Animating: halfway between after 50 ms, at the target once settled.
        now += 50; root.frame(300f, 200f)
        val mid = left()
        assertTrue(mid > segs[0].getBoundingClientRect().x && mid < segs[2].getBoundingClientRect().x, "mid=$mid")
        root.settle()
        assertEquals(segs[2].getBoundingClientRect().x, left(), 0.01f)
    }

    @Test
    fun chipsToggleAndKeepOptionOrder() {
        var picked = emptyList<String>()
        val root = ui {
            var v by useState(listOf<String>())
            picked = v
            chips(values = v, onChange = { v = it }) {
                option("trophy", "Trophy")
                option("lava", "Lava")
                option("water", "Water")
            }
        }
        root.click(root.all(".guilib-chip")[2])
        root.click(root.all(".guilib-chip")[0])
        assertEquals(listOf("trophy", "water"), picked)
        root.click(root.all(".guilib-chip")[2])
        assertEquals(listOf("trophy"), picked)
        root.document.focus(root.all(".guilib-chip")[1])
        root.key(" ")
        assertEquals(listOf("trophy", "lava"), picked)
    }

    @Test
    fun tabsRenderTheActiveContentAndSwitchWithArrows() {
        var tab = ""
        val root = ui {
            var v by useState("d")
            tab = v
            tabs(value = v, onChange = { v = it }) {
                tab("d", "Dungeons") { div(className = "content-d") }
                tab("k", "Kuudra") { div(className = "content-k") }
                tab("x", "Locked", disabled = true)
            }
        }
        assertNotNull(root.document.body.querySelector(".content-d"))
        assertNull(root.document.body.querySelector(".content-k"))
        root.click(root.all(".guilib-tab")[1])
        assertEquals("k", tab)
        assertNotNull(root.document.body.querySelector(".content-k"))
        root.settle()
        val indicator = root.all(".guilib-tab-indicator").single().getBoundingClientRect()
        val active = root.all(".guilib-tab")[1].getBoundingClientRect()
        assertEquals(active.x, indicator.x, 0.01f)
        assertEquals(active.bottom, indicator.bottom, 0.01f)
        // The disabled tab is skipped.
        root.key("ArrowRight")
        assertEquals("d", tab)
        val label = (root.all(".guilib-tab")[0].children.single() as TextNode).data
        assertEquals("Dungeons", label)
    }

    private fun UiRoot.overlay(sel: String): List<Element> = document.overlayRoot.querySelectorAll(sel)

    @Test
    fun detailsAnimateToTheContentHeightAndUnmountWhenClosed() {
        val root = ui {
            details("Party") { div(className = "block") }
        }
        val collapse = root.all(".guilib-collapse").single()
        assertEquals(0f, collapse.getBoundingClientRect().height, 0.01f)
        root.click(root.all(".guilib-details-summary").single())
        assertTrue(root.all(".guilib-details").single().classList.contains("open"))
        now += 50; root.frame(300f, 200f)
        val mid = collapse.getBoundingClientRect().height
        assertTrue(mid > 0f && mid < 35f, "mid=$mid")
        root.settle()
        // The child's margin is part of the height.
        assertEquals(35f, collapse.getBoundingClientRect().height, 0.01f)
        root.click(root.all(".guilib-details-summary").single())
        assertNotNull(root.document.body.querySelector(".block"))
        root.settle()
        assertEquals(0f, collapse.getBoundingClientRect().height, 0.01f)
        assertNull(root.document.body.querySelector(".block"))
    }

    @Test
    fun toastsStackDisappearAndDismissOnClick() {
        val root = ui {
            val toast = useToast()
            useEffect {
                toast.success("Party created")
                toast.error("Server not reachable", durationMs = 0)
            }
        }
        root.frame(300f, 200f)
        assertEquals(2, root.overlay(".guilib-toast").size)
        assertTrue(root.overlay(".guilib-toast")[0].classList.contains("success"))
        now += net.sbo.guilib.core.controls.Toaster.DEFAULT_DURATION_MS + 10; root.frame(300f, 200f)
        assertTrue(root.overlay(".guilib-toast")[0].classList.contains("leaving"))
        now += 300; root.frame(300f, 200f)
        val left = root.overlay(".guilib-toast")
        assertEquals(1, left.size)
        assertTrue(left[0].classList.contains("error"))
        root.click(left[0])
        now += 300; root.frame(300f, 200f)
        assertEquals(0, root.overlay(".guilib-toast").size)
    }

    @Test
    fun toastActionRunsOnceAndDismissesTheToast() {
        var undone = 0
        val root = ui {
            val toast = useToast()
            useEffect { toast.success("Event deleted", action = ToastAction("Undo") { undone++ }) }
        }
        root.frame(300f, 200f)
        val action = root.overlay(".guilib-toast-action").single()
        assertEquals("Undo", (action.children.single() as TextNode).data)
        root.click(action)
        assertEquals(1, undone)
        assertTrue(root.overlay(".guilib-toast")[0].classList.contains("leaving"))
        now += 300; root.frame(300f, 200f)
        assertEquals(0, root.overlay(".guilib-toast").size)
    }

    private fun UiRoot.hover(el: Element?) {
        if (el == null) input.mouseMove(1f, 1f) else el.getBoundingClientRect().let { input.mouseMove(it.x + 1f, it.y + 1f) }
        frame(300f, 200f)
    }

    @Test
    fun aHoveredToastWaitsAndContinuesWithTheTimeLeftAfterTheMouseLeaves() {
        val root = ui {
            val toast = useToast()
            useEffect { toast.error("Missing: Catacombs 30, Magical Power 600", durationMs = 4000) }
        }
        root.frame(300f, 200f)
        now += 1000; root.frame(300f, 200f)
        root.hover(root.overlay(".guilib-toast").single())
        now += 10_000; root.frame(300f, 200f)
        assertFalse(root.overlay(".guilib-toast").single().classList.contains("leaving"))
        root.hover(null)
        now += 2900; root.frame(300f, 200f)
        assertFalse(root.overlay(".guilib-toast").single().classList.contains("leaving"))
        now += 200; root.frame(300f, 200f)
        assertTrue(root.overlay(".guilib-toast").single().classList.contains("leaving"))
    }

    @Test
    fun aToastHoveredUntilItsLastMomentGetsAtLeastOneAndAHalfSecondsAfterLeaving() {
        val root = ui {
            val toast = useToast()
            useEffect { toast.info("Copied", durationMs = 1000) }
        }
        root.frame(300f, 200f)
        now += 990; root.frame(300f, 200f)
        root.hover(root.overlay(".guilib-toast").single())
        now += 5000; root.frame(300f, 200f)
        root.hover(null)
        now += Toaster.MIN_RESUME_MS - 10; root.frame(300f, 200f)
        assertFalse(root.overlay(".guilib-toast").single().classList.contains("leaving"))
        now += 20; root.frame(300f, 200f)
        assertTrue(root.overlay(".guilib-toast").single().classList.contains("leaving"))
    }

    @Test
    fun pauseOnHoverFalseKeepsTheTimerRunningAndClickStillDismissesAHoveredToast() {
        val root = ui {
            val toast = useToast()
            useEffect {
                toast.info("Plain", durationMs = 1000, pauseOnHover = false)
                toast.info("Paused", durationMs = 3000)
            }
        }
        root.frame(300f, 200f)
        val (plain, paused) = root.overlay(".guilib-toast")
        root.hover(plain)
        now += 1100; root.frame(300f, 200f)
        assertTrue(plain.classList.contains("leaving"))
        root.hover(paused)
        now += 5000; root.frame(300f, 200f)
        assertFalse(paused.classList.contains("leaving"))
        root.click(paused)
        assertTrue(paused.classList.contains("leaving"))
    }

    @Test
    fun theHoveredToastIsNotEvictedWhenTooManyAreShown() {
        lateinit var toaster: Toaster
        val root = ui {
            val toast = useToast()
            toaster = toast
            useEffect { toast.error("Read me", durationMs = 0) }
        }
        root.frame(300f, 200f)
        val first = root.overlay(".guilib-toast").single()
        root.hover(first)
        repeat(Toaster.MAX_TOASTS) { toaster.info("Toast $it", durationMs = 0) }
        root.frame(300f, 200f)
        assertFalse(first.classList.contains("leaving"))
        assertEquals(1, root.overlay(".guilib-toast.leaving").size)
        assertEquals("Toast 0", ((root.overlay(".guilib-toast.leaving").single().querySelector(".guilib-toast-message")!!.children.single()) as TextNode).data)
    }

    @Test
    fun escapeGoesBackFromASubPageBeforeClosingTheScreen() {
        var page: String? = "details"
        val root = ui {
            var p by useState(page)
            page = p
            useEscapeBack(p != null) { p = null }
            div { +(p ?: "list") }
        }
        assertTrue(root.input.keyDown("Escape", 0))
        root.frame(300f, 200f)
        assertNull(page)
        // On the main page Escape is not handled, so the screen closes
        assertFalse(root.input.keyDown("Escape", 0))
    }

    @Test
    fun escapeClosesAnOpenModalBeforeGoingBack() {
        var back = 0
        var modalOpen = true
        val root = ui {
            var open by useState(true)
            modalOpen = open
            useEscapeBack { back++ }
            modal(open = open, onClose = { open = false }) { div { +"Sure?" } }
        }
        assertTrue(root.input.keyDown("Escape", 0))
        root.frame(300f, 200f)
        assertFalse(modalOpen)
        assertEquals(0, back)
        assertTrue(root.input.keyDown("Escape", 0))
        assertEquals(1, back)
    }

    @Test
    fun contextMenuClosesWhenTheScreenResizes() {
        // The menu sits at a mouse position; after a resize that point no longer matches what was clicked.
        val root = ui { contextMenu(menu = { item("Invite") {} }) { div(style = "height: 50px") } }
        root.input.mouseDown(20f, 10f, 2)
        root.input.mouseUp(20f, 10f, 2)
        root.frame(300f, 200f)
        assertEquals(1, root.overlay(".guilib-menu").size)
        root.frame(300f, 200f)
        assertEquals(1, root.overlay(".guilib-menu").size)
        root.frame(200f, 150f)
        root.frame(200f, 150f)
        assertEquals(0, root.overlay(".guilib-menu").size)
    }

    @Test
    fun contextMenuOpensAtTheMouseStaysOnScreenAndRuns() {
        val clicked = ArrayList<String>()
        val root = ui {
            contextMenu(menu = {
                header("Steve")
                item("Invite") { clicked += "invite" }
                separator()
                item("Kick", danger = true, disabled = true) { clicked += "kick" }
                item("Profile") { clicked += "profile" }
            }) { div(className = "anchor") }
        }
        fun rightClick(x: Float, y: Float) {
            root.input.mouseDown(x, y, 2)
            root.input.mouseUp(x, y, 2)
            root.frame(300f, 200f)
        }
        rightClick(20f, 10f)
        var menu = root.overlay(".guilib-menu").single().getBoundingClientRect()
        assertEquals(20f, menu.x, 0.01f)
        assertEquals(10f, menu.y, 0.01f)
        root.click(root.overlay(".guilib-menu-item")[1]) // disabled: nothing happens, stays open
        assertEquals(emptyList<String>(), clicked)
        root.click(root.overlay(".guilib-menu-item")[0])
        assertEquals(listOf("invite"), clicked)
        assertEquals(0, root.overlay(".guilib-menu").size)

        // Near the bottom right corner the menu flips to stay inside the 300x200 screen.
        val anchor = root.all(".anchor").single()
        anchor.inlineStyle = "height: 200px"
        root.frame(300f, 200f)
        rightClick(290f, 195f)
        menu = root.overlay(".guilib-menu").single().getBoundingClientRect()
        assertTrue(menu.right <= 300f && menu.bottom <= 200f, "menu=$menu")
        assertEquals(290f, menu.right, 0.01f)
        // Keyboard: ArrowDown highlights the first enabled item, again skips the disabled one, Enter runs it.
        root.key("ArrowDown")
        root.key("ArrowDown")
        root.key("Enter")
        assertEquals(listOf("invite", "profile"), clicked)
        // Escape closes without running anything.
        rightClick(20f, 10f)
        root.key("Escape")
        assertEquals(0, root.overlay(".guilib-menu").size)
    }

    private fun UiRoot.type(text: String) {
        for (c in text) input.charTyped(c.toString())
        frame(300f, 200f)
    }

    private fun Element.label() = querySelector(".guilib-option-label")!!.let { (it.children.single() as TextNode).data }

    @Test
    fun searchableSelectFiltersAndChoosesWithTheKeyboard() {
        var value: String? = null
        val root = ui {
            var v by useState<String?>(null)
            value = v
            select(value = v, onChange = { v = it.value }, searchable = true, placeholder = "Item") {
                option("hyperion", "§6Hyperion")
                option("terminator", "Terminator")
                option("necron_blade", "Necron's Blade")
                option("aote", "Aspect of the End")
            }
        }
        root.click(root.document.body.querySelector("select")!!)
        root.frame(300f, 200f) // autofocus is posted
        val search = root.overlay(".guilib-select-search").single()
        assertEquals(search, root.document.focusedElement)
        root.type("ER")
        // Case-insensitive, § codes ignored: "Hyperion", "Terminator".
        assertEquals(listOf("§6Hyperion", "Terminator"), root.overlay(".guilib-option").map { it.label() })
        root.key("ArrowDown")
        root.key("ArrowDown")
        root.key("Enter")
        assertEquals("terminator", value)
        assertEquals(0, root.overlay(".guilib-select-menu").size)
        // Focus went back to the select.
        assertEquals("select", root.document.focusedElement?.tagName)
        root.key("ArrowDown")
        root.frame(300f, 200f)
        root.type("zzz")
        assertNotNull(root.document.overlayRoot.querySelector(".guilib-select-empty"))
        root.key("Escape")
        assertEquals(0, root.overlay(".guilib-select-menu").size)
        assertEquals("terminator", value)
    }

    @Test
    fun multiSelectTogglesAndStaysOpen() {
        var values = emptyList<String>()
        val root = ui {
            var v by useState(listOf<String>())
            values = v
            multiSelect(values = v, onChange = { v = it }, placeholder = "All") {
                option("trophy", "Trophy")
                option("lava", "Lava")
                option("water", "Water")
            }
        }
        root.click(root.document.body.querySelector("select")!!)
        root.click(root.overlay(".guilib-option")[2])
        root.click(root.overlay(".guilib-option")[0])
        assertEquals(listOf("trophy", "water"), values)
        assertEquals(1, root.overlay(".guilib-select-menu").size)
        assertTrue(root.overlay(".guilib-option")[0].classList.contains("selected"))
        val shown = root.document.body.querySelector(".guilib-select-value")!!
        // One span per chosen entry (each keeps its option's class), joined with ", ".
        fun text(n: net.sbo.guilib.core.dom.Node): String = if (n is TextNode) n.data else (n as Element).children.joinToString("") { text(it) }
        assertEquals("Trophy, Water", text(shown))
        root.click(root.overlay(".guilib-option")[0])
        assertEquals(listOf("water"), values)
        // A click outside closes it.
        root.input.mouseDown(290f, 190f, 0)
        root.input.mouseUp(290f, 190f, 0)
        root.frame(300f, 200f)
        assertEquals(0, root.overlay(".guilib-select-menu").size)
    }
}
