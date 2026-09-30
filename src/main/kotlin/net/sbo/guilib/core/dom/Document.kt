package net.sbo.guilib.core.dom

import net.sbo.guilib.core.Log
import net.sbo.guilib.core.css.ComputedStyle
import net.sbo.guilib.core.css.PseudoState
import net.sbo.guilib.core.css.StyleContext
import net.sbo.guilib.core.css.StyleEngine
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.event.EventDispatcher
import net.sbo.guilib.core.event.FocusEvent
import net.sbo.guilib.core.event.EventType
import net.sbo.guilib.core.layout.LayoutEngine
import net.sbo.guilib.core.layout.TextMeasurer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Root of a UI: owns the element tree, the component instances, stylesheets and the update loop
 * (state flush → effects → style → layout). The backend calls [update] once per frame and paints if it returns true.
 */
class Document(
    val measurer: TextMeasurer,
    stylesheets: List<Stylesheet> = emptyList(),
    /** Millisecond clock for timers; injectable for tests. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val styleEngine = StyleEngine(stylesheets)
    private val layoutEngine = LayoutEngine(measurer)
    private val reconciler = Reconciler(this)

    /** The root element (`<body>`; also matches `:root`). Always as large as the viewport. */
    val body: Element = Element("body")
    private val rootInstance = HostInstance(body, null)

    var viewportWidth = 0f
        private set
    var viewportHeight = 0f
        private set

    /** Called for every element whose props were (re)applied, e.g. to attach image/item content. Set by the backend. */
    var elementInitializer: ((Element) -> Unit)? = null

    internal val styleEngineDependsOnAncestorState get() = styleEngine.dependsOnAncestorState
    internal val styleEngineUsesStructural get() = styleEngine.usesStructural

    init {
        body.attach(this)
    }

    // ---- invalidation -----------------------------------------------------------------------------------------

    var styleDirty = true
        private set
    var layoutDirty = true
        private set
    var paintDirty = true
        private set

    internal fun invalidateStyle() {
        styleDirty = true
    }

    fun invalidateLayout() {
        layoutDirty = true; paintDirty = true
    }

    fun invalidatePaint() {
        paintDirty = true
    }

    fun setStylesheets(sheets: List<Stylesheet>) {
        styleEngine.stylesheets = sheets
        body.styleChanged(true)
    }

    // ---- rendering --------------------------------------------------------------------------------------------

    /** Renders [vnode] into the body, replacing/diffing the previous content (like `root.render(<App/>)`). */
    fun render(vnode: VNode) = render(listOf(vnode))

    fun render(vnodes: List<VNode>) {
        uiThread = Thread.currentThread()
        rootInstance.children = reconciler.reconcileChildren(rootInstance, rootInstance.children, vnodes)
        reconciler.syncDom(rootInstance)
        flushWork()
    }

    /** Unmounts everything (runs all effect cleanups). Call when the screen closes. */
    fun unmount() {
        rootInstance.children.forEach { reconciler.unmount(it) }
        rootInstance.children = emptyList()
        reconciler.syncDom(rootInstance)
        timers.clear()
    }

    private val dirtyComponents = LinkedHashSet<ComponentInstance>()

    internal fun scheduleRender(inst: ComponentInstance) {
        inst.dirty = true
        dirtyComponents += inst
    }

    internal fun unschedule(inst: ComponentInstance) {
        dirtyComponents -= inst
    }

    /** Re-renders dirty components (parents first) and runs pending effects until everything settled. */
    private fun flushWork() {
        var rounds = 0
        while (dirtyComponents.isNotEmpty() || reconciler.pendingEffects.isNotEmpty()) {
            if (++rounds > 50) {
                Log.warnOnce("GuiLib: state keeps changing during render/effects (infinite update loop?); giving up this frame")
                break
            }
            while (dirtyComponents.isNotEmpty()) {
                val batch = dirtyComponents.sortedBy { it.depth }
                dirtyComponents.clear()
                for (c in batch) {
                    if (!c.mounted || !c.dirty) continue
                    reconciler.renderComponent(c)
                    reconciler.afterStandaloneRender(c)
                }
            }
            val effects = reconciler.pendingEffects.toList()
            reconciler.pendingEffects.clear()
            for (h in effects) {
                if (!h.pending) continue
                h.pending = false
                h.scope?.runCleanups()
                val scope = EffectScope(this)
                h.scope = scope
                try {
                    h.effect?.invoke(scope)
                } catch (e: Throwable) {
                    Log.error("GuiLib: useEffect threw: $e")
                }
            }
        }
    }

    // ---- threading & timers -----------------------------------------------------------------------------------

    @Volatile
    private var uiThread: Thread? = null
    private val posted = ConcurrentLinkedQueue<() -> Unit>()

    internal fun isUiThread() = uiThread == null || Thread.currentThread() === uiThread

    /** Runs [block] on the UI thread before the next frame (safe to call from any thread). */
    fun post(block: () -> Unit) {
        posted += block
    }

    private class Timer(var due: Long, val interval: Long?, val callback: () -> Unit) : Cancelable {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }

    private val timers = ArrayList<Timer>()

    fun setTimeout(ms: Long, callback: () -> Unit): Cancelable = Timer(clock() + ms, null, callback).also { timers += it }
    fun setInterval(ms: Long, callback: () -> Unit): Cancelable = Timer(clock() + ms, ms.coerceAtLeast(1), callback).also { timers += it }

    private fun runTimers() {
        if (timers.isEmpty()) return
        val now = clock()
        for (t in timers.toList()) {
            if (t.cancelled || t.due > now) continue
            try {
                t.callback()
            } catch (e: Throwable) {
                Log.error("GuiLib: timer callback threw: $e")
            }
            if (t.interval != null) t.due = maxOf(t.due + t.interval, now) else t.cancelled = true
        }
        timers.removeAll { it.cancelled }
    }

    // ---- frame update -----------------------------------------------------------------------------------------

    /**
     * Brings the tree up to date for a frame of the given size. Returns true if something visible changed.
     * Must be called on the UI (render) thread.
     */
    fun update(viewportWidth: Float, viewportHeight: Float): Boolean {
        uiThread = Thread.currentThread()
        while (true) {
            val task = posted.poll() ?: break
            try {
                task()
            } catch (e: Throwable) {
                Log.error("GuiLib: posted task threw: $e")
            }
        }
        runTimers()
        flushWork()

        if (viewportWidth != this.viewportWidth || viewportHeight != this.viewportHeight) {
            this.viewportWidth = viewportWidth
            this.viewportHeight = viewportHeight
            body.styleChanged(true) // vw/vh units
            invalidateLayout()
        }
        if (styleDirty) recalcStyles()
        if (layoutDirty) {
            layoutEngine.layout(body, viewportWidth, viewportHeight)
            layoutDirty = false
            clampScroll(body)
        }
        val repaint = paintDirty
        paintDirty = false
        return repaint
    }

    private fun clampScroll(el: Element) {
        el.scrollTop = el.scrollTop
        el.scrollLeft = el.scrollLeft
        for (c in el.children) if (c is Element) clampScroll(c)
    }

    private fun recalcStyles() {
        styleDirty = false
        val rootStyle = styleEngine.compute(body, body.inlineDeclarations, null, StyleContext(viewportWidth, viewportHeight))
        val ctx = StyleContext(viewportWidth, viewportHeight, rootStyle.fontSize)
        recalc(body, null, force = false, ctx)
    }

    private fun recalc(el: Element, parentStyle: ComputedStyle?, force: Boolean, ctx: StyleContext) {
        var forceChildren = force || el.subtreeStyleDirty
        if (force || el.styleDirty) {
            val next = styleEngine.compute(el, el.inlineDeclarations, parentStyle, ctx)
            val old = el.computed
            if (!next.sameAs(old)) {
                if (next.layoutDiffers(old)) invalidateLayout() else invalidatePaint()
                el.computed = next
                forceChildren = true
            }
        }
        val visitChildren = forceChildren || el.childStyleDirty
        el.styleDirty = false
        el.subtreeStyleDirty = false
        el.childStyleDirty = false
        if (visitChildren) {
            for (c in el.children) if (c is Element) recalc(c, el.style, forceChildren, ctx)
        }
    }

    // ---- focus & interaction state ----------------------------------------------------------------------------

    var focusedElement: Element? = null
        private set

    /** Moves keyboard focus, firing blur/focusout and focus/focusin like the DOM. `null` clears focus. */
    fun focus(el: Element?) {
        val old = focusedElement
        if (old === el) return
        if (el != null && (el.disabled || el.document !== this)) return
        focusedElement = el
        if (old != null) {
            old.setState(PseudoState.FOCUS, false)
            setFocusWithin(old, false)
            EventDispatcher.dispatch(FocusEvent(EventType.BLUR, el, bubbles = false), old)
            EventDispatcher.dispatch(FocusEvent(EventType.FOCUSOUT, el, bubbles = true), old)
        }
        if (el != null) {
            el.setState(PseudoState.FOCUS, true)
            setFocusWithin(el, true)
            EventDispatcher.dispatch(FocusEvent(EventType.FOCUS, old, bubbles = false), el)
            EventDispatcher.dispatch(FocusEvent(EventType.FOCUSIN, old, bubbles = true), el)
        }
        invalidatePaint()
        flushWork()
    }

    private fun setFocusWithin(el: Element, on: Boolean) {
        var e: Element? = el
        while (e != null) {
            e.setState(PseudoState.FOCUS_WITHIN, on)
            e = e.parent
        }
    }

    /** Hooks for the interaction layer (hover/active tracking) to forget detached elements. */
    internal val detachListeners = ArrayList<(Node) -> Unit>()

    internal fun onDetach(node: Node) {
        if (node is Element && focusedElement === node) {
            focusedElement = null
        }
        detachListeners.forEach { it(node) }
    }

    internal fun onElementPropsApplied(el: Element) {
        elementInitializer?.invoke(el)
    }

    /** Runs state updates and effects scheduled by event handlers right away (so the next frame sees them). */
    fun flush() = flushWork()
}
