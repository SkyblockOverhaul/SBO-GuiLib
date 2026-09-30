package net.sbo.guilib.core.dom

import net.sbo.guilib.core.dsl.ComponentScope
import net.sbo.guilib.core.event.UIEvent

/** Virtual node produced by the DSL (the equivalent of a JSX element). Cheap, immutable, recreated on every render. */
sealed class VNode {
    abstract val key: Any?
}

class VText(val text: String) : VNode() {
    override val key: Any? get() = null
}

class ElementProps(
    val id: String? = null,
    val className: String? = null,
    val style: String? = null,
    val attrs: Map<String, Any?> = emptyMap(),
    val handlers: Map<String, (UIEvent) -> Unit> = emptyMap(),
    val ref: Ref<Element?>? = null,
)

class VElement(val tag: String, val props: ElementProps, val children: List<VNode>, override val key: Any?) : VNode()

class VComponent<P>(val type: ComponentType<P>, val props: P, override val key: Any?) : VNode()

class VProvider<T>(val context: Context<T>, val value: T, val children: List<VNode>, override val key: Any?) : VNode()

/** Children rendered into the overlay layer above the whole UI (like `ReactDOM.createPortal(children, document.body)`). */
class VPortal(val children: List<VNode>, val className: String?, override val key: Any?) : VNode()

/**
 * A function component, created with [component]. [render] runs inside a [ComponentScope] where hooks
 * (`useState`, `useEffect`, …) and all tag functions are available.
 */
class ComponentType<P>(val name: String, internal val render: ComponentScope.(P) -> Unit) {
    override fun toString() = "<$name>"
}

/** Declares a function component with props of type [P]. */
fun <P> component(name: String, render: ComponentScope.(props: P) -> Unit): ComponentType<P> = ComponentType(name, render)

/** Declares a function component without props. */
fun component(name: String, render: ComponentScope.() -> Unit): ComponentType<Unit> = ComponentType(name) { render() }

/** Mutable box that survives re-renders without triggering them (like React's `useRef`). Also used for element refs. */
class Ref<T>(var current: T)

/** A value passed down the tree without props (like `React.createContext`). */
class Context<T> internal constructor(val defaultValue: T, val name: String) {
    override fun toString() = "Context($name)"
}

fun <T> createContext(defaultValue: T, name: String = "Context"): Context<T> = Context(defaultValue, name)
