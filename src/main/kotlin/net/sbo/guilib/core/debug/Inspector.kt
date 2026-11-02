package net.sbo.guilib.core.debug

import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.dom.Document
import net.sbo.guilib.core.dom.Element

/**
 * What the CSS inspector (Ctrl + Shift + C in any GuiLib screen) shows about an element, so players and mod authors
 * can find the selectors to style: its own selector, the path from the body and the rules that match it.
 */
object Inspector {
    /** `tag#id.class.class`, e.g. `button.pf-btn.primary`. */
    fun selectorOf(el: Element): String = buildString {
        append(el.tagName)
        el.id?.takeIf { it.isNotBlank() }?.let { append('#').append(it) }
        el.styleClasses.forEach { append('.').append(it) }
    }

    /** The elements from the body down to [el], `body.x > div.y > button.z`. */
    fun pathOf(el: Element): String =
        generateSequence(el) { it.parent }.toList().asReversed().joinToString(" > ") { selectorOf(it) }

    /**
     * The rules whose selector matches [el] as `source:line  selector`, in cascade order (the last one wins at the
     * same specificity). The user-agent stylesheet is left out unless [userAgent]; `::before` / `::after` rules too.
     */
    fun matchingRules(document: Document, el: Element, userAgent: Boolean = false): List<String> =
        document.styleEngine.stylesheets
            .filter { userAgent || it.origin != Origin.USER_AGENT }
            .flatMap { sheet ->
                sheet.rules.flatMap { rule ->
                    rule.selectors.filter { it.pseudoElement == null && it.matches(el) }.map { "${sheet.source}:${rule.line}  $it" }
                }
            }
}
