package net.sbo.guilib.core.controls

import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.classNames
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.event.EventType
import net.sbo.guilib.core.event.InputEvent
import net.sbo.guilib.core.event.KeyboardEvent
import net.sbo.guilib.core.event.MouseEvent
import net.sbo.guilib.core.event.UIEvent
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.round

internal data class SliderProps(
    val value: Float,
    val onChange: ((Float) -> Unit)?,
    val onChangeEnd: ((Float) -> Unit)?,
    val min: Float,
    val max: Float,
    val step: Float,
    val disabled: Boolean,
    val showValue: Boolean,
    val format: ((Float) -> String)?,
    val className: String?,
    val id: String?,
    val style: String?,
)

/** Snaps [v] to the step grid starting at [min] and clamps it to the range; removes float noise like `0.30000001`. */
internal fun snapSliderValue(v: Float, min: Float, max: Float, step: Float): Float {
    if (max <= min) return min
    var r = v.coerceIn(min, max)
    if (step > 0f) {
        r = min + round((r - min) / step) * step
        // Keep the step's precision (step 0.05 → 2 decimals) so values print and compare cleanly.
        val decimals = BigDecimal(step.toString()).stripTrailingZeros().scale().coerceIn(0, 6)
        r = BigDecimal(r.toDouble()).setScale(decimals, RoundingMode.HALF_UP).toFloat()
        // The last grid point can lie below max (range 0..10, step 3); never round past the end.
        if (r > max) r -= step
    }
    return r.coerceIn(min, max)
}

/** Default value label: no decimals for whole steps, otherwise as many as the step has. */
internal fun formatSliderValue(v: Float, step: Float): String {
    val decimals = if (step > 0f) BigDecimal(step.toString()).stripTrailingZeros().scale().coerceIn(0, 6) else 2
    return String.format(Locale.ROOT, "%.${decimals}f", v)
}

/**
 * Range slider (like `<input type="range">`), controlled: shows [SliderProps.value], reports every change during a
 * drag through `onChange` and the final value on release (or after a key press) through `onChangeEnd`.
 * Focusable; arrow keys move by one step, PageUp/PageDown by a tenth of the range, Home/End jump to the ends.
 * Styled with `.guilib-slider` (`.dragging`, `:focus`, `:disabled`), `.guilib-slider-track`, `.guilib-slider-rail`,
 * `.guilib-slider-fill`, `.guilib-slider-thumb` and `.guilib-slider-value`.
 */
internal val SliderComponent = component<SliderProps>("Slider") { p ->
    var dragging by useState(false)
    val railRef = useElementRef()
    // The latest value we reported, so a release right after a move reports it even before the parent re-rendered.
    val last = useRef(p.value)
    val drag = useRef(false)

    val value = snapSliderValue(p.value, p.min, p.max, p.step)
    if (!drag.current) last.current = value

    fun set(v: Float): Boolean {
        val next = snapSliderValue(v, p.min, p.max, p.step)
        if (next == last.current) return false
        last.current = next
        p.onChange?.invoke(next)
        return true
    }

    fun fromMouse(e: MouseEvent) {
        val r = railRef.current?.getBoundingClientRect() ?: return
        val f = if (r.width > 0f) ((e.clientX - r.x) / r.width).coerceIn(0f, 1f) else 0f
        set(p.min + f * (p.max - p.min))
    }

    useDocumentEvent(EventType.MOUSEMOVE) { e -> if (drag.current) fromMouse(e as MouseEvent) }
    useDocumentEvent(EventType.MOUSEUP) { e ->
        if (drag.current && (e as MouseEvent).button == 0) {
            drag.current = false
            dragging = false
            p.onChangeEnd?.invoke(last.current)
        }
    }

    val handlers = HashMap<String, (UIEvent) -> Unit>()
    handlers[EventType.MOUSEDOWN] = { e ->
        e as MouseEvent
        if (e.button == 0 && !p.disabled) {
            drag.current = true
            dragging = true
            fromMouse(e)
        }
    }
    handlers[EventType.KEYDOWN] = { e ->
        e as KeyboardEvent
        val range = p.max - p.min
        val step = if (p.step > 0f) p.step else range / 100f
        val big = maxOf(step, range / 10f)
        val target = when (e.key) {
            "ArrowRight", "ArrowUp" -> last.current + step
            "ArrowLeft", "ArrowDown" -> last.current - step
            "PageUp" -> last.current + big
            "PageDown" -> last.current - big
            "Home" -> p.min
            "End" -> p.max
            else -> null
        }
        if (target != null && !p.disabled) {
            e.preventDefault()
            if (set(target)) p.onChangeEnd?.invoke(last.current)
        }
    }

    val attrs = HashMap<String, Any?>()
    attrs["tabindex"] = 0
    if (p.disabled) attrs["disabled"] = true
    val shown = last.current
    val pct = if (p.max > p.min) (shown - p.min) / (p.max - p.min) * 100f else 0f
    val pctText = String.format(Locale.ROOT, "%.3f", pct)
    val restText = String.format(Locale.ROOT, "%.3f", 100f - pct)
    val slider = classNames("guilib-slider", "dragging" to dragging, p.className)
    val body: net.sbo.guilib.core.dsl.NodeBuilder.() -> Unit = {
        element("div", null, p.id, slider, p.style, null, attrs, handlers) {
            div(className = "guilib-slider-track")
            div(className = "guilib-slider-rail", ref = railRef) {
                div(className = "guilib-slider-fill", style = "right: $restText%")
                div(className = "guilib-slider-thumb", style = "left: $pctText%")
            }
        }
    }
    if (p.showValue) {
        div(className = classNames("guilib-slider-field", "disabled" to p.disabled)) {
            body()
            span(className = "guilib-slider-value") { +(p.format?.invoke(shown) ?: formatSliderValue(shown, p.step)) }
        }
    } else body()
}

