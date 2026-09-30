package net.sbo.guilib.core.dom

import net.sbo.guilib.core.Log
import net.sbo.guilib.core.dsl.ComponentScope

/** Mounted counterpart of a [VNode]. Components and providers own children but no DOM node of their own. */
internal sealed class Instance {
    var parent: Instance? = null
    var matchKey: Any? = null
    var mounted = true
    val depth: Int get() = (parent?.depth ?: -1) + 1

    /** Appends the DOM nodes this instance contributes to its host parent. */
    abstract fun collectDom(out: MutableList<Node>)
}

internal class HostInstance(val element: Element, var vnode: VElement?) : Instance() {
    var children: List<Instance> = emptyList()
    override fun collectDom(out: MutableList<Node>) {
        out += element
    }
}

internal class TextInstance(val node: TextNode) : Instance() {
    override fun collectDom(out: MutableList<Node>) {
        out += node
    }
}

internal class ComponentInstance(var vnode: VComponent<*>, val document: Document) : Instance() {
    var children: List<Instance> = emptyList()
    val hooks = ArrayList<Any?>()
    var dirty = true
    var renderCount = 0
    /** Providers this component read with `useContext`, so it re-renders when their value changes. */
    val subscriptions = HashSet<ProviderInstance>()

    override fun collectDom(out: MutableList<Node>) = children.forEach { it.collectDom(out) }

    val name get() = vnode.type.name
}

/** Renders its children into a container inside the document's overlay layer instead of in place. */
internal class PortalInstance(val container: Element) : Instance() {
    var children: List<Instance> = emptyList()
    override fun collectDom(out: MutableList<Node>) {} // contributes nothing to its parent
}

internal class ProviderInstance(var vnode: VProvider<*>) : Instance() {
    var children: List<Instance> = emptyList()
    val consumers = HashSet<ComponentInstance>()
    override fun collectDom(out: MutableList<Node>) = children.forEach { it.collectDom(out) }
}

/** State of one `useEffect` call. */
internal class EffectHook(var deps: List<Any?>?, var effect: (EffectScope.() -> Unit)?) {
    var scope: EffectScope? = null
    var pending = true
}

/** Receiver of `useEffect` bodies: register cleanups and timers that are cancelled automatically. */
class EffectScope internal constructor(private val document: Document) {
    internal val cleanups = ArrayList<() -> Unit>()

    /** Runs [cleanup] before the effect re-runs or when the component unmounts (the React "return () => …"). */
    fun onCleanup(cleanup: () -> Unit) {
        cleanups += cleanup
    }

    /** Like JS `setInterval`, but cancelled automatically on cleanup. */
    fun setInterval(ms: Long, callback: () -> Unit): Cancelable = document.setInterval(ms, callback).also { t -> cleanups += { t.cancel() } }

    /** Like JS `setTimeout`, but cancelled automatically on cleanup. */
    fun setTimeout(ms: Long, callback: () -> Unit): Cancelable = document.setTimeout(ms, callback).also { t -> cleanups += { t.cancel() } }

    internal fun runCleanups() {
        val list = cleanups.toList()
        cleanups.clear()
        for (c in list.asReversed()) try {
            c()
        } catch (e: Throwable) {
            Log.error("GuiLib: effect cleanup threw: $e")
        }
    }
}

fun interface Cancelable {
    fun cancel()
}

/** Diffs VNodes against mounted instances and patches the live [Element] tree. */
internal class Reconciler(private val document: Document) {

    /** Effects scheduled during this flush, run after the DOM is updated. */
    val pendingEffects = ArrayList<EffectHook>()

    // ---- mount / update / unmount --------------------------------------------------------------------------------

    fun mount(v: VNode, parent: Instance?): Instance = when (v) {
        is VText -> TextInstance(TextNode(v.text))
        is VElement -> {
            val el = Element(v.tag)
            val inst = HostInstance(el, null)
            inst.parent = parent
            applyProps(el, null, v.props)
            inst.vnode = v
            inst.children = reconcileChildren(inst, emptyList(), v.children)
            syncDom(inst)
            inst
        }
        is VComponent<*> -> {
            val inst = ComponentInstance(v, document)
            inst.parent = parent
            renderComponent(inst)
            inst
        }
        is VProvider<*> -> {
            val inst = ProviderInstance(v)
            inst.parent = parent
            inst.children = reconcileChildren(inst, emptyList(), v.children)
            inst
        }
        is VPortal -> {
            val inst = PortalInstance(document.openPortal(v.className))
            inst.parent = parent
            inst.children = reconcileChildren(inst, emptyList(), v.children)
            syncPortal(inst)
            inst
        }
    }.also { it.parent = parent }

    private fun sameType(inst: Instance, v: VNode): Boolean = when (inst) {
        is TextInstance -> v is VText
        is HostInstance -> v is VElement && v.tag == inst.element.tagName
        is ComponentInstance -> v is VComponent<*> && v.type === inst.vnode.type
        is ProviderInstance -> v is VProvider<*> && v.context === inst.vnode.context
        is PortalInstance -> v is VPortal
    }

