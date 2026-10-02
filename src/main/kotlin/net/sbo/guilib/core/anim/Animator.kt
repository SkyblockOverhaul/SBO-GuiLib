package net.sbo.guilib.core.anim

import net.sbo.guilib.core.css.AnimationDirection
import net.sbo.guilib.core.css.AnimationFillMode
import net.sbo.guilib.core.css.AnimationPlayState
import net.sbo.guilib.core.css.AnimationSpec
import net.sbo.guilib.core.css.ComputedStyle
import net.sbo.guilib.core.css.Prop
import net.sbo.guilib.core.css.Properties
import net.sbo.guilib.core.css.StyleContext
import net.sbo.guilib.core.css.StyleEngine
import net.sbo.guilib.core.css.TimingFunction
import net.sbo.guilib.core.dom.Element
import java.util.IdentityHashMap
import kotlin.math.floor

/**
 * Runs CSS transitions and `@keyframes` animations.
 *
 * The element's computed style stays the *target* ([Element.computed]); each frame the animator writes the current
 * in-between values to [Element.animatedStyle], which is what layout and painting read. Like the CSS cascade,
 * transitions win over animations.
 */
internal class Animator(private val engine: StyleEngine) {

    private class Transition(val from: Any?, val to: Any?, val start: Float, val duration: Float, val timing: TimingFunction)

    private class Running(var spec: AnimationSpec, val start: Float) {
        /** (offset, style) per keyframe, including implicit 0%/100% frames = the element's own style. */
        var frames: List<Pair<Float, ComputedStyle>> = emptyList()
        var props: Set<Prop> = emptySet()
        var pausedFor = 0f
        var pausedSince: Float? = null
    }

    private class State {
        val transitions = HashMap<Prop, Transition>()
        val animations = LinkedHashMap<String, Running>()
    }

    private val states = IdentityHashMap<Element, State>()
    /**
     * Names of keyframe animations that already ran to the end on an element. Like in browsers they don't start again
     * while the element keeps the name, however often it is restyled (hover, inline style changes …).
     */
    private val finished = IdentityHashMap<Element, MutableSet<String>>()

    /** Elements that got a new transition or animation since the last [tick]. */
    private val started = java.util.Collections.newSetFromMap(IdentityHashMap<Element, Boolean>())

    val isActive get() = states.isNotEmpty()

    /** True if transitions or animations started since the last [tick] and still have to write their first values. */
    val hasStarted get() = started.isNotEmpty()

    fun remove(el: Element) {
        states.remove(el)
        finished.remove(el)
        started.remove(el)
        el.animatedStyle = null
    }

    /** Called whenever [el] got a new computed style ([old] is `null` on first style). */
    fun onStyleComputed(el: Element, old: ComputedStyle?, next: ComputedStyle, parentStyle: ComputedStyle?, ctx: StyleContext, now: Float) {
        var state = states[el]

        // ---- transitions: start for changed properties listed in `transition` ----
        if (old != null) {
            val specs = next.transitions.filter { it.duration > 0f && it.property != "none" }
            val displayed = el.animatedStyle ?: old
            for (p in Prop.entries) {
                if (p in Interpolation.NOT_ANIMATABLE) continue
                val target = next[p]
                if (old[p] == target) continue
                val spec = specs.lastOrNull { it.property == "all" || p in Properties.longhandsOf(it.property) }
                val from = displayed[p]
                if (spec == null || !Interpolation.canInterpolate(p, from, target)) {
                    state?.transitions?.remove(p) // a changed value without transition cancels a running one
                    continue
                }
                if (state == null) state = State().also { states[el] = it }
                state.transitions[p] = Transition(from, target, now + spec.delay, spec.duration, spec.timing)
                started += el
            }
        }

        // ---- keyframe animations: start new names, stop removed ones, refresh frames ----
        val specs = next.animations
        val done = finished[el]
        if (done != null) {
            // A removed name may play again when it comes back.
            done.retainAll(specs.map { it.name }.toSet())
            if (done.isEmpty()) finished.remove(el)
        }
        if (specs.isNotEmpty() || state?.animations?.isNotEmpty() == true) {
            if (state == null) state = State().also { states[el] = it }
            val names = specs.map { it.name }.toSet()
            state.animations.keys.retainAll(names)
            for (spec in specs) {
                if (done != null && spec.name in done && spec.name !in state.animations) continue
                val keyframes = engine.keyframes(spec.name) ?: continue
                val running = state.animations.getOrPut(spec.name) { started += el; Running(spec, now) }
                running.spec = spec
                val frames = ArrayList<Pair<Float, ComputedStyle>>()
                if (keyframes.frames.none { it.offset == 0f }) frames += 0f to next
                for (kf in keyframes.frames) {
                    frames += kf.offset to engine.compute(el, el.inlineDeclarations + kf.declarations, parentStyle, ctx)
                }
                if (keyframes.frames.none { it.offset == 1f }) frames += 1f to next
                running.frames = frames
                running.props = Prop.entries.filter { p -> p !in Interpolation.NOT_ANIMATABLE && frames.any { it.second[p] != next[p] } }.toSet()
            }
        }
        if (state != null && state.transitions.isEmpty() && state.animations.isEmpty()) {
            states.remove(el)
            state = null
        }
        // Rebase the displayed style on the new one, dropping values of cancelled transitions (otherwise an element
        // whose last transition got cancelled would keep its mid-transition value forever, as tick() no longer sees it).
        val running = state?.let { s -> s.transitions.keys + s.animations.values.flatMap { it.props } } ?: emptySet()
        val kept = el.animatedOverrides?.filterKeys { it in running }.orEmpty()
        el.animatedOverrides = kept.ifEmpty { null }
        el.animatedStyle = if (kept.isEmpty()) null else next.withOverrides(kept)
    }

