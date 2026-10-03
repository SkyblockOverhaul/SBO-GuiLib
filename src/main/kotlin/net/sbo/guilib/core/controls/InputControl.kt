package net.sbo.guilib.core.controls

import net.sbo.guilib.core.css.PseudoState
import net.sbo.guilib.core.css.TextAlign
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
import java.text.BreakIterator

/**
 * Behaviour of `<input>` (text, password, number) and `<input type="checkbox">`.
 *
 * Like React, inputs are *controlled* when a `value` (or `checked`) is passed: typing fires `onInput`/`onChange`
 * and the displayed text only changes when the parent passes the new value back. Without `value` the input keeps
 * its own text (uncontrolled). Unlike the DOM, `onChange` fires on every edit (React semantics).
 *
 * The input's children are generated here (like a shadow DOM) and can be styled with
 * `.guilib-input-text`, `.guilib-placeholder`, `.guilib-caret`, `.guilib-selection` and `.guilib-check`. The
 * placeholder text also takes `input::placeholder` rules.
 */
internal class InputControl(val el: Element) : EditableControl {
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
        val showPlaceholder = text.isEmpty() && !placeholder.isNullOrEmpty()
        textSpan.className = if (showPlaceholder) "guilib-input-text guilib-placeholder" else "guilib-input-text"
        textSpan.pseudoOfParent = if (showPlaceholder) "placeholder" else null
    }

    private fun measure(s: String): Float {
        val doc = el.ownerDocument ?: return 0f
        return doc.measurer.width(s, TextStyle.of(el.style))
    }

    /** Positions caret/selection after layout and keeps the caret scrolled into view. */
    override fun afterLayout(now: Long) {
        if (isCheckbox || builtFor == null) return
        val b = el.box
        val line = b.paragraphs.firstOrNull()?.lines?.firstOrNull()
        val top = (b.paragraphs.firstOrNull()?.y ?: b.contentY) - b.border.top + (line?.y ?: 0f)
        val height = line?.height ?: b.contentHeight
        val shown = displayText()
        val shift = alignShift()
        val rel = measure(shown.substring(0, caret.coerceIn(0, shown.length)))
        val caretX = b.padding.left + shift + rel

        // Scroll horizontally so the caret stays visible (aligned text fits, so it never scrolls).
        val visibleW = b.contentWidth
        if (shift > 0f) el.scrollLeft = 0f
        if (rel - el.scrollLeft > visibleW - 1f) el.scrollLeft = rel - visibleW + 1f
        if (rel < el.scrollLeft) el.scrollLeft = rel

        val focused = el.isFocused
        val blinkOn = ((now - blinkStart) / 500L) % 2L == 0L
        setStyle(caretEl, "left: ${caretX}px; top: ${top}px; height: ${height}px; visibility: ${if (focused && blinkOn && !hasSelection) "visible" else "hidden"}${caretColorCss(el)}")
        if (hasSelection && focused) {
            val x0 = b.padding.left + shift + measure(shown.substring(0, selStart))
            val x1 = b.padding.left + shift + measure(shown.substring(0, selEnd))
            setStyle(selectionEl, "display: block; left: ${x0}px; top: ${top}px; width: ${x1 - x0}px; height: ${height}px")
        } else {
            setStyle(selectionEl, "display: none")
        }
    }

    /** Where `text-align` puts the typed text (an empty field counts as zero width, so the caret sits in the middle). */
    private fun alignShift() = alignShift(el, el.box.contentWidth, measure(displayText()))

    private fun setStyle(e: Element, css: String) {
        if (e.inlineStyle != css) e.inlineStyle = css
    }

    // ---- editing -----------------------------------------------------------------------------------------------

    private fun filter(s: String): String {
        var r = s.replace("\n", " ").replace("\r", "").filter { it >= ' ' }
        // Digits, sign, decimal marks and the k/m/b shorthand of numberInput ("1.5m").
        if (type == "number") r = r.filter { it.isDigit() || it in "+-.,kmbKMB" }
        return r
    }

    private fun replaceSelection(insert: String) {
        val ins = filter(insert)
        val room = maxLength - (text.length - (selEnd - selStart))
        var clipped = if (ins.length > room) ins.substring(0, room.coerceAtLeast(0)) else ins
        // Don't cut a character (emoji, combining accent) in half at the length limit.
        if (clipped.length < ins.length) clipped = clipped.substring(0, boundaryAtOrBefore(ins, clipped.length))
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

    // Caret positions are user-perceived character boundaries, so an emoji (two UTF-16 chars) or a letter with a
    // combining accent is never split.
    private fun charLeft(from: Int): Int = if (from <= 0) 0 else breaker().preceding(from.coerceAtMost(text.length))

    private fun charRight(from: Int): Int = if (from >= text.length) text.length else breaker().following(from.coerceAtLeast(0))

    private fun breaker() = BreakIterator.getCharacterInstance().also { it.setText(text) }

    private fun boundaryAtOrBefore(s: String, i: Int): Int {
        if (i <= 0 || i >= s.length) return i.coerceIn(0, s.length)
        val b = BreakIterator.getCharacterInstance().also { it.setText(s) }
        return if (b.isBoundary(i)) i else b.preceding(i)
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
        // The client rect includes transforms; text is measured untransformed.
        val scale = if (el.box.width > 0f) r.width / el.box.width else 1f
        val x = (clientX - r.x) / scale - el.box.border.left - el.box.padding.left - alignShift() + el.scrollLeft
        val shown = displayText()
        var best = 0
        var bestDist = Float.MAX_VALUE
        var i = 0
        while (true) {
            val d = kotlin.math.abs(measure(shown.substring(0, i)) - x)
            if (d < bestDist) {
                bestDist = d; best = i
            } else break
            if (i >= text.length) break
            i = charRight(i)
        }
        return best
    }

    /** Default actions; returns true if the event was consumed. */
    override fun handle(ev: UIEvent, doc: Document): Boolean {
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
            "ArrowLeft" -> moveCaret(if (!shift && hasSelection) selStart else if (ctrl) wordLeft(caret) else charLeft(caret), shift)
            "ArrowRight" -> moveCaret(if (!shift && hasSelection) selEnd else if (ctrl) wordRight(caret) else charRight(caret), shift)
            "Home" -> moveCaret(0, shift)
            "End" -> moveCaret(text.length, shift)
            "Backspace" -> when {
                hasSelection -> replaceSelection("")
                caret > 0 -> {
                    val from = if (ctrl) wordLeft(caret) else charLeft(caret)
                    commit(text.substring(0, from) + text.substring(caret), from)
                }
            }
            "Delete" -> when {
                hasSelection -> replaceSelection("")
                caret < text.length -> {
                    val to = if (ctrl) wordRight(caret) else charRight(caret)
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

    override fun onBlur() {
        dragging = false
        anchor = caret
    }
}

/** Inline `background-color` for the caret of [el] when it sets `caret-color` (`auto` keeps the ua.css currentColor). */
internal fun caretColorCss(el: Element): String {
    val c = el.style.caretColor ?: return ""
    val rgba = (c shl 8) or (c ushr 24)
    return "; background-color: #" + Integer.toHexString(rgba).padStart(8, '0')
}

/** Offset of a line [width] wide inside [available] for [el]'s `text-align`; like the layout, never negative. */
internal fun alignShift(el: Element, available: Float, width: Float): Float = when (el.style.textAlign) {
    TextAlign.LEFT -> 0f
    TextAlign.CENTER -> ((available - width) / 2f).coerceAtLeast(0f)
    TextAlign.RIGHT -> (available - width).coerceAtLeast(0f)
}