    private fun update(inst: Instance, v: VNode) {
        when (inst) {
            is TextInstance -> inst.node.data = (v as VText).text
            is HostInstance -> {
                v as VElement
                applyProps(inst.element, inst.vnode?.props, v.props)
                inst.vnode = v
                inst.children = reconcileChildren(inst, inst.children, v.children)
                syncDom(inst)
            }
            is ComponentInstance -> {
                v as VComponent<*>
                val propsChanged = inst.vnode.props != v.props
                inst.vnode = v
                if (propsChanged || inst.dirty) renderComponent(inst)
            }
            is ProviderInstance -> {
                v as VProvider<*>
                val changed = inst.vnode.value != v.value
                inst.vnode = v
                if (changed) inst.consumers.forEach { document.scheduleRender(it) }
                inst.children = reconcileChildren(inst, inst.children, v.children)
            }
            is PortalInstance -> {
                v as VPortal
                inst.container.className = "guilib-portal" + (v.className?.let { " $it" } ?: "")
                inst.children = reconcileChildren(inst, inst.children, v.children)
                syncPortal(inst)
            }
        }
    }

    private fun syncPortal(inst: PortalInstance) {
        val nodes = ArrayList<Node>()
        inst.children.forEach { it.collectDom(nodes) }
        inst.container.setChildren(nodes)
    }

    fun unmount(inst: Instance) {
        if (!inst.mounted) return
        inst.mounted = false
        when (inst) {
            is HostInstance -> {
                inst.children.forEach { unmount(it) }
                inst.vnode?.props?.ref?.let { if (it.current === inst.element) it.current = null }
            }
            is ComponentInstance -> {
                inst.children.forEach { unmount(it) }
                for (h in inst.hooks) if (h is EffectHook) {
                    h.scope?.runCleanups(); h.pending = false
                }
                inst.subscriptions.forEach { it.consumers -= inst }
                document.unschedule(inst)
            }
            is ProviderInstance -> inst.children.forEach { unmount(it) }
            is PortalInstance -> {
                inst.children.forEach { unmount(it) }
                document.closePortal(inst.container)
            }
            is TextInstance -> {}
        }
    }

    // ---- children ----------------------------------------------------------------------------------------------

    fun reconcileChildren(parent: Instance, old: List<Instance>, next: List<VNode>): List<Instance> {
        val byKey = HashMap<Any?, Instance>(old.size * 2)
        for (o in old) byKey[o.matchKey] = o
        val seenKeys = HashSet<Any?>()
        val result = ArrayList<Instance>(next.size)
        next.forEachIndexed { index, v ->
            var key: Any? = v.key?.let { KeyWrapper(it) } ?: index
            if (!seenKeys.add(key)) {
                Log.warnOnce("GuiLib: duplicate key '${v.key}' among children of ${describe(parent)}; keys must be unique between siblings")
                key = DuplicateKey(index)
                seenKeys += key
            }
            val existing = byKey[key]
            val inst = if (existing != null && existing.mounted && sameType(existing, v)) {
                byKey.remove(key)
                update(existing, v)
                existing
            } else {
                mount(v, parent)
            }
            inst.matchKey = key
            inst.parent = parent
            result += inst
        }
        // Everything not reused is gone.
        for (o in byKey.values) unmount(o)
        return result
    }

    private data class KeyWrapper(val key: Any)
    private data class DuplicateKey(val index: Int)

    private fun describe(inst: Instance): String = when (inst) {
        is HostInstance -> "<${inst.element.describe()}>"
        is ComponentInstance -> "<${inst.name}>"
        is ProviderInstance -> "<${inst.vnode.context}.Provider>"
        is PortalInstance -> "<portal>"
        is TextInstance -> "text"
    }

    /** Writes the flattened DOM children of [host] into its element. */
    fun syncDom(host: HostInstance) {
        if (host.element.internalChildren) return // e.g. <input>: children are managed by its control
        val nodes = ArrayList<Node>()
        host.children.forEach { it.collectDom(nodes) }
        if (host.element === document.body) nodes += document.overlayRoot
        host.element.setChildren(nodes)
    }

    /** Re-syncs the DOM of whatever owns [inst]'s nodes: its nearest host element or portal. */
    fun syncOwner(inst: Instance) {
        var p = inst.parent
        while (p != null && p !is HostInstance && p !is PortalInstance) p = p.parent
        when (p) {
            is HostInstance -> syncDom(p)
            is PortalInstance -> syncPortal(p)
            else -> {}
        }
    }

    // ---- components --------------------------------------------------------------------------------------------

    fun renderComponent(inst: ComponentInstance) {
        inst.dirty = false
        inst.renderCount++
        val scope = ComponentScope(inst, this, document)
        val output: List<VNode> = try {
            @Suppress("UNCHECKED_CAST")
            (inst.vnode.type as ComponentType<Any?>).render(scope, inst.vnode.props)
            scope.finish()
            scope.nodes
        } catch (e: Throwable) {
            Log.error("GuiLib: error while rendering <${inst.name}>: $e\n${e.stackTraceToString().lineSequence().take(8).joinToString("\n")}")
            return // keep the previous output
        }
        inst.children = reconcileChildren(inst, inst.children, output)
    }

    /** Called when a component re-rendered on its own (state change): refresh its owner's DOM children. */
    fun afterStandaloneRender(inst: ComponentInstance) = syncOwner(inst)

    // ---- props -------------------------------------------------------------------------------------------------

    private fun applyProps(el: Element, old: ElementProps?, new: ElementProps) {
        el.id = new.id
        el.className = new.className ?: ""
        el.inlineStyle = new.style
        if (old != null) for (k in old.attrs.keys) if (k !in new.attrs) el.setAttribute(k, null)
        for ((k, v) in new.attrs) el.setAttribute(k, v)
        el.handlers = new.handlers
        if (old?.ref != null && old.ref !== new.ref && old.ref.current === el) old.ref.current = null
        new.ref?.current = el
        document.onElementPropsApplied(el, old == null)
    }
}
