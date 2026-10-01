package net.sbo.guilib.core.controls

import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.NodeBuilder
import net.sbo.guilib.core.dsl.classNames
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.event.EventType
import net.sbo.guilib.core.event.UIEvent

internal data class CollapseProps(
    val open: Boolean,
    val durationMs: Long,
    val keepMounted: Boolean,
    val className: String?,
    val children: NodeBuilder.() -> Unit,
)

/**
 * Animates its height between 0 and the height of its content. The height follows the content while open, so content
 * changes animate too. Closed content is unmounted after the animation unless [CollapseProps.keepMounted].
 * Styled with `.guilib-collapse` (`.open`) and `.guilib-collapse-inner`.
 */
internal val CollapseComponent = component<CollapseProps>("Collapse") { p ->
    val outerRef = useElementRef()
    val innerRef = useElementRef()
    var mounted by useState(p.open)
    val doc = useDocument()

    useEffect(p.open) {
        if (p.open) mounted = true
        else if (!p.keepMounted) {
            val t = doc.setTimeout(p.durationMs) { mounted = false }
            onCleanup { t.cancel() }
        }
    }

    val style = useLayoutStyle(outerRef) {
        val h = if (p.open) innerRef.current?.box?.height ?: 0f else 0f
        "height: ${px(h)}; transition: height ${p.durationMs}ms ease"
    }
    div(className = classNames("guilib-collapse", "open" to p.open, p.className), ref = outerRef, style = style) {
        div(className = "guilib-collapse-inner", ref = innerRef) {
            if (p.open || mounted || p.keepMounted) p.children(this)
        }
    }
}

internal data class DetailsProps(
    val summary: NodeBuilder.() -> Unit,
    val open: Boolean?,
    val defaultOpen: Boolean,
    val onToggle: ((Boolean) -> Unit)?,
    val disabled: Boolean,
    val durationMs: Long,
    val className: String?,
    val children: NodeBuilder.() -> Unit,
)

/**
 * Expandable section like HTML `<details>`: a clickable summary row with a chevron and an animated body.
 * Controlled with [DetailsProps.open] + `onToggle`, or uncontrolled (starts as [DetailsProps.defaultOpen]).
 * Styled with `.guilib-details` (`.open`), `.guilib-details-summary`, `.guilib-details-chevron`, `.guilib-details-content`.
 */
internal val DetailsComponent = component<DetailsProps>("Details") { p ->
    var own by useState(p.defaultOpen)
    val open = p.open ?: own

    val attrs = HashMap<String, Any?>()
    attrs["tabindex"] = 0
    if (p.disabled) attrs["disabled"] = true
    val handlers = HashMap<String, (UIEvent) -> Unit>()
    handlers[EventType.CLICK] = {
        if (!p.disabled) {
            if (p.open == null) own = !open
            p.onToggle?.invoke(!open)
        }
    }
    div(className = classNames("guilib-details", "open" to open, "disabled" to p.disabled, p.className)) {
        element("div", null, null, "guilib-details-summary", null, null, attrs, handlers) {
            span(className = "guilib-details-chevron")
            div(className = "guilib-details-title") { p.summary(this) }
        }
        CollapseComponent(CollapseProps(open, p.durationMs, false, null) {
            div(className = "guilib-details-content") { p.children(this) }
        })
    }
}
