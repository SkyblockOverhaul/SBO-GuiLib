package net.sbo.guilib.core.dsl

import net.sbo.guilib.core.controls.ModalComponent
import net.sbo.guilib.core.controls.ModalProps
import net.sbo.guilib.core.controls.PresenceComponent
import net.sbo.guilib.core.controls.PresenceProps
import net.sbo.guilib.core.controls.PresenceListComponent
import net.sbo.guilib.core.controls.PresenceListProps
import net.sbo.guilib.core.controls.SelectComponent
import net.sbo.guilib.core.controls.SelectOption
import net.sbo.guilib.core.controls.SelectProps
import net.sbo.guilib.core.controls.NumberInputComponent
import net.sbo.guilib.core.controls.NumberInputProps
import net.sbo.guilib.core.controls.RangeSliderComponent
import net.sbo.guilib.core.controls.RangeSliderProps
import net.sbo.guilib.core.controls.SliderComponent
import net.sbo.guilib.core.controls.decimalsOf
import net.sbo.guilib.core.controls.SliderProps
import net.sbo.guilib.core.controls.SortableComponent
import net.sbo.guilib.core.controls.SortableProps
import net.sbo.guilib.core.controls.SwitchComponent
import net.sbo.guilib.core.controls.SwitchProps
import net.sbo.guilib.core.controls.TooltipComponent
import net.sbo.guilib.core.controls.TooltipProps
import net.sbo.guilib.core.dom.VText
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.Ref
import net.sbo.guilib.core.event.EventType
import net.sbo.guilib.core.event.FocusEvent
import net.sbo.guilib.core.event.InputEvent
import net.sbo.guilib.core.event.KeyboardEvent
import net.sbo.guilib.core.controls.defaultStepMultiplier
import net.sbo.guilib.core.event.Modifiers
import net.sbo.guilib.core.event.UIEvent

/** Collects the `option(...)` entries of a [select]. */
@GuiDsl
class SelectBuilder {
    internal val options = ArrayList<SelectOption>()

    /** An entry: `option("easy") { +"Easy" }`. [title] is shown as a tooltip while hovering the entry. */
    fun option(value: String, disabled: Boolean = false, title: String? = null, label: NodeBuilder.() -> Unit) {
        val text = NodeBuilder().apply(label).nodes.filterIsInstance<VText>().joinToString("") { it.text }
        options += SelectOption(value, text, disabled, title)
    }

    /** An entry with a plain label: `option("easy", "Easy", title = "For new players")`. */
    fun option(value: String, label: String, disabled: Boolean = false, title: String? = null) {
        options += SelectOption(value, label, disabled, title)
    }
}

/**
 * Dropdown, controlled like React: `select(value = mode, onChange = { mode = it.value }) { option("a") { +"A" } }`.
 * The menu opens in a portal, so it's never clipped by scroll containers. [searchable] adds a search field at the top
 * of the menu that filters the options while typing (for long lists like items or players).
 */
fun NodeBuilder.select(
    value: String?,
    onChange: ((InputEvent) -> Unit)? = null,
    className: String? = null,
    id: String? = null,
    style: String? = null,
    key: Any? = null,
    disabled: Boolean = false,
    placeholder: String? = null,
    searchable: Boolean = false,
    searchPlaceholder: String? = null,
    options: SelectBuilder.() -> Unit,
) {
    val opts = SelectBuilder().apply(options).options
    SelectComponent(SelectProps(value, onChange, opts, className, id, style, disabled, placeholder, searchable, searchPlaceholder), key)
}

/**
 * Dropdown for choosing several options (each with a check mark; the menu stays open while toggling), controlled:
 * `multiSelect(values = cats, onChange = { cats = it }, placeholder = "All") { option("trophy", "Trophy"); option("lava", "Lava") }`.
 * [onChange] gets the selected values in option order; the box shows the chosen labels. [searchable] adds a search field.
 * Styled like [select] plus `select.multiple`, `.guilib-option-check`. For a few options [chips] may be nicer.
 */
