package net.sbo.guilib.fabric.debug

import net.sbo.guilib.core.dom.Rect
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.div

/**
 * The CSS inspector over a GuiLib screen (Ctrl + Shift + C): a frame around the element under the mouse and a box with
 * its selector, its path from the body and the rules that match it. Drawn in its own document like the metrics
 * window, the screen fills in [Props] every frame.
 */
internal object InspectorOverlay {
    /** How many matching rules the box lists, the last ones (they win). */
    private const val MAX_RULES = 8

    class Props(
        /** The element's box in GUI px, null when nothing is under the mouse. */
        val box: Rect?,
        val selector: String?,
        val path: String?,
        val rules: List<String>,
        /** Shown instead of the help line after a click copied something. */
        val copied: String?,
        /** The box sits at the top while the mouse is in the lower half. */
        val top: Boolean,
    )

    val Window = component<Props>("Inspector") { p ->
        p.box?.let { b ->
            div(className = "guilib-inspector-outline", style = "left: ${b.x}px; top: ${b.y}px; width: ${b.width}px; height: ${b.height}px") {}
        }
        div(className = if (p.top) "guilib-inspector top" else "guilib-inspector") {
            div(className = "guilib-inspector-title") { +"CSS Inspector" }
            if (p.selector == null) {
                div(className = "guilib-inspector-hint") { +"Move the mouse over something." }
            } else {
                div(className = "guilib-inspector-selector") { +p.selector }
                p.path?.let { div(className = "guilib-inspector-path") { +it } }
                if (p.rules.isNotEmpty()) {
                    div(className = "guilib-inspector-section") { +"Matching rules (the last one wins)" }
                    if (p.rules.size > MAX_RULES) div(className = "guilib-inspector-hint") { +"… ${p.rules.size - MAX_RULES} more" }
                    p.rules.takeLast(MAX_RULES).forEach { div(className = "guilib-inspector-rule") { +it } }
                }
            }
            div(className = "guilib-inspector-hint") {
                +(p.copied ?: "Left click: copy selector · Right click: copy path · Ctrl + Shift + C or Esc: close")
            }
        }
    }
}
