package net.sbo.guilib.fabric.debug

import net.minecraft.client.Minecraft
import net.sbo.guilib.core.FrameStats
import net.sbo.guilib.core.dom.Document
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.TextNode
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.NodeBuilder
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.event.MouseEvent
import net.sbo.guilib.fabric.font.GlyphAtlas
import net.sbo.guilib.fabric.image.Images
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Metrics window for any GuiLib screen (Ctrl + F12, or `GuiLib.open(metrics = true)`): frame time, GuiLib's work,
 * CPU, memory, garbage collection, a leak check and cache sizes, sampled twice a second with a 30 s history.
 *
 * It lives in its own document, drawn on top of the screen's: its own work is not counted ([FrameStats.uncounted]),
 * the DOM numbers are the screen's only and the screen's CSS can't change it (styled by ua.css, `.guilib-metrics*`).
 * The JVM is queried on a background thread (some of those calls take milliseconds).
 */
internal object MetricsOverlay {
    private const val SAMPLE_MS = 500L
    private const val HISTORY = 60
    private const val MB = 1024.0 * 1024.0

    class Props(val target: Document, val onClose: () -> Unit)

    /** Where the window was dragged to (GUI px), kept while the game runs; `null` = top left. */
    private var lastPosition: Pair<Float, Float>? = null
    private const val MARGIN = 6f

    private const val INFO = "This window is not measured: it is a separate document whose styles, layout, display list and " +
        "drawing are left out of Frame, Work and DOM (they show this screen only). CPU and Memory are the whole game's " +
        "(Minecraft included) and do include this window, which costs little: the JVM is queried on a background thread."

    /** JVM numbers, measured off the render thread. */
    private class Jvm(
        val processCpu: Double, val renderCpu: Double,
        val heapUsedMb: Double, val heapMaxMb: Double, val allocMbPerS: Double, val gcPerS: Double, val gcMsPerS: Double,
        val oldAfterGcMb: Double,
    )

