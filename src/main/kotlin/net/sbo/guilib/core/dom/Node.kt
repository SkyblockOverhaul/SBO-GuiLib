package net.sbo.guilib.core.dom

import net.sbo.guilib.core.css.ComputedStyle
import net.sbo.guilib.core.css.CssParser
import net.sbo.guilib.core.css.Declaration
import net.sbo.guilib.core.css.Position
import net.sbo.guilib.core.css.PseudoState
import net.sbo.guilib.core.css.Selectable
import net.sbo.guilib.core.css.Selector
import net.sbo.guilib.core.event.UIEvent
import net.sbo.guilib.core.layout.LayoutBox
import net.sbo.guilib.core.layout.LayoutNode

/** Axis-aligned rectangle in GUI pixels. */
data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right get() = x + width
    val bottom get() = y + height
    fun contains(px: Float, py: Float) = px >= x && py >= y && px < right && py < bottom
    fun intersect(o: Rect): Rect {
        val nx = maxOf(x, o.x)
        val ny = maxOf(y, o.y)
        return Rect(nx, ny, (minOf(right, o.right) - nx).coerceAtLeast(0f), (minOf(bottom, o.bottom) - ny).coerceAtLeast(0f))
    }
}

/** A node of the live UI tree (the "DOM"). Created and updated by the reconciler; read it, don't build it by hand. */
sealed class Node : LayoutNode {
    var parent: Element? = null
        internal set

    internal var document: Document? = null

    /** The document this node is attached to (like DOM `ownerDocument`). */
    val ownerDocument: Document? get() = document

    override val box = LayoutBox()

    /**
     * Border box on screen, including the `transform` of this node and its ancestors (like the web). For a
     * `display: inline` element: the union of its line fragments.
     */
    fun getBoundingClientRect(): Rect {
        if (this is Element && box.inParagraph) inlineBounds()?.let { return it }
        return toClient(Rect(0f, 0f, box.width, box.height), inside = false)
    }

    /** Union of the fragments of this inline element, mapped from its paragraph container to the screen. */
    private fun inlineBounds(): Rect? {
        var container = parent
        while (container != null && container.box.inParagraph) container = container.parent
        container ?: return null
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (p in container.box.paragraphs) for (line in p.lines) for (f in line.fragments) {
            val owner = f.owner as? Node ?: continue
            val mine = when (f) {
                is net.sbo.guilib.core.layout.Fragment.Text -> owner.parent?.let { (this as Element).contains(it) } == true
                else -> (this as Element).contains(owner)
            }
            if (!mine) continue
            x0 = minOf(x0, p.x + f.x); x1 = maxOf(x1, p.x + f.x + f.width)
            y0 = minOf(y0, p.y + line.y); y1 = maxOf(y1, p.y + line.y + line.height)
        }
        if (x0 > x1) return null
        return container.toClient(Rect(x0, y0, x1 - x0, y1 - y0), inside = true)
    }

    /**
     * Maps [local] (relative to this node's border box; with [inside], to its scrolled content) to the screen.
     */
    internal fun toClient(local: Rect, inside: Boolean): Rect {
        // Root first: each element's transform is applied around its own untransformed box.
        val chain = ArrayList<Node>()
        var n: Node? = this
        while (n != null) {
            chain += n; n = n.parent
        }
        var x = 0f
        var y = 0f
        var xf = Transform2D.IDENTITY
        for (i in chain.indices.reversed()) {
            val node = chain[i]
            x += node.box.x
            y += node.box.y
            if (node is Element) {
                if (node.style.position == Position.FIXED) xf = Transform2D.IDENTITY // matches the painter
                xf *= Transform2D.of(node.style, x, y, node.box.width, node.box.height)
            }
            if ((i > 0 || inside) && node is Element) {
                x -= node.scrollLeft
                y -= node.scrollTop
            }
        }
        return xf.map(Rect(x + local.x, y + local.y, local.width, local.height))
    }
}

class TextNode internal constructor(text: String) : Node() {
    var data: String = text
        internal set(value) {
            if (field != value) {
                field = value
                document?.invalidateLayout()
            }
        }

    override val style: ComputedStyle get() = parent?.style ?: ComputedStyle.INITIAL
    override val layoutChildren: List<LayoutNode> get() = emptyList()
    override val textContent: String get() = data

    /** False shows `§` literally instead of applying formatting codes (used by text inputs). */
    override var formattingCodes: Boolean = true
        internal set(value) {
            if (field != value) {
                field = value
                document?.invalidateLayout()
            }
        }

