package net.sbo.guilib.core.dsl

import net.sbo.guilib.core.controls.ModalComponent
import net.sbo.guilib.core.controls.ModalProps
import net.sbo.guilib.core.controls.PresenceComponent
import net.sbo.guilib.core.controls.PresenceProps
import net.sbo.guilib.core.controls.SelectComponent
import net.sbo.guilib.core.controls.SelectOption
import net.sbo.guilib.core.controls.SelectProps
import net.sbo.guilib.core.controls.SliderComponent
import net.sbo.guilib.core.controls.SliderProps
import net.sbo.guilib.core.controls.SortableComponent
import net.sbo.guilib.core.controls.SortableProps
import net.sbo.guilib.core.controls.SwitchComponent
import net.sbo.guilib.core.controls.SwitchProps
import net.sbo.guilib.core.controls.TooltipComponent
import net.sbo.guilib.core.controls.TooltipProps
import net.sbo.guilib.core.dom.VText
import net.sbo.guilib.core.event.InputEvent

/** Collects the `option(...)` entries of a [select]. */
@GuiDsl
class SelectBuilder {
    internal val options = ArrayList<SelectOption>()

    /** An entry: `option("easy") { +"Easy" }`. */
    fun option(value: String, disabled: Boolean = false, label: NodeBuilder.() -> Unit) {
        val text = NodeBuilder().apply(label).nodes.filterIsInstance<VText>().joinToString("") { it.text }
        options += SelectOption(value, text, disabled)
    }

    /** An entry with a plain label: `option("easy", "Easy")`. */
    fun option(value: String, label: String, disabled: Boolean = false) {
        options += SelectOption(value, label, disabled)
    }
}

/**
 * Dropdown, controlled like React: `select(value = mode, onChange = { mode = it.value }) { option("a") { +"A" } }`.
 * The menu opens in a portal, so it's never clipped by scroll containers.
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
    options: SelectBuilder.() -> Unit,
) {
    val opts = SelectBuilder().apply(options).options
    SelectComponent(SelectProps(value, onChange, opts, className, id, style, disabled, placeholder), key)
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
 * Styled with `.guilib-sortable` (`.horizontal`, `.handle`, `.sorting`), `.guilib-sortable-item` (`.dragging`).
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
    children: NodeBuilder.(item: T, dragging: Boolean) -> Unit,
) {
    @Suppress("UNCHECKED_CAST")
    SortableComponent(
        SortableProps(
            items, key as (Any?) -> Any?, onReorder as ((List<Any?>) -> Unit)?, horizontal, handle, className, itemClassName,
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
