package net.sbo.guilib.core.controls

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
import kotlin.math.abs

/**
 * Behaviour of `<textarea>`: multi-line text editing with word wrap, caret, mouse/keyboard selection, clipboard and
 * vertical scrolling. Controlled like [InputControl] (pass `value`, update it in `onChange`).
 *
 * The control wraps the text itself (one `.guilib-textarea-line` element per visual line), so the caret and the
 * selection always match what is shown. Styled with `textarea`, `.guilib-textarea-line`, `.guilib-placeholder`,
 * `.guilib-caret` and `.guilib-selection`.
 */
internal class TextAreaControl(val el: Element) : EditableControl {
    var text = ""
    var caret = 0
    var anchor = 0
    private var dragging = false
    private var blinkStart = 0L
    /** x the caret tries to keep when moving up/down. */
    private var goalX: Float? = null
    private var built = false

    /** A visual line: text indices [start, end) (the end excludes a hard line break). */
    data class VLine(val start: Int, val end: Int)

    var lines: List<VLine> = listOf(VLine(0, 0))
        private set
    private var wrappedFor: String? = null
    private var wrapWidth = -1f

    private val lineEls = ArrayList<Element>()
    private lateinit var caretEl: Element
    private val selectionEls = ArrayList<Element>()

    private val controlled get() = el.getAttribute("value") != null
    private val placeholder get() = el.getAttribute("placeholder") as? String
    private val maxLength get() = (el.getAttribute("maxlength") as? Int) ?: Int.MAX_VALUE

    private val selStart get() = minOf(caret, anchor)
    private val selEnd get() = maxOf(caret, anchor)
    private val hasSelection get() = caret != anchor

    fun sync() {
        if (!built) {
            built = true
            el.internalChildren = true
            caretEl = Element("div").also { it.className = "guilib-caret" }
            (el.getAttribute("value") as? String)?.let { text = it; caret = it.length; anchor = caret }
        }
        val v = el.getAttribute("value") as? String
        if (v != null && v != text) {
            text = v
            caret = caret.coerceAtMost(text.length)
            anchor = anchor.coerceAtMost(text.length)
        }
        rewrap()
    }

    // ---- wrapping ------------------------------------------------------------------------------------------------

    private fun style() = TextStyle.of(el.style)

    private fun measure(s: String): Float = el.ownerDocument?.measurer?.width(s, style()) ?: 0f

    private fun breaker(s: String) = BreakIterator.getCharacterInstance().also { it.setText(s) }

    /** Splits the text into visual lines that fit [width] (word wrap; words longer than a line break anywhere). */
    internal fun wrap(width: Float): List<VLine> {
        val out = ArrayList<VLine>()
        val chars = breaker(text)
        var lineStart = 0
        while (true) {
            val nl = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            var start = lineStart
            while (true) {
                if (width <= 0f || measure(text.substring(start, nl)) <= width) {
                    out += VLine(start, nl); break
                }
                // The longest piece that fits (at least one character).
                var fit = chars.following(start).coerceAtMost(nl)
                while (fit < nl) {
                    val next = chars.following(fit).coerceAtMost(nl)
                    if (measure(text.substring(start, next)) > width) break
                    fit = next
                }
                // Prefer breaking after the last space in it (unless the piece ends exactly at a word end).
                var cut = fit
                if (fit < nl && text[fit] != ' ') {
                    val space = text.lastIndexOf(' ', fit - 1)
                    if (space >= start && space + 1 > start && space + 1 < fit) cut = space + 1
                }
                // Spaces after the cut stay on this line (they don't take room visually).
                while (cut < nl && text[cut] == ' ') cut++
                if (cut >= nl) {
                    out += VLine(start, nl); break
                }
                out += VLine(start, cut)
                start = cut
            }
            if (nl >= text.length) break
            lineStart = nl + 1
        }
        return out
    }

    private fun rewrap() {
        val width = el.box.contentWidth
        if (wrappedFor == text && wrapWidth == width && lineEls.isNotEmpty()) return
        wrappedFor = text
        wrapWidth = width
        lines = wrap(width)
        val showPlaceholder = text.isEmpty() && !placeholder.isNullOrEmpty()
        val shown = if (showPlaceholder) listOf(placeholder!!) else lines.map { text.substring(it.start, it.end) }
        while (lineEls.size < shown.size) {
            lineEls += Element("div").also { e ->
                e.setChildren(listOf(TextNode("").also { it.formattingCodes = false }))
            }
        }
        while (lineEls.size > shown.size) lineEls.removeAt(lineEls.size - 1)
        shown.forEachIndexed { i, s ->
            val e = lineEls[i]
            e.className = if (showPlaceholder) "guilib-textarea-line guilib-placeholder" else "guilib-textarea-line"
            // An empty line still needs its height.
            (e.children.first() as TextNode).data = s.ifEmpty { " " }
        }
        while (selectionEls.size < lines.size) selectionEls += Element("div").also { it.className = "guilib-selection" }
        while (selectionEls.size > lines.size) selectionEls.removeAt(selectionEls.size - 1)
        el.setChildren(selectionEls + lineEls + caretEl)
        el.ownerDocument?.invalidateLayout()
    }

