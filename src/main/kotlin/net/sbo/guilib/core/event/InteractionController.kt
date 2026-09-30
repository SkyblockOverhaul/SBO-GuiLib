package net.sbo.guilib.core.event

import net.sbo.guilib.core.css.Cursor
import net.sbo.guilib.core.css.PseudoState
import net.sbo.guilib.core.dom.Document
import net.sbo.guilib.core.dom.Element

/**
 * Turns raw input (mouse position, buttons, wheel, keys) into DOM-style events and interaction state
 * (`:hover`, `:active`, `:focus`), and performs default actions (focus on click, Tab navigation,
 * Enter/Space activating buttons, wheel scrolling, label → input forwarding).
 */
class InteractionController(private val doc: Document, private val hitTest: (Float, Float) -> Element?) {
    var mouseX = -1f
        private set
    var mouseY = -1f
        private set

    private var hoverChain: List<Element> = emptyList()
    private var activeChain: List<Element> = emptyList()
    private var pressTarget: Element? = null
    private var pressButton = -1
    private var lastClickTarget: Element? = null
    private var lastClickTime = 0L
    private var clickCount = 0

    /** Extra default actions registered by built-in components (inputs, selects, …); return true if handled. */
    val defaultActions = ArrayList<(UIEvent) -> Boolean>()

    init {
        doc.detachListeners += { node ->
            if (node is Element) {
                if (hoverChain.any { it === node }) hoverChain = hoverChain.filter { it.document === doc && it !== node }
                if (activeChain.any { it === node }) activeChain = activeChain.filter { it !== node }
                if (pressTarget === node) pressTarget = null
            }
        }
    }

    /** The element under the mouse, if any. */
    val hovered: Element? get() = hoverChain.lastOrNull()

    /** Cursor requested by the hovered element's `cursor` property. */
    val cursor: Cursor
        get() {
            val el = hovered ?: return Cursor.DEFAULT
            if (disabledAncestor(el) != null && el.style.cursor == Cursor.POINTER) return Cursor.NOT_ALLOWED
            return el.style.cursor
        }

    private fun chainOf(el: Element?): List<Element> {
        val out = ArrayList<Element>()
        var e = el
        while (e != null) {
            out += e; e = e.parent
        }
        out.reverse()
        return out
    }

    private fun disabledAncestor(el: Element): Element? {
        var e: Element? = el
        while (e != null) {
            if (e.disabled) return e
            e = e.parent
        }
        return null
    }

    private fun updateHover(x: Float, y: Float, modifiers: Modifiers) {
        val target = hitTest(x, y)
        val next = chainOf(target)
        if (next.size == hoverChain.size && next.indices.all { next[it] === hoverChain[it] }) return
        val old = hoverChain
        hoverChain = next
        // mouseleave from the deepest left element up, mouseenter from the outermost new element down (like the DOM).
        for (el in old.asReversed()) if (next.none { it === el }) {
            el.setState(PseudoState.HOVER, false)
            EventDispatcher.dispatch(MouseEvent(EventType.MOUSELEAVE, x, y, modifiers = modifiers, bubbles = false), el)
        }
        for (el in next) if (old.none { it === el }) {
            el.setState(PseudoState.HOVER, true)
            EventDispatcher.dispatch(MouseEvent(EventType.MOUSEENTER, x, y, modifiers = modifiers, bubbles = false), el)
        }
    }

    /** Re-evaluates hover after the page changed under a still mouse (scrolling, re-layout). */
    fun refreshHover() {
        if (mouseX >= 0f) updateHover(mouseX, mouseY, Modifiers.NONE)
        doc.flush()
    }

    fun mouseMove(x: Float, y: Float, modifiers: Modifiers = Modifiers.NONE) {
        mouseX = x; mouseY = y
        updateHover(x, y, modifiers)
        val target = hovered
        if (target != null && disabledAncestor(target) == null) EventDispatcher.dispatch(MouseEvent(EventType.MOUSEMOVE, x, y, modifiers = modifiers), target)
        // Dragging (e.g. selecting text) keeps going to the pressed element even outside of it.
        pressTarget?.let { pressed ->
            val drag = MouseEvent(EventType.MOUSEMOVE, x, y, pressButton, modifiers)
            drag.target = pressed
            runDefaultActions(drag)
        }
        doc.flush()
    }