internal data class SwitchProps(
    val checked: Boolean,
    val onChange: ((InputEvent) -> Unit)?,
    val label: String?,
    val disabled: Boolean,
    val className: String?,
    val id: String?,
)

/**
 * Toggle switch (an on/off checkbox that looks like a sliding pill). Controlled like `checkbox`:
 * `switch(checked = on, onChange = { on = it.checked })`. Focusable; Space/Enter toggle it.
 * Styled with `.guilib-switch` (`.checked`, `:focus`, `:disabled`), `.guilib-switch-track` and `.guilib-switch-thumb`.
 */
internal val SwitchComponent = component<SwitchProps>("Switch") { p ->
    val ref = useElementRef()
    val attrs = HashMap<String, Any?>()
    attrs["tabindex"] = 0
    if (p.disabled) attrs["disabled"] = true
    val handlers = HashMap<String, (UIEvent) -> Unit>()
    handlers[EventType.CLICK] = {
        if (!p.disabled) {
            val ev = InputEvent(EventType.CHANGE, if (!p.checked) "on" else "", checked = !p.checked)
            ref.current?.let { ev.target = it; ev.currentTarget = it }
            p.onChange?.invoke(ev)
        }
    }
    element("label", null, p.id, classNames("guilib-switch", "checked" to p.checked, p.className), null, ref, attrs, handlers) {
        span(className = "guilib-switch-track") { span(className = "guilib-switch-thumb") }
        if (p.label != null) span(className = "guilib-switch-label") { +p.label }
    }
}

internal data class RangeSliderProps(
    val low: Float,
    val high: Float,
    val onChange: ((Float, Float) -> Unit)?,
    val onChangeEnd: ((Float, Float) -> Unit)?,
    val min: Float,
    val max: Float,
    val step: Float,
    val disabled: Boolean,
    val showValue: Boolean,
    val format: ((Float) -> String)?,
    val className: String?,
    val id: String?,
    val style: String?,
)

/**
 * Slider with two thumbs selecting a range (`low..high`). A press moves the nearer thumb and drags it; the thumbs can't
 * pass each other. Each thumb is focusable and moves with the same keys as [SliderComponent].
 * Styled like the slider plus `.guilib-range-slider` and `.guilib-slider-thumb.low` / `.high`.
 */