    // ---- positions -----------------------------------------------------------------------------------------------

    /** Visual line of index [i]: a soft-wrap boundary belongs to the following line. */
    private fun lineOf(i: Int): Int {
        for (l in lines.indices.reversed()) if (lines[l].start <= i) return l
        return 0
    }

    private fun xIn(line: Int, i: Int): Float {
        val l = lines[line]
        return measure(text.substring(l.start, i.coerceIn(l.start, l.end)))
    }

    /** Index in [line] closest to x (relative to the line's text start). */
    private fun indexInLine(line: Int, x: Float): Int {
        val l = lines[line]
        val chars = breaker(text)
        var best = l.start
        var bestDist = Float.MAX_VALUE
        var i = l.start
        while (true) {
            val d = abs(measure(text.substring(l.start, i)) - x)
            if (d < bestDist) {
                bestDist = d; best = i
            } else break
            if (i >= l.end) break
            i = chars.following(i).coerceAtMost(l.end)
        }
        return best
    }

    /** Text index under a point in client coordinates. */
    fun indexAt(clientX: Float, clientY: Float): Int {
        if (lineEls.isEmpty() || text.isEmpty()) return 0
        val r = el.getBoundingClientRect()
        val scale = if (el.box.width > 0f) r.width / el.box.width else 1f
        val x = (clientX - r.x) / scale
        val y = (clientY - r.y) / scale + el.scrollTop
        var line = lines.lastIndex
        for (i in lines.indices) {
            val b = lineEls.getOrNull(i)?.box ?: break
            if (y < b.y + b.height) {
                line = i; break
            }
        }
        val lb = lineEls.getOrNull(line)?.box
        return indexInLine(line, x - (lb?.let { it.x + it.contentX } ?: el.box.contentX) + el.scrollLeft)
    }

    /** Positions caret and selection after layout and keeps the caret scrolled into view. */
    override fun afterLayout(now: Long) {
        if (!built) return
        if (el.box.contentWidth != wrapWidth || wrappedFor != text) {
            rewrap()
            return // positioned next frame, once the new lines are laid out
        }
        if (lineEls.size != lines.size && text.isNotEmpty()) return
        val focused = el.isFocused
        val cl = lineOf(caret)
        val lb = lineEls.getOrNull(cl)?.box ?: return
        val caretX = lb.x + lb.contentX + xIn(cl, caret)
        val top = lb.y
        val height = lb.height

        // Scroll vertically so the caret line stays visible.
        if (focused) {
            val viewTop = el.box.border.top + el.box.padding.top
            val viewBottom = el.box.height - el.box.border.bottom - el.box.padding.bottom
            if (top - el.scrollTop < viewTop) el.scrollTop = top - viewTop
            if (top + height - el.scrollTop > viewBottom) el.scrollTop = top + height - viewBottom
        }

        val blinkOn = ((now - blinkStart) / 500L) % 2L == 0L
        setStyle(caretEl, "left: ${caretX}px; top: ${top}px; height: ${height}px; visibility: ${if (focused && blinkOn && !hasSelection) "visible" else "hidden"}")
        for (i in lines.indices) {
            val sel = selectionEls.getOrNull(i) ?: continue
            val l = lines[i]
            val b = lineEls.getOrNull(i)?.box
            val from = maxOf(selStart, l.start)
            // A selected hard line break shows as a little extra width.
            val breakSelected = selEnd > l.end && i < lines.lastIndex && lines[i + 1].start > l.end
            val to = minOf(selEnd, l.end)
            if (!focused || !hasSelection || b == null || from > to || (from == to && !breakSelected)) {
                setStyle(sel, "display: none"); continue
            }
            val x0 = b.x + b.contentX + xIn(i, from)
            val x1 = b.x + b.contentX + xIn(i, to) + if (breakSelected) 3f else 0f
            setStyle(sel, "display: block; left: ${x0}px; top: ${b.y}px; width: ${x1 - x0}px; height: ${b.height}px")
        }
    }

    private fun setStyle(e: Element, css: String) {
        if (e.inlineStyle != css) e.inlineStyle = css
    }

    // ---- editing -------------------------------------------------------------------------------------------------

