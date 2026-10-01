package net.sbo.guilib.core.dsl

import net.sbo.guilib.core.controls.ModalComponent
import net.sbo.guilib.core.controls.ModalProps
import net.sbo.guilib.core.controls.PresenceComponent
import net.sbo.guilib.core.controls.PresenceProps
import net.sbo.guilib.core.controls.SelectComponent
import net.sbo.guilib.core.controls.SelectOption
import net.sbo.guilib.core.controls.SelectProps
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
