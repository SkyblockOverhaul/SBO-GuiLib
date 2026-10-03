package net.sbo.guilib.core.controls

import net.sbo.guilib.core.dom.Cancelable
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.classNames
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.input
import net.sbo.guilib.core.event.EventType
import net.sbo.guilib.core.event.Modifiers
import net.sbo.guilib.core.event.MouseEvent
import java.math.BigDecimal
import java.math.RoundingMode

internal data class NumberInputProps(
    /** `null` = empty field (only when [nullable]). */
    val value: Double?,
    val onChange: ((Double?) -> Unit)?,
    val min: Double,
    val max: Double,
    val step: Double,
    val decimals: Int,
    val wheel: Boolean,
    val disabled: Boolean,
    val placeholder: String?,
    /** The field may be empty: clearing it reports `null` instead of restoring the last value. */
    val nullable: Boolean,
    /** How many steps one click / wheel notch / arrow key moves for the held keys. */
    val stepMultiplier: (Modifiers) -> Int,
    /** Turns the typed text into a number (`null` = invalid). */
    val parse: (String) -> Double?,
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

private val SHORTHAND = Regex("""([+-]?)(\d*(?:[.,]\d*)?)\s*([kmb])""", RegexOption.IGNORE_CASE)

/**
 * Default parser of [numberInput][net.sbo.guilib.core.dsl.numberInput]: plain numbers plus the shorthand `k` = 1,000,
 * `m` = 1,000,000, `b` = 1,000,000,000 (any case) – `"100k"` → 100000, `"1.5m"` / `"1,5M"` → 1500000. `.` and `,` both
 * work as decimal mark. Empty or invalid text → `null`.
 */
fun parseNumberShorthand(text: String): Double? {
    val m = SHORTHAND.matchEntire(text.trim()) ?: return parseNumber(text)
    val (sign, digits, unit) = m.destructured
    if (digits.none { it.isDigit() }) return null
    val factor = when (unit.lowercase()) {
        "k" -> 1_000L
        "m" -> 1_000_000L
        else -> 1_000_000_000L
    }
    // BigDecimal so "1.1k" is exactly 1100, not 1100.0000000000002.
    return BigDecimal(sign + digits.replace(',', '.')).multiply(BigDecimal.valueOf(factor)).toDouble()
}

/** Steps per click, wheel notch or arrow key for the held keys: Shift × 10, Ctrl × 100, Ctrl + Shift × 1000 (Cmd = Ctrl). */
fun defaultStepMultiplier(m: Modifiers): Int {
    val ctrl = m.ctrl || m.meta
    return when {
        ctrl && m.shift -> 1000
        ctrl -> 100
        m.shift -> 10
        else -> 1
    }
}

/** Delay before a held +/− button starts repeating, and the repeat interval. */
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_MS = 60L

/**
 * Number field with − and + buttons. The value always stays within min..max: typed values are clamped when the field
 * loses focus or Enter is pressed; values inside the range are reported while typing. Typed text goes through `parse`
 * (default [parseNumberShorthand]: `100k`, `1.5m`, `2,5k`, `1b`); the field keeps the typed text until blur/Enter, then
 * shows the formatted number; invalid text goes back to the last value. ArrowUp/ArrowDown and the mouse
 * wheel (while hovered) step by `step` × `stepMultiplier` (default Shift × 10, Ctrl × 100, Ctrl + Shift × 1000); holding
 * a button repeats with the multiplier of the press. With `nullable`, the field may be empty
 * (reported as `null`): + on an empty field starts at `step` (at least min), − on an empty field does nothing and − at
 * min empties the field again.
 * Styled with `.guilib-number` (`.disabled`), `.guilib-number-input`, `.guilib-number-dec`, `.guilib-number-inc`.
 */
internal val NumberInputComponent = component<NumberInputProps>("NumberInput") { p ->
    var draft by useState<String?>(null)
    val latest = useRef<Double?>(p.value)
    latest.current = p.value
    val repeat = useRef<Cancelable?>(null)
    val doc = useDocument()

    fun clamp(v: Double) = roundTo(v.coerceIn(p.min, p.max), p.decimals)

    fun emit(v: Double?) {
        val next = v?.let { clamp(it) }
        if (next != latest.current) {
            latest.current = next
            p.onChange?.invoke(next)
        }
    }

    fun stepBy(times: Int) {
        val cur = latest.current
        if (!p.disabled) when {
            // Empty: + starts one step up from empty (at least min), − does nothing.
            cur == null -> if (times > 0) emit(maxOf(p.min, times * p.step))
            // At min, − empties the field again (nullable only; otherwise clamp keeps it at min).
            p.nullable && times < 0 && cur <= p.min -> emit(null)
            else -> emit(cur + times * p.step)
        }
        draft = null
    }

    fun parsed(text: String): Double? = p.parse(text)?.takeIf { it.isFinite() }

    fun commitDraft() {
        val d = draft ?: return
        val v = parsed(d)
        if (v != null) emit(v) else if (p.nullable && d.isBlank()) emit(null)
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
            val times = dir * p.stepMultiplier(e.modifiers)
            stepBy(times)
            stopRepeat()
            repeat.current = doc.setTimeout(REPEAT_DELAY_MS) {
                repeat.current = doc.setInterval(REPEAT_MS) { stepBy(times) }
            }
        }
    }

    // Nullable: − stays usable at min (it empties the field) and is disabled only when the field is already empty.
    val atMin = if (p.nullable) p.value == null else p.value != null && p.value <= p.min
    val atMax = p.value != null && p.value >= p.max
    div(
        className = classNames("guilib-number", "disabled" to p.disabled, p.className),
        id = p.id,
        style = p.style,
        onWheel = { e ->
            if (p.wheel && !p.disabled && e.deltaY != 0f) {
                commitDraft()
                stepBy((if (e.deltaY < 0f) 1 else -1) * p.stepMultiplier(e.modifiers))
                e.preventDefault()
            }
        },
    ) {
        button(className = "guilib-number-dec", tabIndex = -1, disabled = p.disabled || atMin, onMouseDown = holdButton(-1)) { +"−" }
        input(
            className = "guilib-number-input",
            // A custom parser may need any characters; the default one only digits, signs, decimal marks and k/m/b.
            type = if (p.parse == ::parseNumberShorthand) "number" else "text",
            value = draft ?: p.value?.let { formatNumber(it, p.decimals) } ?: "",
            placeholder = p.placeholder,
            disabled = p.disabled,
            onChange = { e ->
                draft = e.value
                // Report values inside the range right away; out-of-range values are clamped on blur/Enter.
                val typed = parsed(e.value)
                if (typed != null && typed in p.min..p.max) emit(typed) else if (p.nullable && e.value.isBlank()) emit(null)
            },
            onBlur = { commitDraft() },
            onKeyDown = { e ->
                when (e.key) {
                    "ArrowUp" -> { commitDraft(); stepBy(p.stepMultiplier(e.modifiers)); e.preventDefault() }
                    "ArrowDown" -> { commitDraft(); stepBy(-p.stepMultiplier(e.modifiers)); e.preventDefault() }
                    "Enter" -> commitDraft()
                }
            },
        )
        button(className = "guilib-number-inc", tabIndex = -1, disabled = p.disabled || atMax, onMouseDown = holdButton(1)) { +"+" }
    }
}
