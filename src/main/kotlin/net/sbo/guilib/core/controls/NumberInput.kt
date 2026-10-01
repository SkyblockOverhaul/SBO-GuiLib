package net.sbo.guilib.core.controls

import net.sbo.guilib.core.dom.Cancelable
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.classNames
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.input
import net.sbo.guilib.core.event.EventType
import net.sbo.guilib.core.event.MouseEvent
import java.math.BigDecimal
import java.math.RoundingMode

internal data class NumberInputProps(
    val value: Double,
    val onChange: ((Double) -> Unit)?,
    val min: Double,
    val max: Double,
    val step: Double,
    val decimals: Int,
    val wheel: Boolean,
    val disabled: Boolean,
    val placeholder: String?,
    val className: String?,
    val id: String?,
    val style: String?,
)

/** Decimals a step needs (`0.25` → 2, `5` → 0). */
internal fun decimalsOf(step: Double): Int =
    if (step <= 0.0) 0 else BigDecimal(step.toString()).stripTrailingZeros().scale().coerceIn(0, 6)

internal fun roundTo(v: Double, decimals: Int): Double = BigDecimal(v).setScale(decimals, RoundingMode.HALF_UP).toDouble()

internal fun formatNumber(v: Double, decimals: Int): String = BigDecimal(v).setScale(decimals, RoundingMode.HALF_UP).toPlainString()

/** Parses what the user typed: `,` works as decimal point, empty/invalid → null. */
internal fun parseNumber(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

/** Delay before a held +/− button starts repeating, and the repeat interval. */
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_MS = 60L

/**
 * Number field with − and + buttons. The value always stays within min..max: typed values are clamped when the field
 * loses focus or Enter is pressed; values inside the range are reported while typing. ArrowUp/ArrowDown and the mouse
 * wheel (while hovered) step by `step`, Shift × 10; holding a button repeats.
 * Styled with `.guilib-number` (`.disabled`), `.guilib-number-input`, `.guilib-number-dec`, `.guilib-number-inc`.
 */
internal val NumberInputComponent = component<NumberInputProps>("NumberInput") { p ->
    var draft by useState<String?>(null)
    val latest = useRef(p.value)
    latest.current = p.value
    val repeat = useRef<Cancelable?>(null)
    val doc = useDocument()

    fun clamp(v: Double) = roundTo(v.coerceIn(p.min, p.max), p.decimals)

    fun emit(v: Double) {
        val next = clamp(v)
        if (next != latest.current) {
            latest.current = next
            p.onChange?.invoke(next)
        }
    }

    fun stepBy(times: Int) {
        if (!p.disabled) emit(latest.current + times * p.step)
        draft = null
    }

    fun commitDraft() {
        val d = draft ?: return
        parseNumber(d)?.let { emit(it) }
        draft = null
    }

    fun stopRepeat() {
        repeat.current?.cancel()
        repeat.current = null
    }

    useDocumentEvent(EventType.MOUSEUP) { stopRepeat() }
    useEffect { onCleanup { stopRepeat() } }

    fun holdButton(dir: Int): (MouseEvent) -> Unit = { e ->
        if (e.button == 0 && !p.disabled) {
            commitDraft()
            stepBy(dir * if (e.shiftKey) 10 else 1)
            stopRepeat()
            repeat.current = doc.setTimeout(REPEAT_DELAY_MS) {
                repeat.current = doc.setInterval(REPEAT_MS) { stepBy(dir) }
            }
        }
    }

    val atMin = p.value <= p.min
    val atMax = p.value >= p.max
    div(
        className = classNames("guilib-number", "disabled" to p.disabled, p.className),
        id = p.id,
        style = p.style,
        onWheel = { e ->
            if (p.wheel && !p.disabled && e.deltaY != 0f) {
                commitDraft()
                stepBy((if (e.deltaY < 0f) 1 else -1) * if (e.shiftKey) 10 else 1)
                e.preventDefault()
            }
        },
    ) {
        button(className = "guilib-number-dec", tabIndex = -1, disabled = p.disabled || atMin, onMouseDown = holdButton(-1)) { +"−" }
        input(
            className = "guilib-number-input",
            type = "number",
            value = draft ?: formatNumber(p.value, p.decimals),
            placeholder = p.placeholder,
            disabled = p.disabled,
            onChange = { e ->
                draft = e.value
                // Report values inside the range right away; out-of-range values are clamped on blur/Enter.
                parseNumber(e.value)?.takeIf { it in p.min..p.max }?.let { emit(it) }
            },
            onBlur = { commitDraft() },
            onKeyDown = { e ->
                when (e.key) {
                    "ArrowUp" -> { commitDraft(); stepBy(if (e.shiftKey) 10 else 1); e.preventDefault() }
                    "ArrowDown" -> { commitDraft(); stepBy(if (e.shiftKey) -10 else -1); e.preventDefault() }
                    "Enter" -> commitDraft()
                }
            },
        )
        button(className = "guilib-number-inc", tabIndex = -1, disabled = p.disabled || atMax, onMouseDown = holdButton(1)) { +"+" }
    }
}