    override fun toString() = "\"$data\""
}

/** Content with a natural size that the backend draws itself (images, items). */
interface ReplacedContent {
    val width: Float
    val height: Float
}

/**
 * An element of the live UI tree. Mirrors the DOM `Element` API where it makes sense:
 * [tagName], [id], [classList], [children], [getBoundingClientRect], [querySelector], [focus], [scrollTop].
 */
class Element internal constructor(val tagName: String) : Node(), Selectable {
    companion object {
        /** How long `:scrolling` stays on after the last scroll position change. */
        const val SCROLLING_MS = 150L
    }

    var id: String? = null
        internal set(value) {
            if (field != value) {
                field = value; styleChanged(true)
            }
        }

    private val classes = LinkedHashSet<String>()

    /**
     * The element's classes, changeable like the DOM's `classList` (`add`, `remove`, `toggle`, `replace`).
     * Meant for elements GuiLib doesn't render from your props, above all [Document.body] (it also holds the portals,
     * so a class there styles modals and tooltips too). On an element you render with `className`, the next render
     * of that element sets its classes back, like React does.
     */
    val classList = ClassList()

    inner class ClassList internal constructor() : AbstractSet<String>() {
        override val size get() = classes.size
        override fun iterator(): Iterator<String> = classes.toList().iterator()
        override fun contains(element: String) = element in classes

        fun add(vararg names: String) = change { names.forEach { classes += it } }
        fun remove(vararg names: String) = change { names.forEach { classes -= it } }

        /** Adds [name] if it is missing (or [force] is true), removes it otherwise; returns whether it is now set. */
        fun toggle(name: String, force: Boolean? = null): Boolean {
            val on = force ?: (name !in classes)
            if (on) add(name) else remove(name)
            return on
        }

        /** Replaces [old] with [new] in place; returns false (and changes nothing) if [old] isn't set. */
        fun replace(old: String, new: String): Boolean {
            if (old !in classes) return false
            val next = classes.map { if (it == old) new else it }
            change { classes.clear(); classes += next }
            return true
        }

        private inline fun change(block: () -> Unit) {
            val before = classes.toList()
            block()
            if (classes.toList() != before) classesChanged()
        }
    }

    var className: String
        get() = classes.joinToString(" ")
        set(value) {
            val next = value.split(' ', '\t', '\n').filter { it.isNotEmpty() }
            if (next.toSet() != classes) {
                classes.clear(); classes += next
                classesChanged()
            }
        }

    /**
     * Inline `style` attribute text. Settable like [classList] (same caveat: rendering the element with `style`
     * sets it back); [setStyleProperty] changes a single property.
     */
    var inlineStyle: String? = null
        set(value) {
            if (field != value) {
                field = value
                inlineDeclarations = if (value.isNullOrBlank()) emptyList() else CssParser.parseDeclarations(value, "style attribute of ${describe()}")
                styleChanged(false)
            }
        }

    internal var inlineDeclarations: List<Declaration> = emptyList()
        private set

    /** Sets one inline property (`"font-family"`, `"--accent"`, …) like `style.setProperty`; `null` removes it. */
    fun setStyleProperty(property: String, value: String?) {
        val name = if (property.startsWith("--")) property else property.lowercase()
        val rest = inlineDeclarations.filter { it.property != name }.map { it.toString() }
        inlineStyle = (if (value == null) rest else rest + "$name: $value").joinToString("; ").ifEmpty { null }
    }

    fun removeStyleProperty(property: String) = setStyleProperty(property, null)

    /** The inline value of [property] as written, or `null` if the inline style doesn't set it. */
    fun getStyleProperty(property: String): String? {
        val name = if (property.startsWith("--")) property else property.lowercase()
        return inlineDeclarations.lastOrNull { it.property == name }?.let { d ->
            d.value.joinToString("").trim() + if (d.important) " !important" else ""
        }
    }

    /** Other attributes (`disabled`, `checked`, `value`, `src`, `title`, `tabIndex`, …). */
    internal val attributes = HashMap<String, Any?>()

    fun getAttribute(name: String): Any? = attributes[name]

    override fun styleAttribute(name: String): String? = when (name) {
        "id" -> id
        "class" -> className
        else -> when (val v = attributes[name]) {
            null, false -> null
            true -> ""
            else -> v.toString()
        }
    }

    internal fun setAttribute(name: String, value: Any?) {
        if (attributes[name] == value) return
        if (value == null) attributes.remove(name) else attributes[name] = value
        when (name) {
            "disabled" -> setState(PseudoState.DISABLED, value == true)
            "checked" -> setState(PseudoState.CHECKED, value == true)
        }
        // `content: attr(…)` of ::before/::after may read it.
        if (document?.styleEngineHasPseudoElements == true) styleChanged(false)
        document?.invalidatePaint()
    }

