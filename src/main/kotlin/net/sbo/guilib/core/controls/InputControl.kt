package net.sbo.guilib.core.controls

import net.sbo.guilib.core.css.PseudoState
import net.sbo.guilib.core.dom.Document
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.TextNode
import net.sbo.guilib.core.event.CharEvent
import net.sbo.guilib.core.event.EventDispatcher
import net.sbo.guilib.core.event.EventType
import net.sbo.guilib.core.event.InputEvent
import net.sbo.guilib.core.event.KeyboardEvent
import net.sbo.guilib.core.event.MouseEvent
import net.sbo.guilib.core.event.UIEvent
import net.sbo.guilib.core.layout.TextStyle

/**
 * Behaviour of `<input>` (text, password, number) and `<input type="checkbox">`.
 *
 * Like React, inputs are *controlled* when a `value` (or `checked`) is passed: typing fires `onInput`/`onChange`
 * and the displayed text only changes when the parent passes the new value back. Without `value` the input keeps
 * its own text (uncontrolled). Unlike the DOM, `onChange` fires on every edit (React semantics).
 *
 * The input's children are generated here (like a shadow DOM) and can be styled with
 * `.guilib-input-text`, `.guilib-placeholder`, `.guilib-caret`, `.guilib-selection` and `.guilib-check`.
 */
internal class InputControl(val el: Element) {
    var text = ""
    var caret = 0
    var anchor = 0
    var dragging = false
    var blinkStart = 0L
    private var builtFor: String? = null

    private lateinit var textSpan: Element
    private lateinit var textNode: TextNode
    private lateinit var caretEl: Element
    private lateinit var selectionEl: Element

    val type: String get() = (el.getAttribute("type") as? String)?.lowercase() ?: "text"
    val isCheckbox get() = type == "checkbox"
    val controlled get() = el.getAttribute("value") != null
    private val placeholder get() = el.getAttribute("placeholder") as? String
    private val maxLength get() = (el.getAttribute("maxlength") as? Int) ?: Int.MAX_VALUE

    val selStart get() = minOf(caret, anchor)
    val selEnd get() = maxOf(caret, anchor)
    val hasSelection get() = caret != anchor

    /** (Re)builds the internal children when the input type changes; syncs controlled values. */
    fun sync() {
        if (builtFor != type) build()
        if (!isCheckbox) {
            val v = el.getAttribute("value") as? String
            if (v != null && v != text) {
                text = v
                caret = caret.coerceAtMost(text.length)
                anchor = anchor.coerceAtMost(text.length)
            }
            updateText()
        }
    }

    private fun build() {
        builtFor = type
        el.internalChildren = true
        if (isCheckbox) {
            val check = Element("span").also { it.className = "guilib-check" }
            check.setChildren(listOf(TextNode("✓")))
            el.setChildren(listOf(check))
            return
        }
        // What the user types is shown as is: `§` must not turn into formatting (and shift the caret).
        textNode = TextNode("").also { it.formattingCodes = false }
        textSpan = Element("span").also { it.className = "guilib-input-text" }
        textSpan.setChildren(listOf(textNode))
        selectionEl = Element("div").also { it.className = "guilib-selection" }
        caretEl = Element("div").also { it.className = "guilib-caret" }
        el.setChildren(listOf(selectionEl, textSpan, caretEl))
        (el.getAttribute("value") as? String)?.let { text = it; caret = it.length; anchor = caret }
    }

    private fun displayText(): String = if (type == "password") "•".repeat(text.length) else text

    private fun updateText() {
        val shown = when {
            text.isNotEmpty() -> displayText()
            !placeholder.isNullOrEmpty() -> placeholder!!
            else -> " " // keep one line of height
        }
        textNode.data = shown
        textSpan.className = if (text.isEmpty() && !placeholder.isNullOrEmpty()) "guilib-input-text guilib-placeholder" else "guilib-input-text"
    }

