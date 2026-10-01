package net.sbo.guilib.core.dom

import net.sbo.guilib.core.css.ComputedStyle
import net.sbo.guilib.core.css.CssParser
import net.sbo.guilib.core.css.Declaration
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

    /** Absolute border box on screen, taking ancestors' scroll offsets into account. */
    fun getBoundingClientRect(): Rect {
        var x = box.x
        var y = box.y
        var p = parent
        while (p != null) {
            x += p.box.x - p.scrollLeft
            y += p.box.y - p.scrollTop
            p = p.parent
        }
        return Rect(x, y, box.width, box.height)
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
    var id: String? = null
        internal set(value) {
            if (field != value) {
                field = value; styleChanged(true)
            }
        }

    private val classes = LinkedHashSet<String>()
    val classList: Set<String> get() = classes

    var className: String
        get() = classes.joinToString(" ")
        internal set(value) {
            val next = value.split(' ', '\t', '\n').filter { it.isNotEmpty() }
            if (next.toSet() != classes) {
                classes.clear(); classes += next
                styleChanged(true)
            }
        }

    /** Inline `style` attribute text. */
    var inlineStyle: String? = null
        internal set(value) {
            if (field != value) {
                field = value
                inlineDeclarations = if (value.isNullOrBlank()) emptyList() else CssParser.parseDeclarations(value, "style attribute of ${describe()}")
                styleChanged(false)
            }
        }

    internal var inlineDeclarations: List<Declaration> = emptyList()
        private set

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

    override val layoutChildren: List<LayoutNode> get() = childList
    override val intrinsicWidth: Float? get() = replaced?.width
    override val intrinsicHeight: Float? get() = replaced?.height
    override val isLineBreak: Boolean get() = tagName == "br"

    /** Scroll offset of a scroll container (`overflow: auto/scroll`). Clamped to the scrollable range. */
    var scrollTop: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, maxScrollTop)
            if (v != field) {
                field = v; document?.invalidatePaint()
            }
        }
    var scrollLeft: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, maxScrollLeft)
            if (v != field) {
                field = v; document?.invalidatePaint()
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
    }
}

internal fun Node.detach() {
    val doc = document ?: return
    doc.onDetach(this)
    document = null
    if (this is Element) children.forEach { it.detach() }
}
