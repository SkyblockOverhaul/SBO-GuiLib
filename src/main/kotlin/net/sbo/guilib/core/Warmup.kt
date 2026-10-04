package net.sbo.guilib.core

import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.checkbox
import net.sbo.guilib.core.dsl.classNames
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.h1
import net.sbo.guilib.core.dsl.input
import net.sbo.guilib.core.dsl.p
import net.sbo.guilib.core.dsl.scroll
import net.sbo.guilib.core.dsl.select
import net.sbo.guilib.core.dsl.slider
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.dsl.switch
import net.sbo.guilib.core.dsl.table
import net.sbo.guilib.core.dsl.td
import net.sbo.guilib.core.dsl.tr
import net.sbo.guilib.core.event.Modifiers
import net.sbo.guilib.core.layout.FontMetrics
import net.sbo.guilib.core.layout.TextMeasurer
import net.sbo.guilib.core.layout.TextStyle

/**
 * Runs the CSS parser, style engine, reconciler, layout, painter and event code once on a background thread at
 * startup, so the first screen the player opens doesn't pay for class loading and cold code (that made the first
 * open take a noticeable moment, later opens didn't). Uses its own document and a fake text measurer, nothing of
 * Minecraft.
 *
 * Every [UiRoot] and stylesheet load waits for a running warm-up before it starts ([await]), so the library is never
 * used from two threads at once.
 */
object Warmup {
    @Volatile
    private var thread: Thread? = null

    /** The parsed user-agent stylesheet, handed to the backend's stylesheet cache when the warm-up is done. */
    @Volatile
    var userAgent: Stylesheet? = null
        private set

    /** Starts the warm-up with the text of the user-agent stylesheet; [done] runs on the warm-up thread at the end. */
    fun start(uaCss: String, done: () -> Unit = {}) {
        if (thread != null) return
        thread = Thread({
            val t = System.nanoTime()
            try {
                FrameStats.uncounted { run(uaCss) } // not part of the metrics overlay's numbers
                done()
                Log.info("GuiLib: warm-up took ${(System.nanoTime() - t) / 1_000_000} ms")
            } catch (e: Throwable) {
                Log.warn("GuiLib: warm-up failed (harmless, the first screen just opens slower): $e")
            }
        }, "GuiLib warm-up").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** Waits for a running warm-up to finish (no-op on the warm-up thread itself or when there is none). */
    fun await() {
        val t = thread ?: return
        if (t === Thread.currentThread()) return
        t.join()
    }

    private fun run(uaCss: String) {
        val ua = Stylesheet.parse(uaCss, "guilib:css/ua.css", Origin.USER_AGENT)
        val author = Stylesheet.parse(
            """
            .panel { display: flex; flex-direction: column; gap: 4px; padding: 6px; border-radius: 4px;
                     background: linear-gradient(#2b2d31, #1e1f22); box-shadow: 0 2px 6px rgba(0, 0, 0, 0.5) }
            .row:hover { background-color: rgba(255, 255, 255, 0.06) }
            .row:nth-child(odd) { opacity: 0.9 }
            .grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 2px }
            .on { color: var(--guilib-accent); transform: translateX(2px); transition: color 100ms, transform 100ms }
            @media (max-width: 100px) { .panel { padding: 2px } }
            """.trimIndent(),
            "warm-up",
        )
        // Hits the same code paths as a real screen at every step after this one.
        repeat(2) {
            val root = UiRoot(FakeMeasurer, listOf(ua, author), clock = { 0L })
            lateinit var toggle: () -> Unit
            root.render(VComponent(component("GuiLibWarmup") {
                val (on, setOn) = useState(false)
                toggle = { setOn(!on) }
                div(className = "panel") {
                    h1 { +"Warm-up" }
                    p { +"Some §atext§r with "; span(className = classNames("on" to on)) { +"spans" } }
                    div(className = "grid") { repeat(6) { span { +"cell $it" } } }
                    scroll(style = "max-height: 40px") {
                        repeat(10) { div(className = "row") { +"Row $it" } }
                    }
                    table { repeat(2) { tr { td { +"a" }; td { +"b" } } } }
                    input(value = "text", placeholder = "Search")
                    checkbox(checked = on, label = "Check")
                    switch(checked = on, label = "Switch")
                    slider(value = 3, max = 10)
                    select(value = "a") { option("a", "A"); option("b", "B") }
                    button(className = "primary", disabled = !on) { +"Button" }
                }
            }, Unit, null))
            root.frame(320f, 240f)
            root.input.mouseMove(20f, 60f)
            root.input.mouseDown(20f, 60f, 0)
            root.input.mouseUp(20f, 60f, 0)
            root.input.wheel(20f, 60f, 0f, 10f)
            root.input.keyDown("Tab", 0, Modifiers.NONE)
            toggle()
            root.frame(320f, 240f)
            root.frame(200f, 240f)
            root.document.unmount()
        }
        userAgent = ua
    }

    /** Fixed-width text: the real font isn't needed to warm up the code. */
    private object FakeMeasurer : TextMeasurer {
        override fun width(text: String, style: TextStyle) = text.length * style.fontSize * 0.5f
        override fun metrics(style: TextStyle) = FontMetrics(style.fontSize * 0.8f, style.fontSize * 0.2f, style.fontSize * 1.2f)
    }
}