    /** Returns true if the press hit an element (the backend should then not pass it on). */
    fun mouseDown(x: Float, y: Float, button: Int, modifiers: Modifiers = Modifiers.NONE): Boolean {
        mouseMove(x, y, modifiers)
        val target = hovered ?: return false
        pressTarget = target
        pressButton = button
        if (disabledAncestor(target) != null) return true
        val down = MouseEvent(EventType.MOUSEDOWN, x, y, button, modifiers)
        val allowed = EventDispatcher.dispatch(down, target)
        if (button == 0) {
            activeChain = chainOf(target)
            activeChain.forEach { it.setState(PseudoState.ACTIVE, true) }
        }
        if (allowed && button == 0) {
            doc.focus(focusableAncestor(target))
            runDefaultActions(down)
        }
        doc.flush()
        return true
    }

    fun mouseUp(x: Float, y: Float, button: Int, modifiers: Modifiers = Modifiers.NONE): Boolean {
        mouseMove(x, y, modifiers)
        activeChain.forEach { it.setState(PseudoState.ACTIVE, false) }
        activeChain = emptyList()
        val target = hovered
        val pressed = pressTarget
        pressTarget = null
        if (target == null) return false
        if (disabledAncestor(target) != null) return true
        EventDispatcher.dispatch(MouseEvent(EventType.MOUSEUP, x, y, button, modifiers), target)
        if (pressed != null) {
            val up = MouseEvent(EventType.MOUSEUP, x, y, button, modifiers)
            up.target = pressed
            runDefaultActions(up)
        }
        if (pressed != null && button == pressButton) {
            // click goes to the nearest common ancestor of press and release targets.
            val common = chainOf(pressed).zip(chainOf(target)).takeWhile { (a, b) -> a === b }.lastOrNull()?.first
            if (common != null && disabledAncestor(common) == null) {
                if (button == 0) click(common, x, y, modifiers)
                else if (button == 2) EventDispatcher.dispatch(MouseEvent(EventType.CONTEXTMENU, x, y, button, modifiers), common)
            }
        }
        doc.flush()
        return true
    }

    private fun click(target: Element, x: Float, y: Float, modifiers: Modifiers) {
        val now = System.currentTimeMillis()
        clickCount = if (lastClickTarget === target && now - lastClickTime < 400) clickCount + 1 else 1
        lastClickTarget = target
        lastClickTime = now
        val ev = MouseEvent(EventType.CLICK, x, y, 0, modifiers)
        val allowed = EventDispatcher.dispatch(ev, target)
        if (allowed) runDefaultActions(ev)
        if (clickCount == 2) {
            val dbl = MouseEvent(EventType.DBLCLICK, x, y, 0, modifiers)
            if (EventDispatcher.dispatch(dbl, target)) runDefaultActions(dbl)
        }
    }

    private fun runDefaultActions(ev: UIEvent) {
        for (a in defaultActions) if (a(ev)) return
        if (ev.type == EventType.CLICK && ev.target.tagName != "input") {
            // Clicking a <label> activates the first form control inside it.
            var e: Element? = ev.target
            while (e != null && e.tagName != "label") e = e.parent
            val label = e ?: return
            if (ev.target.tagName == "input" || ev.target.tagName == "select") return
            val control = label.descendants().firstOrNull { it.tagName == "input" || it.tagName == "select" || it.tagName == "button" } ?: return
            doc.focus(control)
            val synthetic = MouseEvent(EventType.CLICK, mouseX, mouseY)
            if (EventDispatcher.dispatch(synthetic, control)) for (a in defaultActions) if (a(synthetic)) return
        }
    }

