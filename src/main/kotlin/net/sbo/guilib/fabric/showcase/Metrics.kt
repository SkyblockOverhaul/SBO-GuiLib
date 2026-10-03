package net.sbo.guilib.fabric.showcase

import net.minecraft.client.Minecraft
import net.sbo.guilib.core.FrameStats
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.TextNode
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.NodeBuilder
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.fabric.GuiLib
import net.sbo.guilib.fabric.font.GlyphAtlas
import net.sbo.guilib.fabric.image.Images
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.util.Locale

/**
 * Floating metrics window of the showcase: frame time, GuiLib's work, CPU, memory, garbage collection and a leak
 * indicator, sampled twice a second with a 60 s history.
 */
internal object Metrics {
    private const val SAMPLE_MS = 500L
    private const val HISTORY = 120

    private class Sample(
        val fps: Int,
        val frameMs: Double, val updateMs: Double, val drawMs: Double, val worstMs: Double,
        val stylesPerS: Double, val layoutsPerS: Double, val nodesPerS: Double, val reusedPerS: Double, val paintsPerS: Double,
        val processCpu: Double, val renderCpu: Double,
        val heapUsedMb: Double, val heapMaxMb: Double, val allocMbPerS: Double, val gcPerS: Double, val gcMsPerS: Double,
        val oldAfterGcMb: Double,
    )

    /** Reads the counters and turns the change since the previous call into rates. */
    private class Sampler {
        private val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        private val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        private val renderThread = Thread.currentThread().threadId()
        private var last: LongArray? = null

        private fun counters(): LongArray {
            val st = FrameStats
            val gcs = ManagementFactory.getGarbageCollectorMXBeans()
            return longArrayOf(
                System.nanoTime(), st.frames, st.updateNanos, st.drawNanos, st.styles, st.layouts, st.nodeLayouts, st.layoutHits, st.paints,
                threads?.getThreadCpuTime(renderThread) ?: -1L, threads?.getThreadAllocatedBytes(renderThread) ?: -1L,
                gcs.sumOf { it.collectionCount.coerceAtLeast(0) }, gcs.sumOf { it.collectionTime.coerceAtLeast(0) },
            )
        }

        fun sample(): Sample? {
            val now = counters()
            val prev = last
            last = now
            // FrameStats.reset() (dev automation) makes counters go back: skip that interval.
            if (prev == null || (1..8).any { now[it] < prev[it] }) return null
            val d = LongArray(now.size) { now[it] - prev[it] }
            val secs = d[0] / 1e9
            val frames = d[1].coerceAtLeast(1)
            val rt = Runtime.getRuntime()
            val oldGen = ManagementFactory.getMemoryPoolMXBeans()
                .filter { it.type == MemoryType.HEAP && it.collectionUsage != null && ("Old" in it.name || "Tenured" in it.name) }
                .sumOf { it.collectionUsage.used }
            return Sample(
                fps = Minecraft.getInstance().fps,
                frameMs = (d[2] + d[3]) / 1e6 / frames, updateMs = d[2] / 1e6 / frames, drawMs = d[3] / 1e6 / frames,
                worstMs = FrameStats.takeWorstFrameNanos() / 1e6,
                stylesPerS = d[4] / secs, layoutsPerS = d[5] / secs, nodesPerS = d[6] / secs, reusedPerS = d[7] / secs, paintsPerS = d[8] / secs,
                processCpu = (os?.processCpuLoad ?: -1.0) * 100.0,
                renderCpu = if (now[9] >= 0) d[9] / 1e9 / secs * 100.0 else -1.0,
                heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / MB, heapMaxMb = rt.maxMemory() / MB,
                allocMbPerS = if (now[10] >= 0) d[10] / MB / secs else -1.0,
                gcPerS = d[11] / secs, gcMsPerS = d[12] / secs,
                oldAfterGcMb = oldGen / MB,
            )
        }
    }

    private const val MB = 1024.0 * 1024.0

    private fun f(v: Double, digits: Int = 1) = if (v < 0) "n/a" else String.format(Locale.ROOT, "%.${digits}f", v)

    /** Elements and text nodes in the open GuiLib screen. */
    private fun domCounts(): Pair<Int, Int> {
        val body = GuiLib.currentDocument()?.body ?: return 0 to 0
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
        div(className = "metrics-graph $className") {
            val pad = HISTORY - values.size
            for (i in 0 until HISTORY) {
                val v = if (i < pad) 0.0 else values[i - pad].coerceAtLeast(0.0)
                val pct = if (top > 0) (v / top * 100.0).coerceIn(0.0, 100.0) else 0.0
                div(className = "metrics-bar", style = "height: ${String.format(Locale.ROOT, "%.1f", pct)}%")
            }
        }
    }

