package net.sbo.guilib.core.css

/** Minimal [Selectable] for selector/cascade tests. */
class FakeElement(
    override val styleTag: String,
    override val styleId: String? = null,
    classes: String = "",
    vararg states: PseudoState,
) : Selectable {
    override val styleClasses: Set<String> = classes.split(' ').filter { it.isNotBlank() }.toSet()
    val states = states.toMutableSet()
    val children = ArrayList<FakeElement>()
    override var styleParent: FakeElement? = null
        private set

    fun add(child: FakeElement): FakeElement {
        child.styleParent = this
        children += child
        return child
    }

    override val stylePreviousSibling: Selectable?
        get() = styleParent?.children?.let { c -> c.getOrNull(c.indexOf(this) - 1) }
    override val styleNextSibling: Selectable?
        get() = styleParent?.children?.let { c -> c.getOrNull(c.indexOf(this) + 1) }

    override fun hasState(state: PseudoState) = state in states
}