    fun wheel(x: Float, y: Float, deltaX: Float, deltaY: Float, modifiers: Modifiers = Modifiers.NONE): Boolean {
        mouseMove(x, y, modifiers)
        val target = hovered ?: return false
        val (dx, dy) = if (modifiers.shift && deltaX == 0f) deltaY to 0f else deltaX to deltaY
        val allowed = EventDispatcher.dispatch(WheelEvent(x, y, dx, dy, modifiers), target)
        if (allowed) {
            var e: Element? = target
            while (e != null) {
                if (scrollBy(e, dx, dy)) break
                e = e.parent
            }
        }
        doc.flush()
        return true
    }

    private fun scrollBy(el: Element, dx: Float, dy: Float): Boolean {
        val s = el.style
        var moved = false
        if (dy != 0f && s.overflowY.scrolls && el.maxScrollTop > 0f) {
            val before = el.scrollTop
            el.scrollTop = before + dy
            moved = el.scrollTop != before
        }
        if (dx != 0f && s.overflowX.scrolls && el.maxScrollLeft > 0f) {
            val before = el.scrollLeft
            el.scrollLeft = before + dx
            moved = moved || el.scrollLeft != before
        }
        if (moved) EventDispatcher.dispatch(ScrollEvent(el.scrollLeft, el.scrollTop), el)
        return moved
    }

    /** Returns true if the key was handled (the backend must then not run its own action, e.g. closing on Escape). */
    fun keyDown(key: String, keyCode: Int, modifiers: Modifiers = Modifiers.NONE, repeat: Boolean = false): Boolean {
        val target = doc.focusedElement ?: doc.body
        val ev = KeyboardEvent(EventType.KEYDOWN, key, keyCode, modifiers, repeat)
        val allowed = EventDispatcher.dispatch(ev, target)
        var handled = !allowed
        if (allowed) {
            if (defaultActions.any { it(ev) }) handled = true
            else when (key) {
                "Tab" -> {
                    moveFocus(if (modifiers.shift) -1 else 1); handled = true
                }
                "Enter", " " -> {
                    val f = doc.focusedElement
                    if (f != null && !f.disabled && f.tagName != "input" && (f.tagName == "button" || f.getAttribute("tabindex") != null)) {
                        click(f, mouseX, mouseY, modifiers); handled = true
                    }
                }
                "Escape" -> {
                    if (doc.focusedElement != null) {
                        // First Escape only blurs an input; the second one closes the screen.
                        if (doc.focusedElement?.tagName == "input") {
                            doc.focus(null); handled = true
                        }
                    }
                }
            }
        }
        doc.flush()
        return handled
    }

    fun keyUp(key: String, keyCode: Int, modifiers: Modifiers = Modifiers.NONE): Boolean {
        val target = doc.focusedElement ?: doc.body
        val allowed = EventDispatcher.dispatch(KeyboardEvent(EventType.KEYUP, key, keyCode, modifiers), target)
        doc.flush()
        return !allowed
    }

    fun charTyped(char: String, modifiers: Modifiers = Modifiers.NONE): Boolean {
        val target = doc.focusedElement ?: return false
        val ev = CharEvent(char, modifiers)
        val allowed = EventDispatcher.dispatch(ev, target)
        val handled = !allowed || (allowed && defaultActions.any { it(ev) })
        doc.flush()
        return handled
    }

    private fun isFocusable(el: Element): Boolean {
        if (el.disabled) return false
        val tab = el.getAttribute("tabindex") as? Int
        if (tab != null) return tab >= 0
        return el.tagName == "input" || el.tagName == "button" || el.tagName == "select"
    }

    private fun focusableAncestor(el: Element): Element? {
        var e: Element? = el
        while (e != null) {
            val tab = e.getAttribute("tabindex") as? Int
            if (!e.disabled && (tab != null || e.tagName == "input" || e.tagName == "button" || e.tagName == "select")) return e
            e = e.parent
        }
        return null
    }

    private fun moveFocus(dir: Int) {
        val all = doc.body.descendants().filter { isFocusable(it) && it.box.visible && it.style.visibility == net.sbo.guilib.core.css.Visibility.VISIBLE }.toList()
        if (all.isEmpty()) return
        val idx = all.indexOfFirst { it === doc.focusedElement }
        val next = if (idx < 0) (if (dir > 0) 0 else all.size - 1) else Math.floorMod(idx + dir, all.size)
        doc.focus(all[next])
    }
}
