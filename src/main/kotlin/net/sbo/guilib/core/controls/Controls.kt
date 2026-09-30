package net.sbo.guilib.core.controls

import net.sbo.guilib.core.dom.Document
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.TextNode
import net.sbo.guilib.core.event.EventType
import net.sbo.guilib.core.event.InteractionController
import java.util.WeakHashMap

/** Wires the built-in form controls into a document. Called by [net.sbo.guilib.core.UiRoot]. */
internal object Controls {

    fun install(doc: Document, input: InteractionController) {
        val inputs = WeakHashMap<Element, InputControl>()
        doc.controlInitializer = { el, created ->
            if (el.tagName == "input") {
                val c = el.control as? InputControl ?: InputControl(el).also { el.control = it }
                inputs[el] = c
                c.sync()
            }
            // Like the HTML attribute: focus once when the element appears.
            if (created && el.getAttribute("autofocus") == true) doc.post { doc.focus(el) }
        }
        input.defaultActions += { ev -> controlFor(ev.target)?.handle(ev, doc) ?: false }
        doc.addEventListener(EventType.BLUR) { ev -> (ev.target.control as? InputControl)?.onBlur() }
        // Position carets/selections after layout (and blink the caret) every frame.
        doc.frameHooks += { now ->
            for ((el, c) in inputs.entries.toList()) if (el.ownerDocument === doc) c.afterLayout(now)
        }
        installTitleTooltips(doc, input)
    }

    /** Native-style tooltips for the `title` prop: shown after hovering an element for [TITLE_DELAY_MS]. */
    private const val TITLE_DELAY_MS = 500L

    private fun installTitleTooltips(doc: Document, input: InteractionController) {
        var titled: Element? = null
        var since = 0L
        var shown = false
        val container = doc.openPortal("guilib-title-portal")
        doc.frameHooks += { now ->
            var e = input.hovered
            while (e != null && e.getAttribute("title") !is String) e = e.parent
            if (e !== titled) {
                titled = e
                since = now
                if (shown) {
                    container.setChildren(emptyList()); shown = false
                }
            } else if (e != null && !shown && now - since >= TITLE_DELAY_MS) {
                val tip = Element("div")
                tip.className = "guilib-tooltip"
                tip.inlineStyle = "position: fixed; left: ${input.mouseX + 8f}px; top: ${input.mouseY + 10f}px"
                tip.setChildren(listOf(TextNode(e.getAttribute("title") as String)))
                container.setChildren(listOf(tip))
                shown = true
            }
        }
    }

    /** The input control of [target] or of the input it belongs to (events may target the generated children). */
    private fun controlFor(target: Element): InputControl? {
        var e: Element? = target
        while (e != null) {
            (e.control as? InputControl)?.let { return it }
            e = e.parent
        }
        return null
    }
}