    /** Queries the JVM every [SAMPLE_MS] on a daemon thread; [latest] is read by the render thread. */
    private class JvmProbe(private val renderThread: Long) {
        @Volatile var latest: Jvm? = null
        private val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        private val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        private var last: LongArray? = null
        private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "GuiLib metrics").also { it.isDaemon = true } }

        init {
            executor.scheduleAtFixedRate({ runCatching { probe() } }, 0, SAMPLE_MS, TimeUnit.MILLISECONDS)
        }

        fun stop() = executor.shutdownNow()

        private fun probe() {
            val gcs = ManagementFactory.getGarbageCollectorMXBeans()
            val now = longArrayOf(
                System.nanoTime(),
                threads?.getThreadCpuTime(renderThread) ?: -1L, threads?.getThreadAllocatedBytes(renderThread) ?: -1L,
                gcs.sumOf { it.collectionCount.coerceAtLeast(0) }, gcs.sumOf { it.collectionTime.coerceAtLeast(0) },
            )
            val prev = last
            last = now
            val cpu = (os?.processCpuLoad ?: -1.0) * 100.0
            if (prev == null) return
            val secs = (now[0] - prev[0]) / 1e9
            val rt = Runtime.getRuntime()
            val oldGen = ManagementFactory.getMemoryPoolMXBeans()
                .filter { it.type == MemoryType.HEAP && it.collectionUsage != null && ("Old" in it.name || "Tenured" in it.name) }
                .sumOf { it.collectionUsage.used }
            latest = Jvm(
                processCpu = cpu,
                renderCpu = if (now[1] >= 0) (now[1] - prev[1]) / 1e9 / secs * 100.0 else -1.0,
                heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / MB, heapMaxMb = rt.maxMemory() / MB,
                allocMbPerS = if (now[2] >= 0) (now[2] - prev[2]) / MB / secs else -1.0,
                gcPerS = (now[3] - prev[3]) / secs, gcMsPerS = (now[4] - prev[4]) / secs,
                oldAfterGcMb = oldGen / MB,
            )
        }
    }

    private class Sample(
        val fps: Int,
        val frameMs: Double, val updateMs: Double, val drawMs: Double, val worstMs: Double,
        val styleMs: Double, val layoutMs: Double, val paintMs: Double,
        val stylesPerS: Double, val layoutsPerS: Double, val nodesPerS: Double, val reusedPerS: Double, val paintsPerS: Double,
        val jvm: Jvm,
    )

    /** GuiLib's counters (cheap, read on the render thread) turned into rates since the previous call. */
    private class FrameSampler {
        private var last: LongArray? = null

        fun sample(jvm: Jvm): Sample? {
            val st = FrameStats
            val now = longArrayOf(
                System.nanoTime(), st.frames, st.updateNanos, st.drawNanos, st.styles, st.layouts, st.nodeLayouts, st.layoutHits,
                st.paints, st.styleNanos, st.layoutNanos, st.paintNanos,
            )
            val prev = last
            last = now
            // FrameStats.reset() (dev automation) makes counters go back: skip that interval.
            if (prev == null || (1 until now.size).any { now[it] < prev[it] }) return null
            val d = LongArray(now.size) { now[it] - prev[it] }
            val secs = d[0] / 1e9
            val frames = d[1].coerceAtLeast(1)
            return Sample(
                fps = Minecraft.getInstance().fps,
                frameMs = (d[2] + d[3]) / 1e6 / frames, updateMs = d[2] / 1e6 / frames, drawMs = d[3] / 1e6 / frames,
                worstMs = FrameStats.takeWorstFrameNanos() / 1e6,
                styleMs = d[9] / 1e6 / frames, layoutMs = d[10] / 1e6 / frames, paintMs = d[11] / 1e6 / frames,
                stylesPerS = d[4] / secs, layoutsPerS = d[5] / secs, nodesPerS = d[6] / secs, reusedPerS = d[7] / secs, paintsPerS = d[8] / secs,
                jvm = jvm,
            )
        }
    }

    private fun f(v: Double, digits: Int = 1) = if (v < 0) "n/a" else String.format(Locale.ROOT, "%.${digits}f", v)

    /** Elements and text nodes of the screen's document. */
    private fun domCounts(body: Element): Pair<Int, Int> {
        var elements = 0
        var texts = 0
        fun walk(e: Element) {
            elements++
            for (c in e.children) when (c) {
                is Element -> walk(c)
                is TextNode -> texts++
            }
        }
        walk(body)
        return elements to texts
    }

    /** Bar graph of [values] (newest right), scaled to [max] (or the largest value). */
    private fun NodeBuilder.graph(values: List<Double>, max: Double? = null, className: String) {
        val top = max ?: (values.maxOrNull() ?: 0.0)
        div(className = "guilib-metrics-graph $className") {
            val pad = HISTORY - values.size
            for (i in 0 until HISTORY) {
                val v = if (i < pad) 0.0 else values[i - pad].coerceAtLeast(0.0)
                val pct = if (top > 0) (v / top * 100.0).coerceIn(0.0, 100.0) else 0.0
                div(className = "guilib-metrics-bar", style = "height: ${String.format(Locale.ROOT, "%.1f", pct)}%")
            }
        }
    }

    private fun NodeBuilder.row(label: String, value: String) {
        div(className = "guilib-metrics-row") {
            span(className = "guilib-metrics-label") { +label }
            span(className = "guilib-metrics-value") { +value }
        }
    }

    val Window = component<Props>("Metrics") { props ->
        val probe = useRef<JvmProbe?>(null)
        val sampler = useRef(FrameSampler())
        val history = useRef(ArrayDeque<Sample>())
        // Old gen right after the first "GC now": what really stays in memory, the reference for the leak check.
        val baseline = useRef(-1.0)
        val gcRequested = useRef(false)
        var tick by useState(0)

        // Dragged by its title bar like a normal window; stays fully on screen.
        val doc = useDocument()
        val window = useElementRef()
        var position by useState(lastPosition ?: (MARGIN to MARGIN))
        val grab = useRef<Pair<Float, Float>?>(null)
        fun clamped(x: Float, y: Float): Pair<Float, Float> {
            val box = window.current?.box
            val maxX = (doc.body.box.width - (box?.width ?: 0f)).coerceAtLeast(0f)
            val maxY = (doc.body.box.height - (box?.height ?: 0f)).coerceAtLeast(0f)
            return x.coerceIn(0f, maxX) to y.coerceIn(0f, maxY)
        }
        useDocumentEvent("mousemove") { e ->
            val g = grab.current ?: return@useDocumentEvent
            e as MouseEvent
            position = clamped(e.clientX - g.first, e.clientY - g.second)
        }
        useDocumentEvent("mouseup") {
            if (grab.current != null) {
                grab.current = null
                lastPosition = position
            }
        }
        // A smaller window (resize, GUI scale) must not leave it outside.
        val onScreen = clamped(position.first, position.second)
        if (onScreen != position && window.current != null) position = onScreen

        useEffect {
            val p = JvmProbe(Thread.currentThread().threadId())
            probe.current = p
            onCleanup { p.stop() }
        }
        useInterval(SAMPLE_MS) {
            val jvm = probe.current?.latest ?: return@useInterval
            val s = sampler.current.sample(jvm) ?: return@useInterval
            history.current.addLast(s)
            if (history.current.size > HISTORY) history.current.removeFirst()
            if (gcRequested.current && baseline.current < 0) {
                baseline.current = jvm.oldAfterGcMb
                gcRequested.current = false
            }
            tick++
        }
        val h = history.current.toList()
        val s = h.lastOrNull()
        div(
            className = if (grab.current != null) "guilib-metrics dragging" else "guilib-metrics", ref = window,
            style = "left: ${position.first}px; top: ${position.second}px",
        ) {
            div(className = "guilib-metrics-head", onMouseDown = { e ->
                if (e.button == 0 && e.target.tagName != "button") {
                    grab.current = (e.clientX - position.first) to (e.clientY - position.second)
                    tick++ // show the grabbing cursor
                }
            }) {
                span(className = "guilib-metrics-title") { +"Metrics" }
                span(className = "guilib-metrics-info", title = INFO) { +"i" }
                button(
                    className = "guilib-metrics-btn", title = "Run the garbage collector (then the old gen shows what really stays in memory)",
                    onClick = { System.gc(); gcRequested.current = true },
                ) { +"GC now" }
                button(className = "guilib-metrics-btn", title = "Close (Ctrl + F12)", onClick = { props.onClose() }) { +"✕" }
            }
            if (s == null) div(className = "guilib-metrics-row") { +"Collecting…" } else content(s, h, baseline.current, props.target.body)
        }
    }

    private fun NodeBuilder.content(s: Sample, h: List<Sample>, baseline: Double, body: Element) {
        val j = s.jvm
        div(className = "guilib-metrics-section") { +"Frame (this screen)" }
        row("FPS (Minecraft)", s.fps.toString())
        row("GuiLib per frame", "${f(s.frameMs, 2)} ms")
        row("update / draw", "${f(s.updateMs, 2)} / ${f(s.drawMs, 2)} ms")
        row("styles / layout / paint", "${f(s.styleMs, 2)} / ${f(s.layoutMs, 2)} / ${f(s.paintMs, 2)} ms")
        row("worst frame", "${f(s.worstMs, 2)} ms")
        graph(h.map { it.frameMs }, className = "frame")

        div(className = "guilib-metrics-section") { +"Work per second" }
        row("styles / layouts / paints", "${f(s.stylesPerS, 0)} / ${f(s.layoutsPerS, 0)} / ${f(s.paintsPerS, 0)}")
        row("nodes laid out / reused", "${f(s.nodesPerS, 0)} / ${f(s.reusedPerS, 0)}")
        val (elements, texts) = domCounts(body)
        row("DOM elements / texts", "$elements / $texts")
        row("paint commands", FrameStats.commands.toString())

        div(className = "guilib-metrics-section") { +"CPU (whole game)" }
        row("process (all cores)", "${f(j.processCpu)} %")
        row("render thread (one core)", "${f(j.renderCpu)} %")
        graph(h.map { it.jvm.renderCpu }, 100.0, className = "cpu")

        div(className = "guilib-metrics-section") { +"Memory (whole game)" }
        row("heap used / max", "${f(j.heapUsedMb, 0)} / ${f(j.heapMaxMb, 0)} MB")
        row("render thread allocates", "${f(j.allocMbPerS)} MB/s")
        row("GC runs / time", "${f(j.gcPerS)}/s · ${f(j.gcMsPerS, 0)} ms/s")
        graph(h.map { it.jvm.heapUsedMb }, j.heapMaxMb, className = "heap")

        div(className = "guilib-metrics-section") { +"Leak check" }
        row("old gen after last GC", "${f(j.oldAfterGcMb, 0)} MB")
        row("since first GC now", if (baseline < 0) "click GC now" else String.format(Locale.ROOT, "%+.1f MB", j.oldAfterGcMb - baseline))
        graph(h.map { it.jvm.oldAfterGcMb }, className = "leak")
        div(className = "guilib-metrics-hint") {
            +"Click GC now, use the UI for a while, click GC now again. The value should stay near +0 MB; between GCs it grows normally."
        }

        div(className = "guilib-metrics-section") { +"Caches" }
        val (pages, glyphs) = GlyphAtlas.stats
        val (images, svgs, filtered) = Images.stats
        row("glyph atlas pages / glyphs", "$pages / $glyphs")
        row("images / SVG rasters / filtered", "$images / $svgs / $filtered")
    }
}