internal val RangeSliderComponent = component<RangeSliderProps>("RangeSlider") { p ->
    var dragging by useState(false)
    val railRef = useElementRef()
    val lowRef = useElementRef()
    val highRef = useElementRef()
    val last = useRef(floatArrayOf(p.low, p.high))
    /** Which thumb is being dragged: 0 = low, 1 = high, -1 = none. */
    val drag = useRef(-1)
    val doc = useDocument()

    fun snap(v: Float) = snapSliderValue(v, p.min, p.max, p.step)
    if (drag.current < 0) {
        val lo = snap(minOf(p.low, p.high))
        last.current = floatArrayOf(lo, snap(maxOf(p.high, lo)))
    }

    fun set(thumb: Int, v: Float): Boolean {
        val cur = last.current
        val next = if (thumb == 0) snap(v).coerceAtMost(cur[1]) else snap(v).coerceAtLeast(cur[0])
        if (next == cur[thumb]) return false
        val n = cur.copyOf().also { it[thumb] = next }
        last.current = n
        p.onChange?.invoke(n[0], n[1])
        return true
    }

    fun valueAt(e: MouseEvent): Float? {
        val r = railRef.current?.getBoundingClientRect() ?: return null
        val f = if (r.width > 0f) ((e.clientX - r.x) / r.width).coerceIn(0f, 1f) else 0f
        return p.min + f * (p.max - p.min)
    }

    useDocumentEvent(EventType.MOUSEMOVE) { e -> if (drag.current >= 0) valueAt(e as MouseEvent)?.let { set(drag.current, it) } }
    useDocumentEvent(EventType.MOUSEUP) { e ->
        if (drag.current >= 0 && (e as MouseEvent).button == 0) {
            drag.current = -1
            dragging = false
            p.onChangeEnd?.invoke(last.current[0], last.current[1])
        }
    }

    val handlers = HashMap<String, (UIEvent) -> Unit>()
    handlers[EventType.MOUSEDOWN] = { e ->
        e as MouseEvent
        val v = valueAt(e)
        if (e.button == 0 && !p.disabled && v != null) {
            val (lo, hi) = last.current.let { it[0] to it[1] }
            // The nearer thumb; when both sit on the same spot, the side of the press decides.
            val thumb = when {
                lo == hi -> if (v > hi) 1 else 0
                kotlin.math.abs(v - lo) <= kotlin.math.abs(v - hi) -> 0
                else -> 1
            }
            drag.current = thumb
            dragging = true
            set(thumb, v)
            // Focus the thumb itself (the press target is the slider, which isn't focusable).
            e.preventDefault()
            (if (thumb == 0) lowRef else highRef).current?.let { doc.focus(it) }
        }
    }

    fun keyHandler(thumb: Int): (UIEvent) -> Unit = { e ->
        e as KeyboardEvent
        val range = p.max - p.min
        val step = if (p.step > 0f) p.step else range / 100f
        val big = maxOf(step, range / 10f)
        val cur = last.current[thumb]
        val target = when (e.key) {
            "ArrowRight", "ArrowUp" -> cur + step
            "ArrowLeft", "ArrowDown" -> cur - step
            "PageUp" -> cur + big
            "PageDown" -> cur - big
            "Home" -> p.min
            "End" -> p.max
            else -> null
        }
        if (target != null && !p.disabled) {
            e.preventDefault()
            if (set(thumb, target)) p.onChangeEnd?.invoke(last.current[0], last.current[1])
        }
    }

    fun pct(v: Float) = if (p.max > p.min) (v - p.min) / (p.max - p.min) * 100f else 0f
    fun fmt(v: Float) = String.format(Locale.ROOT, "%.3f", v)
    val (lo, hi) = last.current.let { it[0] to it[1] }
    val attrs = HashMap<String, Any?>()
    if (p.disabled) attrs["disabled"] = true
    val cls = classNames("guilib-slider", "guilib-range-slider", "dragging" to dragging, p.className)
    val body: net.sbo.guilib.core.dsl.NodeBuilder.() -> Unit = {
        element("div", null, p.id, cls, p.style, null, attrs, handlers) {
            div(className = "guilib-slider-track")
            div(className = "guilib-slider-rail", ref = railRef) {
                div(className = "guilib-slider-fill", style = "left: ${fmt(pct(lo))}%; right: ${fmt(100f - pct(hi))}%")
                for (thumb in 0..1) {
                    val tAttrs = HashMap<String, Any?>()
                    tAttrs["tabindex"] = if (p.disabled) -1 else 0
                    val tHandlers = HashMap<String, (UIEvent) -> Unit>()
                    tHandlers[EventType.KEYDOWN] = keyHandler(thumb)
                    val v = if (thumb == 0) lo else hi
                    element(
                        "div", thumb, null,
                        classNames("guilib-slider-thumb", if (thumb == 0) "low" else "high", "active" to (drag.current == thumb)),
                        "left: ${fmt(pct(v))}%", if (thumb == 0) lowRef else highRef, tAttrs, tHandlers, null,
                    )
                }
            }
        }
    }
    if (p.showValue) {
        div(className = classNames("guilib-slider-field", "disabled" to p.disabled)) {
            body()
            val f = p.format ?: { v: Float -> formatSliderValue(v, p.step) }
            span(className = "guilib-slider-value") { +"${f(lo)} – ${f(hi)}" }
        }
    } else body()
}