fun NodeBuilder.multiSelect(
    values: List<String>,
    onChange: ((List<String>) -> Unit)? = null,
    className: String? = null,
    id: String? = null,
    style: String? = null,
    key: Any? = null,
    disabled: Boolean = false,
    placeholder: String? = null,
    searchable: Boolean = false,
    searchPlaceholder: String? = null,
    options: SelectBuilder.() -> Unit,
) {
    val opts = SelectBuilder().apply(options).options
    SelectComponent(
        SelectProps(null, null, opts, className, id, style, disabled, placeholder, searchable, searchPlaceholder, true, values, onChange),
        key,
    )
}

/** Checkbox with an optional label: `checkbox(checked = on, onChange = { on = it.checked }, label = "Enabled")`. */
fun NodeBuilder.checkbox(
    checked: Boolean,
    onChange: ((InputEvent) -> Unit)? = null,
    label: String? = null,
    disabled: Boolean = false,
    className: String? = null,
    key: Any? = null,
) {
    label(className = classNames("guilib-checkbox", className), key = key) {
        input(type = "checkbox", checked = checked, disabled = disabled, onChange = onChange)
        if (label != null) span { +label }
    }
}

/**
 * Toggle switch (on/off pill), controlled like [checkbox]: `switch(checked = on, onChange = { on = it.checked }, label = "Sounds")`.
 * Click, Space or Enter toggles it. Styled with `.guilib-switch` (`.checked`), `.guilib-switch-track`, `.guilib-switch-thumb`,
 * `.guilib-switch-label`; recolor it with `--guilib-accent`.
 */
fun NodeBuilder.switch(
    checked: Boolean,
    onChange: ((InputEvent) -> Unit)? = null,
    label: String? = null,
    disabled: Boolean = false,
    className: String? = null,
    id: String? = null,
    key: Any? = null,
) = SwitchComponent(SwitchProps(checked, onChange, label, disabled, className, id), key)

/**
 * Range slider (like `<input type="range">`), controlled like React:
 *
 * ```kotlin
 * var volume by useState(50f)
 * slider(value = volume, onChange = { volume = it }, min = 0f, max = 100f, step = 5f, showValue = true)
 * ```
 * [onChange] fires for every new value while dragging; [onChangeEnd] once with the final value when the mouse is
 * released or after a key press (use it to save settings). Values snap to [step] (`0f` = continuous).
 * Focusable: arrow keys move one step, PageUp/PageDown a tenth of the range, Home/End jump to [min]/[max].
 * [showValue] adds a label after the slider, [format] customizes it (`format = { "${it.toInt()}%" }`).
 * Styled with `.guilib-slider` (`.dragging`), `.guilib-slider-track`, `.guilib-slider-rail`, `.guilib-slider-fill`,
 * `.guilib-slider-thumb`, `.guilib-slider-field` and `.guilib-slider-value`; set the width on `.guilib-slider`
 * (default 100px) and recolor it with `--guilib-accent`.
 */
fun NodeBuilder.slider(
    value: Float,
    onChange: ((Float) -> Unit)? = null,
    min: Float = 0f,
    max: Float = 100f,
    step: Float = 1f,
    onChangeEnd: ((Float) -> Unit)? = null,
    showValue: Boolean = false,
    format: ((Float) -> String)? = null,
    disabled: Boolean = false,
    className: String? = null,
    id: String? = null,
    style: String? = null,
    key: Any? = null,
) = SliderComponent(SliderProps(value, onChange, onChangeEnd, min, max, step, disabled, showValue, format, className, id, style), key)

/** Integer slider: like the Float [slider], but with `Int` values (step 1 by default). */
fun NodeBuilder.slider(
    value: Int,
    onChange: ((Int) -> Unit)? = null,
    min: Int = 0,
    max: Int = 100,
    step: Int = 1,
    onChangeEnd: ((Int) -> Unit)? = null,
    showValue: Boolean = false,
    format: ((Int) -> String)? = null,
    disabled: Boolean = false,
    className: String? = null,
    id: String? = null,
    style: String? = null,
    key: Any? = null,
) = SliderComponent(
    SliderProps(
        value.toFloat(), onChange?.let { f -> { v: Float -> f(Math.round(v)) } }, onChangeEnd?.let { f -> { v: Float -> f(Math.round(v)) } },
        min.toFloat(), max.toFloat(), step.toFloat(), disabled, showValue, format?.let { f -> { v: Float -> f(Math.round(v)) } },
        className, id, style,
    ),
    key,
)