    private fun measure(s: String): Float {
        val doc = el.ownerDocument ?: return 0f
        return doc.measurer.width(s, TextStyle.of(el.style))
    }

    /** Positions caret/selection after layout and keeps the caret scrolled into view. */
    fun afterLayout(now: Long) {
        if (isCheckbox || builtFor == null) return
        val b = el.box
        val line = b.paragraphs.firstOrNull()?.lines?.firstOrNull()
        val top = (b.paragraphs.firstOrNull()?.y ?: b.contentY) - b.border.top + (line?.y ?: 0f)
        val height = line?.height ?: b.contentHeight
        val shown = displayText()
        val caretX = b.padding.left + measure(shown.substring(0, caret.coerceIn(0, shown.length)))

        // Scroll horizontally so the caret stays visible.
        val visibleW = b.contentWidth
        val rel = caretX - b.padding.left
        if (rel - el.scrollLeft > visibleW - 1f) el.scrollLeft = rel - visibleW + 1f
        if (rel < el.scrollLeft) el.scrollLeft = rel

        val focused = el.isFocused
        val blinkOn = ((now - blinkStart) / 500L) % 2L == 0L
        setStyle(caretEl, "left: ${caretX}px; top: ${top}px; height: ${height}px; visibility: ${if (focused && blinkOn && !hasSelection) "visible" else "hidden"}")
        if (hasSelection && focused) {
            val x0 = b.padding.left + measure(shown.substring(0, selStart))
            val x1 = b.padding.left + measure(shown.substring(0, selEnd))
            setStyle(selectionEl, "display: block; left: ${x0}px; top: ${top}px; width: ${x1 - x0}px; height: ${height}px")
        } else {
            setStyle(selectionEl, "display: none")
        }
    }

    private fun setStyle(e: Element, css: String) {
        if (e.inlineStyle != css) e.inlineStyle = css
    }

    // ---- editing -----------------------------------------------------------------------------------------------

    private fun filter(s: String): String {
        var r = s.replace("\n", " ").replace("\r", "").filter { it >= ' ' }
        if (type == "number") r = r.filter { it.isDigit() || it == '-' || it == '.' || it == ',' }
        return r
    }

    private fun replaceSelection(insert: String) {
        val ins = filter(insert)
        val room = maxLength - (text.length - (selEnd - selStart))
        val clipped = if (ins.length > room) ins.substring(0, room.coerceAtLeast(0)) else ins
        val next = text.substring(0, selStart) + clipped + text.substring(selEnd)
        val newCaret = selStart + clipped.length
        commit(next, newCaret)
    }

    private fun commit(next: String, newCaret: Int) {
        caret = newCaret; anchor = newCaret
        touch()
        if (next == text) return
        if (!controlled) {
            text = next
            updateText()
        }
        EventDispatcher.dispatch(InputEvent(EventType.INPUT, next), el)
        EventDispatcher.dispatch(InputEvent(EventType.CHANGE, next), el)
    }

    private fun touch() {
        blinkStart = el.ownerDocument?.now() ?: 0L
        el.ownerDocument?.invalidateLayout()
    }

    private fun moveCaret(to: Int, extend: Boolean) {
        caret = to.coerceIn(0, text.length)
        if (!extend) anchor = caret
        touch()
    }

    private fun wordLeft(from: Int): Int {
        var i = from
        while (i > 0 && text[i - 1] == ' ') i--
        while (i > 0 && text[i - 1] != ' ') i--
        return i
    }

    private fun wordRight(from: Int): Int {
        var i = from
        while (i < text.length && text[i] != ' ') i++
        while (i < text.length && text[i] == ' ') i++
        return i
    }

