package net.sbo.guilib.core.dsl

import net.sbo.guilib.core.Log
import net.sbo.guilib.core.dom.ComponentInstance
import net.sbo.guilib.core.dom.ComponentType
import net.sbo.guilib.core.dom.Context
import net.sbo.guilib.core.dom.Document
import net.sbo.guilib.core.dom.EffectHook
import net.sbo.guilib.core.dom.EffectScope
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.ElementProps
import net.sbo.guilib.core.dom.ProviderInstance
import net.sbo.guilib.core.dom.Reconciler
import net.sbo.guilib.core.dom.Ref
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.VElement
import net.sbo.guilib.core.dom.VNode
import net.sbo.guilib.core.dom.VPortal
import net.sbo.guilib.core.dom.VProvider
import net.sbo.guilib.core.dom.VText
import net.sbo.guilib.core.event.UIEvent
import kotlin.reflect.KProperty

@DslMarker
annotation class GuiDsl

/**
 * Collects child nodes. All tag functions (`div`, `span`, `button`, …, see Tags.kt) are members of this class,
 * so they're available in every children block.
 */
@GuiDsl
open class NodeBuilder {
    @PublishedApi
    internal val nodes = ArrayList<VNode>()

    /** Adds a text node: `+"Hello"`. May contain Minecraft `§` color codes. */
    operator fun String.unaryPlus() {
        nodes += VText(this)
    }

    /** Adds a text node (same as `+value`). */
    fun text(value: Any?) {
        nodes += VText(value.toString())
    }

    /** Renders a component: `PartyCard(props, key = party.id)`. */
    operator fun <P> ComponentType<P>.invoke(props: P, key: Any? = null) {
        nodes += VComponent(this, props, key)
    }

    /** Renders a component without props: `Header()`. */
    operator fun ComponentType<Unit>.invoke(key: Any? = null) {
        nodes += VComponent(this, Unit, key)
    }

    /** Provides [value] to all `useContext(this)` calls below: `ThemeContext.Provider(dark) { … }`. */
    fun <T> Context<T>.Provider(value: T, key: Any? = null, children: NodeBuilder.() -> Unit) {
        nodes += VProvider(this, value, NodeBuilder().apply(children).nodes, key)
    }

    /**
     * Renders [children] into the overlay layer above the whole UI, like React's `createPortal(children, document.body)`.
     * Use it for menus, tooltips and dialogs so they aren't clipped by scroll containers or covered by siblings.
     * Position the content with `position: fixed`.
     */
    fun portal(className: String? = null, key: Any? = null, children: NodeBuilder.() -> Unit) {
        nodes += VPortal(NodeBuilder().apply(children).nodes, className, key)
    }

    /** Groups children without a wrapper element (like `<>…</>`); useful to give a list entry a key. */
    fun fragment(key: Any? = null, children: NodeBuilder.() -> Unit) {
        nodes += VComponent(FRAGMENT, NodeBuilder().apply(children).nodes, key)
    }

    /** Low-level element factory used by all tag functions. */
    fun element(
        tag: String,
        key: Any?,
        id: String?,
        className: String?,
        style: String?,
        ref: Ref<Element?>?,
        attrs: Map<String, Any?>,
        handlers: Map<String, (UIEvent) -> Unit>,
        children: (NodeBuilder.() -> Unit)?,
    ) {
        val kids = if (children == null) emptyList() else NodeBuilder().apply(children).nodes
        nodes += VElement(tag, ElementProps(id, className, style, attrs, handlers, ref), kids, key)
    }

    companion object {
        private val FRAGMENT = ComponentType<List<VNode>>("Fragment") { children -> nodes += children }
    }
}

/** Joins class names, skipping falsy ones: `classNames("btn", "active" to isActive)` (like the `clsx` package). */
fun classNames(vararg parts: Any?): String = buildString {
    for (p in parts) {
        val name = when (p) {
            null, false -> null
            is String -> p.takeIf { it.isNotBlank() }
            is Pair<*, *> -> if (p.second == true) p.first?.toString() else null
            else -> p.toString()
        } ?: continue
        if (isNotEmpty()) append(' ')
        append(name)
    }
}

/**
 * Receiver of a component's render function. Provides React-style hooks. Like in React, hooks must be called
 * unconditionally and in the same order on every render.
 */