/**
 * Slider with two thumbs for a range, e.g. a filter "kills between 5,000 and 20,000":
 *
 * ```kotlin
 * var kills by useState(5000f to 20000f)
 * rangeSlider(low = kills.first, high = kills.second, onChange = { lo, hi -> kills = lo to hi }, min = 0f, max = 50000f, step = 500f)
 * ```
 * A press moves the nearer thumb; the thumbs can't pass each other. Each thumb is focusable (arrow keys like [slider]).
 * [showValue] shows "low – high" (each formatted with [format]). Styled like [slider] plus `.guilib-range-slider`.
 */
fun NodeBuilder.rangeSlider(
    low: Float,
    high: Float,
    onChange: ((low: Float, high: Float) -> Unit)? = null,
    min: Float = 0f,
    max: Float = 100f,
    step: Float = 1f,
    onChangeEnd: ((low: Float, high: Float) -> Unit)? = null,
    showValue: Boolean = false,
    format: ((Float) -> String)? = null,
    disabled: Boolean = false,
    className: String? = null,
    id: String? = null,
    style: String? = null,
    key: Any? = null,
) = RangeSliderComponent(
    RangeSliderProps(low, high, onChange, onChangeEnd, min, max, step, disabled, showValue, format, className, id, style),
    key,
)

/** Integer [rangeSlider]: `rangeSlider(low = 5, high = 20, onChange = { lo, hi -> … }, min = 0, max = 50)`. */
fun NodeBuilder.rangeSlider(
    low: Int,
    high: Int,
    onChange: ((low: Int, high: Int) -> Unit)? = null,
    min: Int = 0,
    max: Int = 100,
    step: Int = 1,
    onChangeEnd: ((low: Int, high: Int) -> Unit)? = null,
    showValue: Boolean = false,
    format: ((Int) -> String)? = null,
    disabled: Boolean = false,
    className: String? = null,
    id: String? = null,
    style: String? = null,
    key: Any? = null,
) = RangeSliderComponent(
    RangeSliderProps(
        low.toFloat(), high.toFloat(),
        onChange?.let { f -> { a: Float, b: Float -> f(Math.round(a), Math.round(b)) } },
        onChangeEnd?.let { f -> { a: Float, b: Float -> f(Math.round(a), Math.round(b)) } },
        min.toFloat(), max.toFloat(), step.toFloat(), disabled, showValue, format?.let { f -> { v: Float -> f(Math.round(v)) } },
        className, id, style,
    ),
    key,
)

/**
 * Number field with − / + buttons that keeps the value within [min]..[max]:
 * `numberInput(value = size, onChange = { size = it }, min = 1, max = 5)`.
 * Typed values inside the range are reported while typing; anything else is clamped when the field loses focus or on
 * Enter. ArrowUp/ArrowDown and the mouse wheel over the field step by [step] (Shift: × 10, [wheel] = false turns the
 * wheel off); holding a button repeats. Styled with `.guilib-number`, `.guilib-number-input`, `.guilib-number-dec`,
 * `.guilib-number-inc`.
 */
fun NodeBuilder.numberInput(
    value: Int,
    onChange: ((Int) -> Unit)? = null,
    min: Int = Int.MIN_VALUE,
    max: Int = Int.MAX_VALUE,
    step: Int = 1,
    wheel: Boolean = true,
    disabled: Boolean = false,
    placeholder: String? = null,
    stepMultiplier: (Modifiers) -> Int = ::defaultStepMultiplier,
    className: String? = null,
    id: String? = null,
    style: String? = null,
    key: Any? = null,
) = NumberInputComponent(
    NumberInputProps(
        value.toDouble(), onChange?.let { f -> { v: Double? -> f(Math.round(v!!).toInt()) } }, min.toDouble(), max.toDouble(),
        step.toDouble(), 0, wheel, disabled, placeholder, false, stepMultiplier, className, id, style,
    ),
    key,
)