    val disabled get() = attributes["disabled"] == true

    internal var handlers: Map<String, (UIEvent) -> Unit> = emptyMap()

    fun hasHandler(type: String) = handlers.containsKey(type)

    /** True if the children are owned by a built-in control (like `<input>`) instead of the reconciler. */
    internal var internalChildren = false

    /** State of a built-in control attached to this element (input caret, …). */
    internal var control: Any? = null

    // ---- tree ----

    private val childList = ArrayList<Node>()
    val children: List<Node> get() = childList
    val elementChildren: List<Element> get() = childList.filterIsInstance<Element>()

    private var prevElement: Element? = null
    private var nextElement: Element? = null

    internal fun setChildren(nodes: List<Node>) {
        if (nodes.size == childList.size && nodes.indices.all { nodes[it] === childList[it] }) return
        for (old in childList) if (nodes.none { it === old } && old.parent === this) {
            old.parent = null
            old.detach()
        }
        childList.clear()
        childList += nodes
        renderList = null
        var prev: Element? = null
        for (n in nodes) {
            n.parent = this
            document?.let { n.attach(it) }
            if (n is Element) {
                n.prevElement = prev
                n.nextElement = null
                prev?.nextElement = n
                prev = n
            }
        }
        // Structural pseudo-classes (:first-child, +, ~) may change for every child.
        if (document?.styleEngineUsesStructural != false) for (n in nodes) if (n is Element) n.styleChanged(true)
        document?.invalidateLayout()
    }

    // ---- style ----

    internal var computed: ComputedStyle? = null
    internal var styleDirty = true
    internal var subtreeStyleDirty = true

    /** Style with running transitions/animations applied; `null` when nothing is animating. */
    internal var animatedStyle: ComputedStyle? = null
    internal var animatedOverrides: Map<net.sbo.guilib.core.css.Prop, Any?>? = null

    /** The style used for layout and painting: the computed style plus running transitions/animations. */
    override val style: ComputedStyle get() = animatedStyle ?: computed ?: ComputedStyle.INITIAL

    private var stateBits = 0

    override fun hasState(state: PseudoState) = stateBits and state.bit != 0

    internal fun setState(state: PseudoState, on: Boolean) {
        val next = if (on) stateBits or state.bit else stateBits and state.bit.inv()
        if (next == stateBits) return
        stateBits = next
        styleChanged(document?.styleEngineDependsOnAncestorState ?: true)
        if (document?.styleEngineSiblingsDependOnState == true) siblingsChanged()
    }

    private fun classesChanged() {
        styleChanged(true)
        if (document?.styleEngineSiblingsDependOnClasses == true) siblingsChanged()
    }

    /** Restyles the siblings: `.a + .b` or `:nth-child(odd of .a)` may now match differently. */
    private fun siblingsChanged() {
        for (n in parent?.children ?: return) if (n !== this && n is Element) n.styleChanged(true)
    }

    /** Marks this element (and with [subtree] its descendants) for style recalculation. */
    internal fun styleChanged(subtree: Boolean) {
        styleDirty = true
        if (subtree) subtreeStyleDirty = true
        var p = parent
        while (p != null && !p.childStyleDirty) {
            p.childStyleDirty = true
            p = p.parent
        }
        document?.invalidateStyle()
    }

    /** Some descendant needs a style update. */
    internal var childStyleDirty = true

    override val styleTag get() = tagName
    override val styleId get() = id
    override val styleClasses: Collection<String> get() = classes
    override val styleParent: Selectable? get() = parent
    override val stylePreviousSibling: Selectable? get() = prevElement
    override val styleNextSibling: Selectable? get() = nextElement

    // ---- layout ----

    var replaced: ReplacedContent? = null
        internal set(value) {
            field = value; document?.invalidateLayout()
        }

    /** Generated `::before` / `::after` boxes; not part of [children], but laid out and painted around them. */
    internal var pseudoBefore: Element? = null
        private set
    internal var pseudoAfter: Element? = null
        private set

    /** For a `::before` / `::after` box: the element it belongs to (events and hit-testing go there). */
    internal var pseudoHost: Element? = null

    private var renderList: List<Node>? = null

    /** Replaced elements, controls with their own content (inputs) and `<br>` get no `::before` / `::after`. */
    internal val canHavePseudo get() = replaced == null && !internalChildren && tagName != "br"

