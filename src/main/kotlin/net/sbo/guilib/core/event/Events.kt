package net.sbo.guilib.core.event

import net.sbo.guilib.core.dom.Element

/**
 * Base class of all events, modelled after the DOM `Event`. Handlers receive the concrete subtype
 * (e.g. `onClick: (MouseEvent) -> Unit`).
 */
open class UIEvent(val type: String, val bubbles: Boolean = true) {
    /** The element the event was dispatched to (the deepest element under the mouse, or the focused element). */
    lateinit var target: Element
        internal set

    /** The element whose handler is currently running. */
    lateinit var currentTarget: Element
        internal set

    var eventPhase: Phase = Phase.NONE
        internal set

    var propagationStopped = false
        private set
    var defaultPrevented = false
        private set

    /** Stops the event from reaching further elements (like the DOM). */
    fun stopPropagation() {
        propagationStopped = true
    }

    /** Suppresses the library's default action (e.g. focusing on mousedown, scrolling on wheel, typing into inputs). */
    fun preventDefault() {
        defaultPrevented = true
    }

    enum class Phase { NONE, CAPTURING, AT_TARGET, BUBBLING }
}

/** Keyboard modifier state shared by mouse and keyboard events. */
data class Modifiers(val shift: Boolean = false, val ctrl: Boolean = false, val alt: Boolean = false, val meta: Boolean = false) {
    companion object {
        val NONE = Modifiers()
    }
}

/**
 * `click`, `dblclick`, `contextmenu`, `mousedown`, `mouseup`, `mousemove`, `mouseenter`, `mouseleave`.
 * [clientX]/[clientY] are GUI pixels relative to the screen; [button]: 0 = left, 1 = middle, 2 = right (like the DOM).
 */
open class MouseEvent(
    type: String,
    val clientX: Float,
    val clientY: Float,
    val button: Int = 0,
    val modifiers: Modifiers = Modifiers.NONE,
    bubbles: Boolean = true,
) : UIEvent(type, bubbles) {
    val shiftKey get() = modifiers.shift
    val ctrlKey get() = modifiers.ctrl
    val altKey get() = modifiers.alt

    /** Mouse position relative to [currentTarget]'s border box. */
    val offsetX get() = clientX - currentTarget.getBoundingClientRect().x
    val offsetY get() = clientY - currentTarget.getBoundingClientRect().y
}

/** `wheel`. Positive [deltaY] scrolls down, in GUI pixels. */
class WheelEvent(clientX: Float, clientY: Float, val deltaX: Float, val deltaY: Float, modifiers: Modifiers = Modifiers.NONE) :
    MouseEvent("wheel", clientX, clientY, 0, modifiers)

/**
 * `keydown` / `keyup`. [key] follows the DOM naming (`"a"`, `"Enter"`, `"Escape"`, `"ArrowUp"`, `"Backspace"`, `"Tab"` …),
 * [keyCode] is the raw GLFW key code.
 */
class KeyboardEvent(type: String, val key: String, val keyCode: Int, val modifiers: Modifiers = Modifiers.NONE, val repeat: Boolean = false) :
    UIEvent(type) {
    val shiftKey get() = modifiers.shift
    val ctrlKey get() = modifiers.ctrl
    val altKey get() = modifiers.alt
}

/** Typed characters (`keypress`-like). Inputs use it; most code should use [KeyboardEvent] or `onInput`. */
class CharEvent(val char: String, val modifiers: Modifiers = Modifiers.NONE) : UIEvent("char")

/** `focus` / `blur` (don't bubble) and `focusin` / `focusout` (bubble), like the DOM. */
class FocusEvent(type: String, val relatedTarget: Element?, bubbles: Boolean) : UIEvent(type, bubbles)

/** `input` (every change) and `change` (committed change) of form controls. */
class InputEvent(type: String, val value: String, val checked: Boolean = false) : UIEvent(type)

/** `scroll`, fired on a scroll container after its scroll position changed. Doesn't bubble. */
class ScrollEvent(val scrollLeft: Float, val scrollTop: Float) : UIEvent("scroll", bubbles = false)

/** Event names used as handler keys. */
object EventType {
    const val CLICK = "click"
    const val DBLCLICK = "dblclick"
    const val CONTEXTMENU = "contextmenu"
    const val MOUSEDOWN = "mousedown"
    const val MOUSEUP = "mouseup"
    const val MOUSEMOVE = "mousemove"
    const val MOUSEENTER = "mouseenter"
    const val MOUSELEAVE = "mouseleave"
    const val WHEEL = "wheel"
    const val KEYDOWN = "keydown"
    const val KEYUP = "keyup"
    const val CHAR = "char"
    const val FOCUS = "focus"
    const val BLUR = "blur"
    const val FOCUSIN = "focusin"
    const val FOCUSOUT = "focusout"
    const val INPUT = "input"
    const val CHANGE = "change"
    const val SCROLL = "scroll"
}
