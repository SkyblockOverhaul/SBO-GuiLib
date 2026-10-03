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
        val inputs = WeakHashMap<Element, EditableControl>()
        doc.controlInitializer = { el, created ->
            if (el.tagName == "input") {
                val c = el.control as? InputControl ?: InputControl(el).also { el.control = it }
                inputs[el] = c
                c.sync()
            } else if (el.tagName == "textarea") {
                val c = el.control as? TextAreaControl ?: TextAreaControl(el).also { el.control = it }
                inputs[el] = c
                c.sync()
            }
            // Like the HTML attribute: focus once when the element appears.
            if (created && el.getAttribute("autofocus") == true) doc.post { doc.focus(el) }
        }
        input.defaultActions += { ev -> controlFor(ev.target)?.handle(ev, doc) ?: false }
        doc.addEventListener(EventType.BLUR) { ev -> (ev.target.control as? EditableControl)?.onBlur() }
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
        var tipAt = 0f to 0f
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
            } else if (e != null && shown) {
                // Laid out now: keep it inside the screen (beside the cursor on whichever side has room).
                val tip = container.children.firstOrNull() as? Element
                if (tip != null) {
                    val pos = pointerPopupPosition(tipAt.first, tipAt.second, tip.box.width, tip.box.height, doc.viewportWidth, doc.viewportHeight)
                    val style = "position: fixed; $pos"
                    if (tip.inlineStyle != style) tip.inlineStyle = style
                }
            } else if (e != null && !shown && now - since >= TITLE_DELAY_MS) {
                val tip = Element("div")
                tip.className = "guilib-tooltip"
                tipAt = input.mouseX to input.mouseY
                // Hidden until the next pass has measured it and moved it into the screen (same frame).
                tip.inlineStyle = "position: fixed; left: 0; top: 0; visibility: hidden"
                tip.setChildren(listOf(TextNode(e.getAttribute("title") as String)))
                container.setChildren(listOf(tip))
                doc.bringPortalToFront(container) // above menus and dropdowns opened after the tooltip layer
                shown = true
            }
        }
    }

    /** The input control of [target] or of the input it belongs to (events may target the generated children). */
    private fun controlFor(target: Element): EditableControl? {
        var e: Element? = target
        while (e != null) {
            (e.control as? EditableControl)?.let { return it }
            e = e.parent
        }
        return null
    }
}