    private fun NodeBuilder.row(label: String, value: String) {
        div(className = "metrics-row") {
            span(className = "metrics-label") { +label }
            span(className = "metrics-value") { +value }
        }
    }

    val Window = component<() -> Unit>("Metrics") { onClose ->
        val sampler = useRef<Sampler?>(null)
        val history = useRef(ArrayDeque<Sample>())
        // Old gen right after the first "GC now": what really stays in memory, the reference for the leak check.
        val baseline = useRef(-1.0)
        val gcRequested = useRef(false)
        var tick by useState(0)
        useInterval(SAMPLE_MS) {
            val s = (sampler.current ?: Sampler().also { sampler.current = it }).sample() ?: return@useInterval
            history.current.addLast(s)
            if (history.current.size > HISTORY) history.current.removeFirst()
            if (gcRequested.current && baseline.current < 0) {
                baseline.current = s.oldAfterGcMb
                gcRequested.current = false
            }
            tick++
        }
        val h = history.current.toList()
        val s = h.lastOrNull()
        portal {
            div(className = "metrics") {
                div(className = "metrics-head") {
                    span(className = "metrics-title") { +"Metrics" }
                    button(className = "metrics-btn", title = "Run the garbage collector (then the old gen shows what really stays in memory)", onClick = { System.gc(); gcRequested.current = true }) { +"GC now" }
                    button(className = "metrics-btn", title = "Close", onClick = { onClose() }) { +"✕" }
                }
                if (s == null) div(className = "metrics-row") { +"Collecting…" } else content(s, h, baseline.current)
            }
        }
    }

    private fun NodeBuilder.content(s: Sample, h: List<Sample>, baseline: Double) {
        div(className = "metrics-section") { +"Frame" }
        row("FPS (Minecraft)", s.fps.toString())
        row("GuiLib per frame", "${f(s.frameMs, 2)} ms")
        row("update / draw", "${f(s.updateMs, 2)} / ${f(s.drawMs, 2)} ms")
        row("worst frame", "${f(s.worstMs, 2)} ms")
        graph(h.map { it.frameMs }, className = "frame")

        div(className = "metrics-section") { +"Work per second" }
        row("styles / layouts / paints", "${f(s.stylesPerS, 0)} / ${f(s.layoutsPerS, 0)} / ${f(s.paintsPerS, 0)}")
        row("nodes laid out / reused", "${f(s.nodesPerS, 0)} / ${f(s.reusedPerS, 0)}")
        val (elements, texts) = domCounts()
        row("DOM elements / texts", "$elements / $texts")
        row("paint commands", FrameStats.commands.toString())

        div(className = "metrics-section") { +"CPU" }
        row("process (all cores)", "${f(s.processCpu)} %")
        row("render thread (one core)", "${f(s.renderCpu)} %")
        graph(h.map { it.renderCpu }, 100.0, className = "cpu")

        div(className = "metrics-section") { +"Memory" }
        row("heap used / max", "${f(s.heapUsedMb, 0)} / ${f(s.heapMaxMb, 0)} MB")
        row("render thread allocates", "${f(s.allocMbPerS)} MB/s")
        row("GC runs / time", "${f(s.gcPerS)}/s · ${f(s.gcMsPerS, 0)} ms/s")
        graph(h.map { it.heapUsedMb }, s.heapMaxMb, className = "heap")

        div(className = "metrics-section") { +"Leak check" }
        row("old gen after last GC", "${f(s.oldAfterGcMb, 0)} MB")
        row("since first GC now", if (baseline < 0) "click GC now" else String.format(Locale.ROOT, "%+.1f MB", s.oldAfterGcMb - baseline))
        graph(h.map { it.oldAfterGcMb }, className = "leak")
        div(className = "metrics-hint") { +"Leak check: click GC now, use the UI for a while, click GC now again. The value should stay near +0 MB; between GCs it grows normally." }

        div(className = "metrics-section") { +"Caches" }
        val (pages, glyphs) = GlyphAtlas.stats
        val (images, svgs, filtered) = Images.stats
        row("glyph atlas pages / glyphs", "$pages / $glyphs")
        row("images / SVG rasters / filtered", "$images / $svgs / $filtered")
    }
}