/** Decimal [numberInput]: shows as many decimals as [step] has (`step = 0.25` → `1.75`). */
fun NodeBuilder.numberInput(
    value: Double,
    onChange: ((Double) -> Unit)? = null,
    min: Double = -Double.MAX_VALUE,
    max: Double = Double.MAX_VALUE,
    step: Double = 1.0,
    wheel: Boolean = true,
    disabled: Boolean = false,
    placeholder: String? = null,
    stepMultiplier: (Modifiers) -> Int = ::defaultStepMultiplier,
    className: String? = null,
    id: String? = null,
    style: String? = null,
    key: Any? = null,
) = NumberInputComponent(
    NumberInputProps(
        value, onChange?.let { f -> { v: Double? -> f(v!!) } }, min, max, step, decimalsOf(step), wheel, disabled, placeholder,
        false, stepMultiplier, className, id, style,
    ),
    key,
)

/**
 * [numberInput] that may be empty: `numberInput(value = level, onChange = { level = it }, allowEmpty = true, placeholder = "any")`
 * with `var level by useState<Int?>(null)`. `null` shows an empty field (with the [placeholder]); clearing the field
 * reports `null` right away. + (arrow up, wheel up) on an empty field starts at [step] (at least [min]); − does nothing
 * on an empty field and empties the field at [min]. [allowEmpty] selects this overload; with `false` a cleared field restores the last value instead.
 */
@JvmName("numberInputNullable")
fun NodeBuilder.numberInput(
    value: Int?,
    onChange: ((Int?) -> Unit)? = null,
    allowEmpty: Boolean,
    min: Int = Int.MIN_VALUE,
    max: Int = Int.MAX_VALUE,
    step: Int = 1,
    wheel: Boolean = true,
    disabled: Boolean = false,
    placeholder: String? = null,
    stepMultiplier: (Modifiers) -> Int = ::defaultStepMultiplier,
    className: String? = null,
    id: String? = null,
    style: String? = null,
    key: Any? = null,
) = NumberInputComponent(
    NumberInputProps(
        value?.toDouble(), onChange?.let { f -> { v: Double? -> f(v?.let { Math.round(it).toInt() }) } }, min.toDouble(),
        max.toDouble(), step.toDouble(), 0, wheel, disabled, placeholder, allowEmpty, stepMultiplier, className, id, style,
    ),
    key,
)

/** Decimal [numberInput] that may be empty (`null`), see the `Int?` overload. */
@JvmName("numberInputNullableDouble")
fun NodeBuilder.numberInput(
    value: Double?,
    onChange: ((Double?) -> Unit)? = null,
    allowEmpty: Boolean,
    min: Double = -Double.MAX_VALUE,
    max: Double = Double.MAX_VALUE,
    step: Double = 1.0,
    wheel: Boolean = true,
    disabled: Boolean = false,
    placeholder: String? = null,
    stepMultiplier: (Modifiers) -> Int = ::defaultStepMultiplier,
    className: String? = null,
    id: String? = null,
    style: String? = null,
    key: Any? = null,
) = NumberInputComponent(
    NumberInputProps(value, onChange, min, max, step, decimalsOf(step), wheel, disabled, placeholder, allowEmpty, stepMultiplier, className, id, style),
    key,
)

/**
 * Multi-line text field (like `<textarea>`), controlled like `input`:
 * `textarea(value = note, onChange = { note = it.value }, placeholder = "Description", rows = 4, maxLength = 256)`.
 * Wraps words, Enter inserts a line break, arrow keys/Home/End/PageUp/PageDown move the caret, Ctrl+A/C/X/V work,
 * and it scrolls vertically when the text is taller than [rows] lines (the height without a CSS `height`). [maxLines] limits the line breaks: Enter does
 * nothing once the text has [maxLines] lines, and extra line breaks in pasted text become spaces (wrapped lines don't
 * count). Styled with `textarea`,
 * `.guilib-textarea-line`, `.guilib-placeholder`, `.guilib-caret`, `.guilib-selection`.
 */
