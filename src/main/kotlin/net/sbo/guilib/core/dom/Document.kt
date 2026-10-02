package net.sbo.guilib.core.dom

import net.sbo.guilib.core.Log
import net.sbo.guilib.core.css.ComputedStyle
import net.sbo.guilib.core.css.Content
import net.sbo.guilib.core.css.ContentPart
import net.sbo.guilib.core.css.Display
import net.sbo.guilib.core.css.PseudoState
import net.sbo.guilib.core.css.StyleContext
import net.sbo.guilib.core.css.StyleEngine
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.event.EventDispatcher
import net.sbo.guilib.core.event.EventType
import net.sbo.guilib.core.event.FocusEvent
import net.sbo.guilib.core.event.UIEvent
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

    init {
        measurer.fontFaces(stylesheets.flatMap { it.fontFaces })
    }
    private val animator = net.sbo.guilib.core.anim.Animator(styleEngine)
    private val timeOrigin = clock()

    /** Milliseconds since this document was created (small numbers keep float precision for animations). */
    private fun animationTime(): Float = (clock() - timeOrigin).toFloat()

    /** True while transitions or animations are running (the backend keeps repainting). */
    val isAnimating get() = animator.isActive
    private val layoutEngine = LayoutEngine(measurer)
    private val reconciler = Reconciler(this)

    /** The root element (`<body>`; also matches `:root`). Always as large as the viewport. */
    val body: Element = Element("body")
    private val rootInstance = HostInstance(body, null)

    /**
     * Layer above all content, always the last child of [body]. Portals (menus, tooltips, modals) render into
     * containers inside it. Styled by `#guilib-overlay` / `.guilib-portal` in the user-agent stylesheet.
     */
    val overlayRoot: Element = Element("div").also { it.id = "guilib-overlay" }
    private val portalContainers = ArrayList<Element>()

    internal fun openPortal(className: String?): Element {
        val c = Element("div")
        c.className = "guilib-portal" + (className?.let { " $it" } ?: "")
        portalContainers += c
        overlayRoot.setChildren(portalContainers.toList())
        return c
    }

    internal fun closePortal(container: Element) {
        portalContainers.remove(container)
        overlayRoot.setChildren(portalContainers.toList())
    }

    /** System clipboard; the backend replaces it with Minecraft's. */
    var clipboard: Clipboard = Clipboard.InMemory()

    // ---- global listeners (document.addEventListener) --------------------------------------------------------

    private val globalListeners = HashMap<String, MutableList<(UIEvent) -> Unit>>()

    /**
     * Listens to every event of [type] in this document, before it reaches any element (capture phase on the
     * document). Returns a function that removes the listener. Used for "click outside" and global shortcuts.
     */
    fun addEventListener(type: String, listener: (UIEvent) -> Unit): () -> Unit {
        globalListeners.getOrPut(type) { ArrayList() } += listener
        return { globalListeners[type]?.remove(listener) }
    }

    internal fun dispatchGlobal(event: UIEvent) {
        val list = globalListeners[event.type] ?: return
        for (l in list.toList()) {
            try {
                l(event)
            } catch (e: Throwable) {
                Log.error("GuiLib: document '${event.type}' listener threw: $e")
            }
            if (event.propagationStopped) return
        }
    }

    /** Screen pixels per GUI pixel (the GUI scale), for `@media (resolution)`; set by the backend. */
    var resolution = 1f

    /**
     * The GUI scale this document asks for (screen pixels per CSS px, e.g. `2.5f`), or `null` for the backend's own
     * (Minecraft's GUI scale). The backend lays out and draws the whole document with it: viewport = window / scale,
     * so `vw`/`vh` and `@media (width, resolution)` follow. Set it with `useScreenScale()` or `GuiLib.open(scale = …)`.
     */
    var scale: Float? = null
        set(value) {
            val v = value?.takeIf { it.isFinite() && it > 0f }
            if (field != v) {
                field = v; invalidateLayout()
            }
        }
    private var appliedResolution = 1f

    var viewportWidth = 0f
        private set
    var viewportHeight = 0f
        private set

    /** Called for every element whose props were (re)applied, e.g. to attach image/item content. Set by the backend. */
    var elementInitializer: ((Element) -> Unit)? = null

    internal val styleEngineDependsOnAncestorState get() = styleEngine.dependsOnAncestorState
    internal val styleEngineUsesStructural get() = styleEngine.usesStructural
    internal val styleEngineSiblingsDependOnClasses get() = styleEngine.siblingsDependOnClasses
    internal val styleEngineSiblingsDependOnState get() = styleEngine.siblingsDependOnState
    internal val styleEngineHasPseudoElements get() = styleEngine.hasPseudoElements

    init {
        body.attach(this)
        body.setChildren(listOf(overlayRoot))
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
        measurer.fontFaces(sheets.flatMap { it.fontFaces })
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

    /** Current time of the document's clock in milliseconds. */
    fun now(): Long = clock()

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

        if (resolution != appliedResolution) {
            appliedResolution = resolution
            body.styleChanged(true) // @media (resolution)
            invalidateLayout() // text widths snap to the new pixel grid
        }
        if (viewportWidth != this.viewportWidth || viewportHeight != this.viewportHeight) {
            this.viewportWidth = viewportWidth
            this.viewportHeight = viewportHeight
            body.styleChanged(true) // vw/vh units
            invalidateLayout()
        }
        styleAndLayout()
        // Transitions/animations write their current values, then layout catches up within the same frame.
        if (animator.isActive) {
            animator.tick(animationTime()) { el, props ->
                if (props.any { !ComputedStyle.isPaintOnly(it) }) invalidateLayout() else invalidatePaint()
                // Inherited values (e.g. color) must reach the children.
                if (props.any { it.inherited }) el.styleChanged(true)
            }
        }
        // Controls position carets etc. from the layout; if that changed something, settle it in the same frame.
        val now = clock()
        for (h in frameHooks) h(now)
        styleAndLayout()
        val repaint = paintDirty
        paintDirty = false
        return repaint
    }

    /** Per-document singletons of other parts of the library (e.g. the toaster), keyed by their class. */
    internal val services = HashMap<Any, Any>()

    /** Called every [update] after layout with the current time (ms). */
    internal val frameHooks = ArrayList<(Long) -> Unit>()

    private fun styleAndLayout() {
        if (styleDirty) recalcStyles()
        if (layoutDirty) {
            net.sbo.guilib.core.FrameStats.layout()
            layoutEngine.layout(body, viewportWidth, viewportHeight)
            layoutDirty = false
            clampScroll(body)
        }
    }

    private fun clampScroll(el: Element) {
        el.clampScroll()
        for (c in el.children) if (c is Element) clampScroll(c)
    }

    private fun recalcStyles() {
        styleDirty = false
        net.sbo.guilib.core.FrameStats.style()
        val rootStyle = styleEngine.compute(body, body.inlineDeclarations, null, StyleContext(viewportWidth, viewportHeight, resolution = resolution))
        val ctx = StyleContext(viewportWidth, viewportHeight, rootStyle.fontSize, resolution)
        recalc(body, null, force = false, ctx)
    }

    private fun recalc(el: Element, parentStyle: ComputedStyle?, force: Boolean, ctx: StyleContext) {
        var forceChildren = force || el.subtreeStyleDirty
        if (force || el.styleDirty) {
            val next = styleEngine.compute(el, el.inlineDeclarations, parentStyle, ctx)
            val old = el.computed
            if (!next.sameAs(old)) {
                if (next.layoutDiffers(old)) invalidateLayout() else invalidatePaint()
                animator.onStyleComputed(el, old, next, parentStyle, ctx, animationTime())
                el.computed = next
                forceChildren = true
            }
            if (styleEngine.hasPseudoElements || el.pseudoBefore != null || el.pseudoAfter != null) {
                updatePseudo(el, false, ctx)
                updatePseudo(el, true, ctx)
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

    /** Creates, updates or removes the `::before` ([after] = false) or `::after` box of [el]. */
    private fun updatePseudo(el: Element, after: Boolean, ctx: StyleContext) {
        val name = if (after) "after" else "before"
        val host = el.computed
        val style = if (host != null && styleEngine.hasPseudoRules(name) && el.canHavePseudo) {
            styleEngine.compute(el, emptyList(), host, ctx, name)
        } else null
        val content = style?.content as? Content.Items
        if (style == null || content == null || style.display == Display.NONE) {
            el.setPseudo(after, null)
            return
        }
        val box = (if (after) el.pseudoAfter else el.pseudoBefore) ?: Element("::$name").also {
            it.pseudoHost = el
            el.setPseudo(after, it)
        }
        box.setPseudoText(content.parts.joinToString("") { p ->
            when (p) {
                is ContentPart.Text -> p.text
                is ContentPart.Attr -> el.styleAttribute(p.name) ?: ""
            }
        })
        val old = box.computed
        if (!style.sameAs(old)) {
            if (style.layoutDiffers(old)) invalidateLayout() else invalidatePaint()
            animator.onStyleComputed(box, old, style, host, ctx, animationTime())
            box.computed = style
        }
        box.styleDirty = false
        box.subtreeStyleDirty = false
        box.childStyleDirty = false
    }

    // ---- focus & interaction state ----------------------------------------------------------------------------

    var focusedElement: Element? = null
        private set

    /** Moves keyboard focus, firing blur/focusout and focus/focusin like the DOM. `null` clears focus. */
    /** True when the last input was a key press (focus then shows `:focus-visible`); set by the interaction layer. */
    var keyboardModality = false

    fun focus(el: Element?) {
        val old = focusedElement
        if (old === el) return
        if (el != null && (el.disabled || el.document !== this)) return
        focusedElement = el
        if (old != null) {
            old.setState(PseudoState.FOCUS, false)
            old.setState(PseudoState.FOCUS_VISIBLE, false)
            setFocusWithin(old, false)
            EventDispatcher.dispatch(FocusEvent(EventType.BLUR, el, bubbles = false), old)
            EventDispatcher.dispatch(FocusEvent(EventType.FOCUSOUT, el, bubbles = true), old)
        }
        if (el != null) {
            el.setState(PseudoState.FOCUS, true)
            if (keyboardModality || el.tagName == "input" || el.tagName == "textarea") el.setState(PseudoState.FOCUS_VISIBLE, true)
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
        if (node is Element) animator.remove(node)
        detachListeners.forEach { it(node) }
    }

    /** Built-in controls (inputs, …) hook in here; see [net.sbo.guilib.core.controls.Controls]. */
    internal var controlInitializer: ((Element, Boolean) -> Unit)? = null

    internal fun onElementPropsApplied(el: Element, created: Boolean) {
        controlInitializer?.invoke(el, created)
        elementInitializer?.invoke(el)
    }

    /** Runs state updates and effects scheduled by event handlers right away (so the next frame sees them). */
    fun flush() = flushWork()
}