    fun indexAt(clientX: Float): Int {
        val r = el.getBoundingClientRect()
        val x = clientX - r.x - el.box.border.left - el.box.padding.left + el.scrollLeft
        val shown = displayText()
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in 0..shown.length) {
            val d = kotlin.math.abs(measure(shown.substring(0, i)) - x)
            if (d < bestDist) {
                bestDist = d; best = i
            } else break
        }
        return best
    }

    /** Default actions; returns true if the event was consumed. */
    fun handle(ev: UIEvent, doc: Document): Boolean {
        if (el.disabled) return false
        if (isCheckbox) return handleCheckbox(ev)
        when (ev) {
            is MouseEvent -> when (ev.type) {
                EventType.MOUSEDOWN -> if (ev.button == 0) {
                    val i = indexAt(ev.clientX)
                    moveCaret(i, ev.shiftKey)
                    dragging = true
                    return true
                }
                EventType.MOUSEMOVE -> if (dragging) {
                    moveCaret(indexAt(ev.clientX), true)
                    return true
                }
                EventType.MOUSEUP -> dragging = false
                EventType.DBLCLICK -> {
                    anchor = wordLeft(caret); caret = wordRight(caret)
                    touch(); return true
                }
            }
            is KeyboardEvent -> if (ev.type == EventType.KEYDOWN) return handleKey(ev, doc)
            is CharEvent -> {
                if (ev.modifiers.ctrl && !ev.modifiers.alt) return false
                replaceSelection(ev.char)
                return true
            }
        }
        return false
    }

    private fun handleKey(ev: KeyboardEvent, doc: Document): Boolean {
        val ctrl = ev.ctrlKey || ev.modifiers.meta
        val shift = ev.shiftKey
        when (ev.key) {
            "ArrowLeft" -> moveCaret(if (!shift && hasSelection) selStart else if (ctrl) wordLeft(caret) else caret - 1, shift)
            "ArrowRight" -> moveCaret(if (!shift && hasSelection) selEnd else if (ctrl) wordRight(caret) else caret + 1, shift)
            "Home" -> moveCaret(0, shift)
            "End" -> moveCaret(text.length, shift)
            "Backspace" -> when {
                hasSelection -> replaceSelection("")
                caret > 0 -> {
                    val from = if (ctrl) wordLeft(caret) else caret - 1
                    commit(text.substring(0, from) + text.substring(caret), from)
                }
            }
            "Delete" -> when {
                hasSelection -> replaceSelection("")
                caret < text.length -> {
                    val to = if (ctrl) wordRight(caret) else caret + 1
                    commit(text.substring(0, caret) + text.substring(to), caret)
                }
            }
            "Enter" -> {} // no forms: consumed so it doesn't activate anything else
            else -> {
                if (ctrl) when (ev.key.lowercase()) {
                    "a" -> {
                        anchor = 0; caret = text.length; touch(); return true
                    }
                    "c" -> {
                        if (hasSelection && type != "password") doc.clipboard.set(text.substring(selStart, selEnd)); return true
                    }
                    "x" -> {
                        if (hasSelection && type != "password") {
                            doc.clipboard.set(text.substring(selStart, selEnd)); replaceSelection("")
                        }
                        return true
                    }
                    "v" -> {
                        replaceSelection(doc.clipboard.get()); return true
                    }
                }
                return false
            }
        }
        return true
    }

    private fun handleCheckbox(ev: UIEvent): Boolean {
        val toggle = (ev.type == EventType.CLICK) || (ev is KeyboardEvent && ev.type == EventType.KEYDOWN && ev.key == " ")
        if (!toggle) return false
        val checked = !el.hasState(PseudoState.CHECKED)
        val hasHandler = el.hasHandler(EventType.CHANGE) || el.hasHandler(EventType.INPUT)
        // Controlled when someone listens: they must pass the new `checked` back. Otherwise toggle ourselves.
        if (!hasHandler) el.setAttribute("checked", checked)
        val value = el.getAttribute("value") as? String ?: "on"
        EventDispatcher.dispatch(InputEvent(EventType.INPUT, value, checked), el)
        EventDispatcher.dispatch(InputEvent(EventType.CHANGE, value, checked), el)
        return true
    }

    fun onBlur() {
        dragging = false
        anchor = caret
    }
}