    private fun filter(s: String) = s.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").filter { it >= ' ' || it == '\n' }

    private fun replaceSelection(insert: String) {
        val ins = filter(insert)
        val room = maxLength - (text.length - (selEnd - selStart))
        var clipped = if (ins.length > room) ins.substring(0, room.coerceAtLeast(0)) else ins
        if (clipped.length < ins.length && clipped.isNotEmpty()) {
            val b = breaker(ins)
            if (!b.isBoundary(clipped.length)) clipped = clipped.substring(0, b.preceding(clipped.length))
        }
        commit(text.substring(0, selStart) + clipped + text.substring(selEnd), selStart + clipped.length)
    }

    private fun commit(next: String, newCaret: Int) {
        caret = newCaret; anchor = newCaret
        goalX = null
        touch()
        if (next == text) return
        if (!controlled) {
            text = next
            rewrap()
        }
        EventDispatcher.dispatch(InputEvent(EventType.INPUT, next), el)
        EventDispatcher.dispatch(InputEvent(EventType.CHANGE, next), el)
    }

    private fun touch() {
        blinkStart = el.ownerDocument?.now() ?: 0L
        el.ownerDocument?.invalidateLayout()
    }

    private fun moveCaret(to: Int, extend: Boolean, keepGoal: Boolean = false) {
        caret = to.coerceIn(0, text.length)
        if (!extend) anchor = caret
        if (!keepGoal) goalX = null
        touch()
    }

    private fun charLeft(from: Int) = if (from <= 0) 0 else breaker(text).preceding(from.coerceAtMost(text.length))
    private fun charRight(from: Int) = if (from >= text.length) text.length else breaker(text).following(from.coerceAtLeast(0))

    private fun isWordChar(c: Char) = !c.isWhitespace()

    private fun wordLeft(from: Int): Int {
        var i = from
        while (i > 0 && !isWordChar(text[i - 1])) i--
        while (i > 0 && isWordChar(text[i - 1])) i--
        return i
    }

    private fun wordRight(from: Int): Int {
        var i = from
        while (i < text.length && isWordChar(text[i])) i++
        while (i < text.length && !isWordChar(text[i])) i++
        return i
    }

    private fun verticalMove(dir: Int, extend: Boolean) {
        val line = lineOf(caret)
        val target = line + dir
        val gx = goalX ?: xIn(line, caret)
        when {
            target < 0 -> moveCaret(0, extend)
            target > lines.lastIndex -> moveCaret(text.length, extend)
            else -> {
                moveCaret(indexInLine(target, gx), extend, keepGoal = true)
                goalX = gx
            }
        }
    }

    override fun handle(ev: UIEvent, doc: Document): Boolean {
        if (el.disabled) return false
        when (ev) {
            is MouseEvent -> when (ev.type) {
                EventType.MOUSEDOWN -> if (ev.button == 0) {
                    moveCaret(indexAt(ev.clientX, ev.clientY), ev.shiftKey)
                    dragging = true
                    return true
                }
                EventType.MOUSEMOVE -> if (dragging) {
                    moveCaret(indexAt(ev.clientX, ev.clientY), true)
                    return true
                }
                EventType.MOUSEUP -> dragging = false
                EventType.DBLCLICK -> {
                    var start = caret
                    while (start > 0 && isWordChar(text[start - 1])) start--
                    var end = caret
                    while (end < text.length && isWordChar(text[end])) end++
                    anchor = start; caret = end
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
            "ArrowUp" -> verticalMove(-1, shift)
            "ArrowDown" -> verticalMove(1, shift)
            "Home" -> moveCaret(if (ctrl) 0 else lines[lineOf(caret)].start, shift)
            "End" -> moveCaret(if (ctrl) text.length else lines[lineOf(caret)].end, shift)
            "PageUp" -> repeat(5) { verticalMove(-1, shift) }
            "PageDown" -> repeat(5) { verticalMove(1, shift) }
            "Enter" -> replaceSelection("\n")
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
            else -> {
                if (ctrl) when (ev.key.lowercase()) {
                    "a" -> {
                        anchor = 0; caret = text.length; touch(); return true
                    }
                    "c" -> {
                        if (hasSelection) doc.clipboard.set(text.substring(selStart, selEnd)); return true
                    }
                    "x" -> {
                        if (hasSelection) {
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

    override fun onBlur() {
        dragging = false
        anchor = caret
    }
}

/** Text editing controls (`input`, `textarea`) as seen by [Controls]. */
internal interface EditableControl {
    fun handle(ev: UIEvent, doc: Document): Boolean
    fun afterLayout(now: Long)
    fun onBlur()
}