fun NodeBuilder.textarea(
    value: String? = null,
    onChange: ((InputEvent) -> Unit)? = null,
    placeholder: String? = null,
    rows: Int = 3,
    maxLength: Int? = null,
    maxLines: Int? = null,
    disabled: Boolean = false,
    autoFocus: Boolean = false,
    className: String? = null,
    id: String? = null,
    style: String? = null,
    key: Any? = null,
    ref: Ref<Element?>? = null,
    onInput: ((InputEvent) -> Unit)? = null,
    onKeyDown: ((KeyboardEvent) -> Unit)? = null,
    onFocus: ((FocusEvent) -> Unit)? = null,
    onBlur: ((FocusEvent) -> Unit)? = null,
) {
    val attrs = HashMap<String, Any?>()
    if (value != null) attrs["value"] = value
    if (placeholder != null) attrs["placeholder"] = placeholder
    if (maxLength != null) attrs["maxlength"] = maxLength
    if (maxLines != null) attrs["maxlines"] = maxLines
    attrs["rows"] = rows
    if (disabled) attrs["disabled"] = true
    if (autoFocus) attrs["autofocus"] = true
    val handlers = HashMap<String, (UIEvent) -> Unit>()
    @Suppress("UNCHECKED_CAST")
    fun on(type: String, h: ((Nothing) -> Unit)?) {
        if (h != null) handlers[type] = h as (UIEvent) -> Unit
    }
    on(EventType.CHANGE, onChange)
    on(EventType.INPUT, onInput)
    on(EventType.KEYDOWN, onKeyDown)
    on(EventType.FOCUS, onFocus)
    on(EventType.BLUR, onBlur)
    // Without a CSS height it is [rows] lines tall (plus padding and border), like a browser textarea.
    element("textarea", key, id, className, style, ref, attrs, handlers, null)
}

/**
 * Tooltip shown when hovering [children] for [delayMs]. For simple cases the `title` prop works on any element too.
 * `tooltip("Refresh the list") { button { +"⟳" } }`
 */
fun NodeBuilder.tooltip(
    text: String,
    placement: String = "top",
    delayMs: Long = 300,
    className: String? = null,
    key: Any? = null,
    children: NodeBuilder.() -> Unit,
) = TooltipComponent(TooltipProps(text, null, placement, delayMs, className, children), key)

/** Tooltip with rich content: `tooltip(content = { b { +"Hi" } }) { anchor }`. */
fun NodeBuilder.tooltip(
    content: NodeBuilder.() -> Unit,
    placement: String = "top",
    delayMs: Long = 300,
    className: String? = null,
    key: Any? = null,
    children: NodeBuilder.() -> Unit,
) = TooltipComponent(TooltipProps(null, content, placement, delayMs, className, children), key)

/**
 * Modal dialog: `modal(open = showDialog, onClose = { showDialog = false }) { h3 { +"Title" }; … }`.
 * Escape and a click on the backdrop call [onClose].
 */
fun NodeBuilder.modal(
    open: Boolean,
    onClose: (() -> Unit)? = null,
    className: String? = null,
    closeOnBackdropClick: Boolean = true,
    key: Any? = null,
    children: NodeBuilder.() -> Unit,
) = ModalComponent(ModalProps(open, onClose, className, closeOnBackdropClick, children), key)

/**
 * Keeps [children] mounted while they animate out (like Framer Motion's `AnimatePresence`):
 *
 * ```kotlin
 * presence(visible = open, exitMs = 200) { leaving ->
 *     div(className = classNames("panel", "leaving" to leaving)) { … }
 * }
 * ```
 * Entering plays the element's CSS `animation` as usual. When [visible] turns false the children render once more with
 * `leaving = true` (switch to an exit animation or transition there) and are removed after [exitMs].
 * Becoming visible again during the exit cancels it.
 */
fun NodeBuilder.presence(visible: Boolean, exitMs: Long, key: Any? = null, children: NodeBuilder.(leaving: Boolean) -> Unit) =
    PresenceComponent(PresenceProps(visible, exitMs, children), key)

/**
 * [presence] for every item of a list: items removed from [items] stay rendered at their old position with
 * `leaving = true` for [exitMs], then they are removed. New items mount normally (their CSS `animation` plays):
 *
 * ```kotlin
 * presenceList(parties, key = { it.id }, exitMs = 200) { party, leaving ->
 *     div(className = classNames("row", "leaving" to leaving)) { +party.leader }
 * }
 * ```
 * ```css
 * .row { animation: row-in 200ms ease-out }
 * .row.leaving { animation: row-out 200ms ease-in forwards; pointer-events: none }
 * ```
 * Items need a stable [key]. An item that comes back during its exit is a normal item again. The items are rendered
 * directly into the parent (no wrapper element). For [sortableList] use its `exitMs` instead.
 */
