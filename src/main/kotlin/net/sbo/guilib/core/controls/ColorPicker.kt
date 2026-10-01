package net.sbo.guilib.core.controls

import net.sbo.guilib.core.css.Colors
import net.sbo.guilib.core.dom.Rect
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.classNames
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.input
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.event.EventType
import net.sbo.guilib.core.event.KeyboardEvent
import net.sbo.guilib.core.event.MouseEvent

internal data class ColorPickerProps(
    val value: Int,
    val onChange: ((Int) -> Unit)?,
    val alpha: Boolean,
    val className: String?,
)

/** Parses `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa` (with or without `#`). */
internal fun parseHexColor(text: String): Int? = Colors.parseHex(text.trim().removePrefix("#"))

/**
 * Color picker: saturation/value area, hue slider, optional alpha slider, hex input and preview.
 * Controlled: shows [ColorPickerProps.value] and reports changes (ARGB) through `onChange`.
 * Built from plain elements, so it is styled with CSS: `.guilib-color-picker`, `.guilib-cp-sv`, `.guilib-cp-hue`,
 * `.guilib-cp-alpha`, `.guilib-cp-handle`, `.guilib-cp-thumb`, `.guilib-cp-preview`, `.guilib-cp-hex`.
 */
internal val ColorPickerComponent = component<ColorPickerProps>("ColorPicker") { p ->
    // HSV is kept as state so the hue survives when the color becomes grey (saturation 0).
    var hsv by useState(Colors.argbToHsv(p.value))
    var editingHex by useState<String?>(null)
    val svRef = useElementRef()
    val hueRef = useElementRef()
    val alphaRef = useElementRef()
    val drag = useRef<String?>(null)

    fun current(h: FloatArray) = Colors.hsvToArgb(h[0], h[1], h[2], if (p.alpha) h[3] else 1f)

    useEffect(p.value) {
        // Follow the controlled value unless it's the color we just produced.
        if (current(hsv) != p.value) {
            val next = Colors.argbToHsv(p.value)
            if (next[1] == 0f || next[2] == 0f) next[0] = hsv[0]
            hsv = next
        }
    }

    fun emit(next: FloatArray) {
        hsv = next
        p.onChange?.invoke(current(next))
    }

    fun fraction(r: Rect?, x: Float, y: Float): Pair<Float, Float>? {
        r ?: return null
        val fx = if (r.width > 0f) ((x - r.x) / r.width).coerceIn(0f, 1f) else 0f
        val fy = if (r.height > 0f) ((y - r.y) / r.height).coerceIn(0f, 1f) else 0f
        return fx to fy
    }

    fun update(mode: String, e: MouseEvent) {
        val h = hsv.copyOf()
        when (mode) {
            "sv" -> fraction(svRef.current?.getBoundingClientRect(), e.clientX, e.clientY)?.let { (fx, fy) -> h[1] = fx; h[2] = 1f - fy }
            "hue" -> fraction(hueRef.current?.getBoundingClientRect(), e.clientX, e.clientY)?.let { (fx, _) -> h[0] = fx * 360f }
            "alpha" -> fraction(alphaRef.current?.getBoundingClientRect(), e.clientX, e.clientY)?.let { (fx, _) -> h[3] = fx }
        }
        emit(h)
    }

    useDocumentEvent(EventType.MOUSEMOVE) { e -> drag.current?.let { update(it, e as MouseEvent) } }
    useDocumentEvent(EventType.MOUSEUP) { drag.current = null }

    fun start(mode: String): (MouseEvent) -> Unit = { e ->
        if (e.button == 0) {
            drag.current = mode
            update(mode, e)
            e.preventDefault()
        }
    }

    val color = current(hsv)
    val pureHue = Colors.toHex(Colors.hsvToArgb(hsv[0], 1f, 1f))
    val opaque = Colors.toHex(color or 0xFF000000.toInt())
    val hex = Colors.toHex(color, withAlpha = p.alpha)

    div(className = classNames("guilib-color-picker", p.className)) {
        div(
            className = "guilib-cp-sv",
            ref = svRef,
            style = "background: linear-gradient(to bottom, transparent, black), linear-gradient(to right, white, $pureHue)",
            onMouseDown = start("sv"),
        ) {
            div(className = "guilib-cp-handle", style = "left: ${hsv[1] * 100f}%; top: ${(1f - hsv[2]) * 100f}%; background-color: $opaque")
        }
        div(
            className = "guilib-cp-hue",
            ref = hueRef,
            style = "background: linear-gradient(to right, #ff0000, #ffff00, #00ff00, #00ffff, #0000ff, #ff00ff, #ff0000)",
            onMouseDown = start("hue"),
        ) {
            div(className = "guilib-cp-thumb", style = "left: ${hsv[0] / 360f * 100f}%; background-color: $pureHue")
        }
        if (p.alpha) {
            div(
                className = "guilib-cp-alpha",
                ref = alphaRef,
                style = "background: linear-gradient(to right, transparent, $opaque)",
                onMouseDown = start("alpha"),
            ) {
                div(className = "guilib-cp-thumb", style = "left: ${hsv[3] * 100f}%; background-color: $hex")
            }
        }
        div(className = "guilib-cp-row") {
            div(className = "guilib-cp-preview", style = "background-color: $hex")
            input(
                className = "guilib-cp-hex",
                value = editingHex ?: hex,
                maxLength = if (p.alpha) 9 else 7,
                onChange = { e ->
                    editingHex = e.value
                    parseHexColor(e.value)?.let { c ->
                        val next = Colors.argbToHsv(if (p.alpha) c else c or 0xFF000000.toInt())
                        emit(next)
                    }
                },
                onBlur = { editingHex = null },
            )
        }
    }
}

internal data class ColorInputProps(
    val value: Int,
    val onChange: ((Int) -> Unit)?,
    val alpha: Boolean,
    val className: String?,
    val disabled: Boolean,
)

/** A color swatch button that opens a [ColorPickerComponent] in a popover (portal). Styled with `.guilib-color-input`. */
internal val ColorInputComponent = component<ColorInputProps>("ColorInput") { p ->
    var open by useState(false)
    var anchor by useState<Rect?>(null)
    val ref = useElementRef()
    val popRef = useElementRef()
    val doc = useDocument()

    useAnchorTracking(ref, if (open) anchor else null) { anchor = it }
    useDocumentEvent(EventType.MOUSEDOWN) { e ->
        if (open && ref.current?.contains(e.target) != true && popRef.current?.contains(e.target) != true) open = false
    }
    useDocumentEvent(EventType.KEYDOWN) { e ->
        if (open && e is KeyboardEvent && e.key == "Escape") {
            open = false; e.preventDefault(); e.stopPropagation()
        }
    }

    val hex = Colors.toHex(p.value, withAlpha = p.alpha)
    button(
        className = classNames("guilib-color-input", p.className),
        ref = ref,
        disabled = p.disabled,
        onClick = {
            anchor = ref.current?.getBoundingClientRect()
            open = !open
        },
    ) {
        span(className = "guilib-color-swatch", style = "background-color: $hex")
        span(className = "guilib-color-hex") { +hex }
    }
    val a = anchor
    if (open && a != null) {
        val up = a.bottom > doc.viewportHeight * 0.55f
        val pos = if (up) "bottom: ${doc.viewportHeight - a.y + 2}px" else "top: ${a.bottom + 2}px"
        portal {
            div(className = "guilib-color-popover", ref = popRef, style = "position: fixed; left: ${a.x}px; $pos") {
                ColorPickerComponent(ColorPickerProps(p.value, p.onChange, p.alpha, null))
            }
        }
    }
}