class ComponentScope internal constructor(
    private val instance: ComponentInstance,
    private val reconciler: Reconciler,
    private val document: Document,
) : NodeBuilder() {
    private var hookIndex = 0

    @Suppress("UNCHECKED_CAST")
    private inline fun <T> hook(kind: String, create: () -> T): T {
        val i = hookIndex++
        if (i < instance.hooks.size) {
            val h = instance.hooks[i]
            val ok = when (kind) {
                "state" -> h is State<*>
                "effect" -> h is EffectHook
                "memo" -> h is MemoHook
                "ref" -> h is Ref<*>
                "context" -> h is ContextHook
                else -> true
            }
            if (!ok) {
                throw IllegalStateException("hook order changed in <${instance.name}> (hook #$i is now $kind). Hooks must not be called conditionally.")
            }
            return h as T
        }
        if (instance.renderCount > 1) Log.warnOnce("GuiLib: <${instance.name}> called more hooks than on its first render; hooks must not be called conditionally")
        return create().also { instance.hooks += it }
    }

    internal fun finish() {
        if (hookIndex < instance.hooks.size && instance.renderCount > 1) {
            Log.warnOnce("GuiLib: <${instance.name}> called fewer hooks than on its first render; hooks must not be called conditionally")
        }
    }

    /**
     * Local state. Use as a delegate (`var count by useState(0)`) or destructure like React
     * (`val (count, setCount) = useState(0)`). Setting a different value re-renders the component.
     */
    fun <T> useState(initial: T): State<T> = hook("state") { State(instance, initial) }

    /** State with a lazily computed initial value. */
    fun <T> useStateLazy(initial: () -> T): State<T> = hook("state") { State(instance, initial()) }

    /**
     * Side effect run after the DOM was updated. With no [deps] it runs once after mount (like `useEffect(fn, [])`);
     * otherwise whenever one of the [deps] changes. Register cleanup with `onCleanup { }`.
     */
    fun useEffect(vararg deps: Any?, effect: EffectScope.() -> Unit) {
        val d = deps.toList()
        val h = hook("effect") { EffectHook(null, null) }
        val first = h.effect == null
        h.effect = effect
        if (first || h.deps != d) {
            h.deps = d
            h.pending = true
            reconciler.pendingEffects += h
        }
    }

    /** Memoized value, recomputed only when [deps] change. */
    fun <T> useMemo(vararg deps: Any?, compute: () -> T): T {
        val d = deps.toList()
        val h = hook("memo") { MemoHook(d, compute()) }
        if (h.deps != d) {
            h.deps = d; h.value = compute()
        }
        @Suppress("UNCHECKED_CAST")
        return h.value as T
    }

    /** Mutable box kept across renders; changing it doesn't re-render. Pass to `ref =` to get the [Element]. */
    fun <T> useRef(initial: T): Ref<T> = hook("ref") { Ref(initial) }

    /** Ref for an element: `val input = useElementRef(); input(ref = input)`, later `input.current?.focus()`. */
    fun useElementRef(): Ref<Element?> = useRef(null)

    /** Value of the nearest `context.Provider` above, or the context's default. */
    fun <T> useContext(context: Context<T>): T {
        hook("context") { ContextHook }
        var p = instance.parent
        while (p != null) {
            if (p is ProviderInstance && p.vnode.context === context) {
                p.consumers += instance
                instance.subscriptions += p
                @Suppress("UNCHECKED_CAST")
                return p.vnode.value as T
            }
            p = p.parent
        }
        return context.defaultValue
    }

    /**
     * Calls [callback] every [ms] milliseconds while mounted (the classic React `useInterval`).
     * The latest [callback] is always used, so it sees current state.
     */
    fun useInterval(ms: Long, callback: () -> Unit) {
        val latest = useRef(callback)
        latest.current = callback
        useEffect(ms) { setInterval(ms) { latest.current() } }
    }

    /** The [Document] this component lives in (viewport size, focus, `addEventListener`, …). */
    fun useDocument(): Document = document

    /**
     * Listens to [type] on the whole document while mounted (like `document.addEventListener` in a React effect).
     * Runs before element handlers; call `stopPropagation()`/`preventDefault()` to swallow the event.
     */
    fun useDocumentEvent(type: String, listener: (UIEvent) -> Unit) {
        val latest = useRef(listener)
        latest.current = listener
        useEffect(type) {
            val remove = document.addEventListener(type) { latest.current(it) }
            onCleanup(remove)
        }
    }

    /** Forces a re-render (escape hatch, rarely needed). */
    fun useForceUpdate(): () -> Unit {
        val s = useState(0)
        return { s.value = s.value + 1 }
    }

    private class MemoHook(var deps: List<Any?>, var value: Any?)
    private object ContextHook
}

/** State cell returned by [ComponentScope.useState]. */
class State<T> internal constructor(private val instance: ComponentInstance, initial: T) {
    private var current: T = initial

    /** Current value; assigning a different value schedules a re-render (thread-safe). */
    var value: T
        get() = current
        set(v) = set(v)

    fun set(v: T) {
        val doc = instance.document
        if (!doc.isUiThread()) {
            doc.post { set(v) }
            return
        }
        if (current == v) return
        current = v
        if (instance.mounted) doc.scheduleRender(instance)
    }

    /** Functional update: `count.update { it + 1 }`. */
    fun update(fn: (T) -> T) {
        val doc = instance.document
        if (!doc.isUiThread()) {
            doc.post { update(fn) }
            return
        }
        set(fn(current))
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = current
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = set(value)
    operator fun component1(): T = current
    operator fun component2(): (T) -> Unit = ::set

    override fun toString() = "State($current)"
}
