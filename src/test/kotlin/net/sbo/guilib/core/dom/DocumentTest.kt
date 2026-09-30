package net.sbo.guilib.core.dom

import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.PseudoState
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.classNames
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.li
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.dsl.ul
import net.sbo.guilib.core.event.EventDispatcher
import net.sbo.guilib.core.event.MouseEvent
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DocumentTest {
    private var now = 0L
    private fun doc(css: String = "") = Document(
        FakeMeasurer,
        listOf(Stylesheet.parse("div { display: block }", "ua", Origin.USER_AGENT), Stylesheet.parse(css, "test.css")),
        clock = { now },
    )

    private fun Element.texts(): String = children.joinToString("") { if (it is TextNode) it.data else (it as Element).texts() }

    @Test
    fun rendersElementsTextAndAttributes() {
        val d = doc()
        val App = component("App") {
            div(className = "card", id = "main") {
                span { +"Hello " }
                +"World"
            }
        }
        d.render(VComponent(App, Unit, null))
        val card = d.body.elementChildren.first() // the last child is the overlay layer for portals
        assertEquals("div", card.tagName)
        assertEquals("main", card.id)
        assertEquals(setOf("card"), card.classList)
        assertEquals("Hello World", card.texts())
        assertSame(card, d.body.querySelector("#main"))
        assertSame(card, d.body.querySelector("div.card"))
    }

    @Test
    fun stateUpdateRerendersOnlyThatComponent() {
        val d = doc()
        var parentRenders = 0
        var childRenders = 0
        lateinit var setCount: (Int) -> Unit
        val Child = component("Child") {
            childRenders++
            val (count, set) = useState(0)
            setCount = set
            span { +"count=$count" }
        }
        val Parent = component("Parent") {
            parentRenders++
            div { Child() }
        }
        d.render(VComponent(Parent, Unit, null))
        assertEquals(1, parentRenders); assertEquals(1, childRenders)
        val spanBefore = d.body.querySelector("span")

        setCount(5)
        d.update(100f, 100f)
        assertEquals(1, parentRenders)
        assertEquals(2, childRenders)
        assertEquals("count=5", d.body.querySelector("span")!!.texts())
        assertSame(spanBefore, d.body.querySelector("span")) // DOM node reused

        setCount(5) // same value → no re-render
        d.update(100f, 100f)
        assertEquals(2, childRenders)
    }

    @Test
    fun batchesMultipleUpdates() {
        val d = doc()
        var renders = 0
        lateinit var bump: () -> Unit
        val C = component("C") {
            renders++
            val s = useState(0)
            bump = { s.update { it + 1 } }
            +"${s.value}"
        }
        d.render(VComponent(C, Unit, null))
        bump(); bump(); bump()
        d.update(10f, 10f)
        assertEquals(2, renders)
        assertEquals("3", d.body.texts())
    }

    data class ItemProps(val label: String)

    @Test
    fun keyedReorderPreservesStateAndElements() {
        val d = doc()
        val Item = component<ItemProps>("Item") { props ->
            val clicks = useState(0)
            li(className = "item", onClick = { clicks.update { it + 1 } }) { +"${props.label}:${clicks.value}" }
        }
        lateinit var setOrder: (List<String>) -> Unit
        val List = component("List") {
            val (items, set) = useState(listOf("a", "b", "c"))
            setOrder = set
            ul { for (i in items) Item(ItemProps(i), key = i) }
        }
        d.render(VComponent(List, Unit, null))
        val lis = d.body.querySelectorAll("li")
        // Click "b" twice.
        EventDispatcher.dispatch(MouseEvent("click", 0f, 0f), lis[1]); d.flush()
        EventDispatcher.dispatch(MouseEvent("click", 0f, 0f), lis[1]); d.flush()
        assertEquals("a:0b:2c:0", d.body.texts())

        setOrder(listOf("c", "b", "a"))
        d.update(10f, 10f)
        assertEquals("c:0b:2a:0", d.body.texts())
        val after = d.body.querySelectorAll("li")
        assertSame(lis[2], after[0])
        assertSame(lis[1], after[1])
        assertSame(lis[0], after[2])

        setOrder(listOf("c", "a"))
        d.update(10f, 10f)
        assertEquals("c:0a:0", d.body.texts())
    }

    @Test
    fun effectsRunAfterMountOnDepsChangeAndCleanUp() {
        val d = doc()
        val log = ArrayList<String>()
        lateinit var setDep: (Int) -> Unit
        lateinit var setShown: (Boolean) -> Unit
        val Child = component<Int>("Child") { dep ->
            useEffect { log += "mount"; onCleanup { log += "unmount" } }
            useEffect(dep) { log += "dep=$dep"; onCleanup { log += "cleanup dep=$dep" } }
            +"x"
        }
        val App = component("App") {
            val (dep, sd) = useState(1)
            val (shown, ss) = useState(true)
            setDep = sd; setShown = ss
            div { if (shown) Child(dep) }
        }
        d.render(VComponent(App, Unit, null))
        assertEquals(listOf("mount", "dep=1"), log)
        log.clear()

        setDep(2); d.update(1f, 1f)
        assertEquals(listOf("cleanup dep=1", "dep=2"), log)
        log.clear()

        setDep(2); d.update(1f, 1f)
        assertTrue(log.isEmpty())

        setShown(false); d.update(1f, 1f)
        assertEquals(setOf("unmount", "cleanup dep=2"), log.toSet())
    }

    @Test
    fun intervalsFollowTheClockAndStopOnUnmount() {
        val d = doc()
        lateinit var setShown: (Boolean) -> Unit
        val Clock = component("Clock") {
            var ticks by useState(0)
            useInterval(1000) { ticks++ }
            +"t=$ticks"
        }
        val App = component("App") {
            val (shown, s) = useState(true)
            setShown = s
            if (shown) Clock()
        }
        d.render(VComponent(App, Unit, null))
        now = 999; d.update(1f, 1f)
        assertEquals("t=0", d.body.texts())
        now = 1000; d.update(1f, 1f)
        now = 2000; d.update(1f, 1f)
        assertEquals("t=2", d.body.texts())
        setShown(false); d.update(1f, 1f)
        now = 5000; d.update(1f, 1f)
        assertEquals("", d.body.texts())
    }

    @Test
    fun contextProvidesValuesAndUpdatesConsumers() {
        val d = doc()
        val Theme = createContext("light")
        var consumerRenders = 0
        val Label = component("Label") {
            consumerRenders++
            +"theme=${useContext(Theme)}"
        }
        val Static = component("Static") { Label() }
        lateinit var setTheme: (String) -> Unit
        val App = component("App") {
            val (theme, s) = useState("dark")
            setTheme = s
            Theme.Provider(theme) { Static() }
        }
        d.render(VComponent(App, Unit, null))
        assertEquals("theme=dark", d.body.texts())
        setTheme("blue"); d.update(1f, 1f)
        assertEquals("theme=blue", d.body.texts())

        // Default value without provider.
        val d2 = doc()
        d2.render(VComponent(Label, Unit, null))
        assertEquals("theme=light", d2.body.texts())
    }

    @Test
    fun eventsBubbleAndCanBeStopped() {
        val d = doc()
        val log = ArrayList<String>()
        val App = component("App") {
            div(className = "outer", onClick = { log += "outer" }) {
                div(className = "inner", onClick = { e -> log += "inner:${e.target.className}" }) {
                    button(className = "btn", onClick = { e -> log += "button"; if (e.shiftKey) e.stopPropagation() })
                }
            }
        }
        d.render(VComponent(App, Unit, null))
        val btn = d.body.querySelector(".btn")!!
        EventDispatcher.dispatch(MouseEvent("click", 0f, 0f), btn)
        assertEquals(listOf("button", "inner:btn", "outer"), log)
        log.clear()
        EventDispatcher.dispatch(MouseEvent("click", 0f, 0f, modifiers = net.sbo.guilib.core.event.Modifiers(shift = true)), btn)
        assertEquals(listOf("button"), log)
    }

    @Test
    fun refsPointToElements() {
        val d = doc()
        lateinit var ref: Ref<Element?>
        val App = component("App") {
            ref = useElementRef()
            div(className = "target", ref = ref)
        }
        d.render(VComponent(App, Unit, null))
        assertNotNull(ref.current)
        assertEquals("target", ref.current!!.className)
    }

    @Test
    fun stylesAreComputedAndReactToStateAndClasses() {
        val d = doc(
            """
            .box { width: 10px; height: 10px; background-color: #111 }
            .box.active { background-color: #222 }
            .box:hover { background-color: #333 }
            .card:hover .title { color: red }
            """.trimIndent(),
        )
        lateinit var setActive: (Boolean) -> Unit
        val App = component("App") {
            val (active, s) = useState(false)
            setActive = s
            div(className = classNames("box", "active" to active))
            div(className = "card") { div(className = "title") { +"t" } }
        }
        d.render(VComponent(App, Unit, null))
        d.update(100f, 100f)
        val box = d.body.querySelector(".box")!!
        assertEquals(0xFF111111.toInt(), box.style.backgroundColor)
        assertEquals(10f, box.box.width)

        setActive(true); d.update(100f, 100f)
        assertEquals(0xFF222222.toInt(), box.style.backgroundColor)

        box.setState(PseudoState.HOVER, true); d.update(100f, 100f)
        assertEquals(0xFF333333.toInt(), box.style.backgroundColor)

        val card = d.body.querySelector(".card")!!
        val title = d.body.querySelector(".title")!!
        assertEquals(0xFFFFFFFF.toInt(), title.style.color)
        card.setState(PseudoState.HOVER, true); d.update(100f, 100f)
        assertEquals(0xFFFF0000.toInt(), title.style.color)
    }

    @Test
    fun postRunsOnUiThread() {
        val d = doc()
        lateinit var state: net.sbo.guilib.core.dsl.State<String>
        val App = component("App") {
            state = useState("before")
            +state.value
        }
        d.render(VComponent(App, Unit, null))
        val t = Thread { state.value = "from thread" }
        t.start(); t.join()
        assertEquals("before", d.body.texts())
        d.update(1f, 1f)
        assertEquals("from thread", d.body.texts())
    }

    @Test
    fun renderErrorsKeepPreviousOutput() {
        val d = doc()
        lateinit var setFail: (Boolean) -> Unit
        val App = component("App") {
            val (fail, s) = useState(false)
            setFail = s
            if (fail) error("boom")
            +"ok"
        }
        d.render(VComponent(App, Unit, null))
        setFail(true); d.update(1f, 1f)
        assertEquals("ok", d.body.texts())
    }
}