fun <T> NodeBuilder.presenceList(
    items: List<T>,
    key: (T) -> Any?,
    exitMs: Long,
    listKey: Any? = null,
    children: NodeBuilder.(item: T, leaving: Boolean) -> Unit,
) {
    @Suppress("UNCHECKED_CAST")
    PresenceListComponent(
        PresenceListProps(items, key as (Any?) -> Any?, exitMs, children as NodeBuilder.(Any?, Boolean) -> Unit),
        listKey,
    )
}

/**
 * Drag-to-reorder list, controlled like React: [items] are rendered in order and dropping an item calls [onReorder]
 * with the reordered list (store it in state):
 *
 * ```kotlin
 * var tasks by useState(listOf("Wash", "Cook", "Sleep"))
 * sortableList(tasks, key = { it }, onReorder = { tasks = it }) { task, dragging ->
 *     span { +task }
 * }
 * ```
 * A drag starts after the mouse moved a few pixels, so clicks inside items keep working. With [handle] = true only
 * elements with the class `guilib-drag-handle` start a drag (use it when items contain inputs). [horizontal] sorts
 * along x (e.g. in a horizontally scrolling row). Escape cancels a drag. Items need a stable [key].
 * Dragging near the edge of a scroll container scrolls it. Items are focusable; Alt + ↑/↓ (←/→ when horizontal),
 * Alt + Home/End move the focused item.
 *
 * Lists with the same [group] exchange items (kanban boards): dragged outside its list an item follows the mouse as a
 * ghost (rendered in a portal; style it through [className]/[itemClassName], selectors relying on other ancestors
 * don't reach it), the list under the mouse opens a gap, and dropping there calls the source's [onReorder] without the
 * item and the target's [onReorder] with it. Dropping elsewhere cancels. Give empty lists a `min-height` so they can
 * receive items.
 *
 * With [exitMs] > 0 items removed from [items] stay at their old position for that long with the item class `.leaving`
 * (not clickable, no drag starts meanwhile), like [presenceList]: give `.leaving` an exit animation in CSS. Items
 * dragged into another list of the [group] move without an exit.
 *
 * Styled with `.guilib-sortable` (`.horizontal`, `.handle`, `.sorting`, `.receiving`), `.guilib-sortable-item`
 * (`.dragging`, `.away` while outside its list, `.guilib-sortable-ghost`, `.leaving`).
 */
fun <T> NodeBuilder.sortableList(
    items: List<T>,
    key: (T) -> Any?,
    onReorder: ((List<T>) -> Unit)?,
    horizontal: Boolean = false,
    handle: Boolean = false,
    className: String? = null,
    itemClassName: String? = null,
    listKey: Any? = null,
    group: String? = null,
    exitMs: Long = 0,
    children: NodeBuilder.(item: T, dragging: Boolean) -> Unit,
) {
    @Suppress("UNCHECKED_CAST")
    SortableComponent(
        SortableProps(
            items, key as (Any?) -> Any?, onReorder as ((List<Any?>) -> Unit)?, horizontal, handle, className, itemClassName, group, exitMs,
            children as NodeBuilder.(Any?, Boolean) -> Unit,
        ),
        listKey,
    )
}

/**
 * Inline color picker (saturation/value area, hue slider, optional alpha, hex input), controlled like React:
 * `colorPicker(value = color, onChange = { color = it })`. Colors are ARGB ints (`0xFF5B8DEF.toInt()`).
 */
fun NodeBuilder.colorPicker(
    value: Int,
    onChange: ((Int) -> Unit)? = null,
    alpha: Boolean = false,
    className: String? = null,
    key: Any? = null,
) = net.sbo.guilib.core.controls.ColorPickerComponent(net.sbo.guilib.core.controls.ColorPickerProps(value, onChange, alpha, className), key)

/** A swatch button that opens a color picker popover: `colorInput(value = color, onChange = { color = it })`. */
fun NodeBuilder.colorInput(
    value: Int,
    onChange: ((Int) -> Unit)? = null,
    alpha: Boolean = false,
    className: String? = null,
    disabled: Boolean = false,
    key: Any? = null,
) = net.sbo.guilib.core.controls.ColorInputComponent(net.sbo.guilib.core.controls.ColorInputProps(value, onChange, alpha, className, disabled), key)