    /** [children] plus the `::before` / `::after` boxes, in layout and paint order. */
    internal val renderChildren: List<Node>
        get() = renderList ?: (if (pseudoBefore == null && pseudoAfter == null) childList else buildList {
            pseudoBefore?.let(::add)
            addAll(childList)
            pseudoAfter?.let(::add)
        }).also { renderList = it }

    internal fun setPseudo(after: Boolean, box: Element?) {
        val old = if (after) pseudoAfter else pseudoBefore
        if (old === box) return
        old?.let { it.parent = null; it.detach() }
        if (after) pseudoAfter = box else pseudoBefore = box
        // Attached by hand: attach() would mark styles dirty, but the host computes the box's style itself.
        box?.parent = this
        box?.document = document
        box?.children?.forEach { it.document = document }
        renderList = null
        document?.invalidateLayout()
    }

    /** Text of a `::before` / `::after` box. */
    internal fun setPseudoText(text: String) {
        val current = childList.singleOrNull() as? TextNode
        when {
            text.isEmpty() -> if (childList.isNotEmpty()) setChildren(emptyList())
            current != null -> current.data = text
            else -> setChildren(listOf(TextNode(text)))
        }
    }

    override val layoutChildren: List<LayoutNode> get() = renderChildren
    override val intrinsicWidth: Float? get() = replaced?.width
    override val intrinsicHeight: Float? get() = replaced?.height
    override val isLineBreak: Boolean get() = tagName == "br"

    /** Scroll offset of a scroll container (`overflow: auto/scroll`). Clamped to the scrollable range. */
    var scrollTop: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, maxScrollTop)
            if (v != field) {
                field = v; scrolled()
            }
        }
    var scrollLeft: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, maxScrollLeft)
            if (v != field) {
                field = v; scrolled()
            }
        }

    private var clampingScroll = false
    private var scrollingTimer: Cancelable? = null

    /** Keeps the scroll position inside the content after a relayout; this doesn't count as scrolling. */
    internal fun clampScroll() {
        clampingScroll = true
        try {
            scrollTop = scrollTop
            scrollLeft = scrollLeft
        } finally {
            clampingScroll = false
        }
    }

    private fun scrolled() {
        val doc = document ?: return
        doc.invalidatePaint()
        if (clampingScroll) return
        setState(PseudoState.SCROLLING, true)
        scrollingTimer?.cancel()
        scrollingTimer = doc.setTimeout(SCROLLING_MS) {
            scrollingTimer = null
            setState(PseudoState.SCROLLING, false)
        }
    }
    val maxScrollTop get() = (box.scrollHeight - box.paddingBoxHeight).coerceAtLeast(0f)
    val maxScrollLeft get() = (box.scrollWidth - box.paddingBoxWidth).coerceAtLeast(0f)

    // ---- DOM-style queries ----

    fun contains(other: Node?): Boolean {
        var n = other
        while (n != null) {
            if (n === this) return true
            n = n.parent
        }
        return false
    }

    /** First descendant matching [selector] (CSS syntax), like `element.querySelector`. */
    fun querySelector(selector: String): Element? {
        val sel = Selector.parse(selector)
        return descendants().firstOrNull { el -> sel.any { it.matches(el) } }
    }

    fun querySelectorAll(selector: String): List<Element> {
        val sel = Selector.parse(selector)
        return descendants().filter { el -> sel.any { it.matches(el) } }.toList()
    }

    fun descendants(): Sequence<Element> = sequence {
        for (c in childList) if (c is Element) {
            yield(c); yieldAll(c.descendants())
        }
    }

    /** Gives this element keyboard focus (like `element.focus()`). */
    fun focus() = document?.focus(this)
    fun blur() {
        if (document?.focusedElement === this) document?.focus(null)
    }

    val isFocused get() = document?.focusedElement === this

    fun describe(): String = buildString {
        append(tagName)
        id?.let { append('#').append(it) }
        classes.forEach { append('.').append(it) }
    }

    override fun toString() = describe()
}

internal fun Node.attach(doc: Document) {
    if (document === doc) return
    document = doc
    if (this is Element) {
        styleChanged(true)
        children.forEach { it.attach(doc) }
        // Recreated by the next style pass.
        setPseudo(false, null)
        setPseudo(true, null)
    }
}

internal fun Node.detach() {
    val doc = document ?: return
    doc.onDetach(this)
    document = null
    if (this is Element) {
        children.forEach { it.detach() }
        pseudoBefore?.detach()
        pseudoAfter?.detach()
    }
}