    /**
     * Advances everything to [now]. Returns the set of animated properties per element so the caller can invalidate
     * style/layout/paint; elements whose animations ended get their plain style back.
     */
    fun tick(now: Float, onChange: (Element, Set<Prop>) -> Unit) = tick(now, onlyStarted = false, onChange)

    /**
     * Writes the first values of transitions/animations that started since the last [tick] (at [now]) without
     * advancing the others – otherwise those elements would be drawn at their target style for a frame.
     */
    fun tickStarted(now: Float, onChange: (Element, Set<Prop>) -> Unit) = tick(now, onlyStarted = true, onChange)

    private fun tick(now: Float, onlyStarted: Boolean, onChange: (Element, Set<Prop>) -> Unit) {
        if (onlyStarted && started.isEmpty()) return
        val done = ArrayList<Element>()
        for ((el, state) in states) {
            if (onlyStarted && el !in started) continue
            val base = el.computed ?: continue
            val overrides = HashMap<Prop, Any?>()

            val anims = state.animations.values.iterator()
            while (anims.hasNext()) {
                val a = anims.next()
                if (!applyAnimation(a, now, overrides)) {
                    anims.remove()
                    finished.getOrPut(el) { HashSet() } += a.spec.name
                }
            }

            val trans = state.transitions.entries.iterator()
            while (trans.hasNext()) {
                val (p, tr) = trans.next()
                val elapsed = now - tr.start
                if (elapsed < 0f) {
                    overrides[p] = tr.from
                    continue
                }
                val progress = (elapsed / tr.duration).coerceIn(0f, 1f)
                if (progress >= 1f) {
                    trans.remove()
                    continue
                }
                overrides[p] = Interpolation.interpolate(p, tr.from, tr.to, tr.timing.ease(progress)) ?: tr.to
            }

            val changed = (overrides.keys + (el.animatedOverrides?.keys ?: emptySet())).toSet()
            el.animatedOverrides = overrides.ifEmpty { null }
            el.animatedStyle = if (overrides.isEmpty()) null else base.withOverrides(overrides)
            if (changed.isNotEmpty()) onChange(el, changed)
            if (state.transitions.isEmpty() && state.animations.isEmpty()) done += el
        }
        done.forEach { states.remove(it) }
        started.clear()
    }

    /** Applies one keyframe animation at [now]; returns false once it's over and should be dropped. */
    private fun applyAnimation(a: Running, now: Float, out: MutableMap<Prop, Any?>): Boolean {
        val spec = a.spec
        if (a.frames.size < 2) return false
        // Pausing freezes the timeline.
        if (spec.playState == AnimationPlayState.PAUSED) {
            if (a.pausedSince == null) a.pausedSince = now
        } else a.pausedSince?.let {
            a.pausedFor += now - it
            a.pausedSince = null
        }
        val clock = (a.pausedSince ?: now) - a.pausedFor
        val elapsed = clock - a.start - spec.delay
        val fillsBackwards = spec.fillMode == AnimationFillMode.BACKWARDS || spec.fillMode == AnimationFillMode.BOTH
        val fillsForwards = spec.fillMode == AnimationFillMode.FORWARDS || spec.fillMode == AnimationFillMode.BOTH

        if (elapsed < 0f) {
            if (fillsBackwards) sample(a, directed(spec, 0, 0f), out)
            return true
        }
        val duration = spec.duration
        val total = if (duration <= 0f) spec.iterations else elapsed / duration
        if (total >= spec.iterations) {
            if (!fillsForwards) return false
            val iterations = spec.iterations
            // End state: progress 1 of the last iteration (or the partial progress for fractional counts).
            val last = if (iterations == floor(iterations)) (iterations - 1).toInt().coerceAtLeast(0) else floor(iterations).toInt()
            val p = if (iterations == floor(iterations)) 1f else iterations - floor(iterations)
            sample(a, directed(spec, last, p), out)
            return true
        }
        val iteration = floor(total).toInt()
        sample(a, directed(spec, iteration, total - iteration), out)
        return true
    }

    private fun directed(spec: AnimationSpec, iteration: Int, p: Float): Float = when (spec.direction) {
        AnimationDirection.NORMAL -> p
        AnimationDirection.REVERSE -> 1f - p
        AnimationDirection.ALTERNATE -> if (iteration % 2 == 0) p else 1f - p
        AnimationDirection.ALTERNATE_REVERSE -> if (iteration % 2 == 0) 1f - p else p
    }

    /** Writes the values at animation progress [p] (0..1); the timing function applies per keyframe interval. */
    private fun sample(a: Running, p: Float, out: MutableMap<Prop, Any?>) {
        val frames = a.frames
        var i = 0
        while (i < frames.size - 2 && p > frames[i + 1].first) i++
        val (o0, s0) = frames[i]
        val (o1, s1) = frames[i + 1]
        val local = if (o1 > o0) ((p - o0) / (o1 - o0)).coerceIn(0f, 1f) else 1f
        val eased = a.spec.timing.ease(local)
        for (prop in a.props) out[prop] = Interpolation.interpolateOrSwitch(prop, s0[prop], s1[prop], eased)
    }
}
