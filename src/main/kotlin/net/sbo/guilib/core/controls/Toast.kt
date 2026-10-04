package net.sbo.guilib.core.controls

import net.sbo.guilib.core.dom.Cancelable
import net.sbo.guilib.core.dom.Document
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.TextNode
import net.sbo.guilib.core.event.EventType

/** A button in a toast, e.g. `ToastAction("Undo") { restore() }`. Clicking it runs [onClick] and closes the toast. */
class ToastAction(val label: String, val onClick: () -> Unit)

/** A shown toast; [dismiss] removes it early. */
class Toast internal constructor(private val toaster: Toaster, internal val element: Element) {
    internal var timer: Cancelable? = null
    internal var leaving = false

    fun dismiss() = toaster.dismiss(this)
}

/**
 * Short messages that disappear on their own ("Party created", "Server not reachable"), stacked in a corner of the
 * screen above everything else. Get it with `useToast()` in a component; safe to call from any thread.
 *
 * ```kotlin
 * val toast = useToast()
 * button(onClick = { toast.success("Party created") }) { … }
 * ```
 * Styled with `.guilib-toasts` (the stack, bottom right), `.guilib-toast` (`.info`, `.success`, `.warning`, `.error`,
 * `.leaving`), `.guilib-toast-accent` (the colored bar), `.guilib-toast-title`, `.guilib-toast-message`, `.guilib-toast-action`,
 * `.guilib-toast-close`.
 */
class Toaster internal constructor(private val doc: Document) {
    private var stack: Element? = null
    private val shown = ArrayList<Toast>()

    /**
     * Shows [message]. [kind] is a class on the toast (`info`, `success`, `warning`, `error` are styled).
     * [durationMs] ≤ 0 keeps it until clicked. [action] adds a button below the message (e.g. "Undo").
     * Returns the toast (call `dismiss()` to remove it early).
     */
    fun show(
        message: String,
        kind: String = "info",
        title: String? = null,
        durationMs: Long = DEFAULT_DURATION_MS,
        action: ToastAction? = null,
    ): Toast {
        val el = Element("div")
        el.className = "guilib-toast $kind"
        val toast = Toast(this, el)
        val body = Element("div").also { it.className = "guilib-toast-body" }
        val parts = ArrayList<Element>()
        if (title != null) parts += Element("div").also { it.className = "guilib-toast-title"; it.setChildren(listOf(TextNode(title))) }
        parts += Element("div").also { it.className = "guilib-toast-message"; it.setChildren(listOf(TextNode(message))) }
        if (action != null) parts += Element("button").also {
            it.className = "guilib-toast-action"
            it.setChildren(listOf(TextNode(action.label)))
            it.handlers = mapOf(EventType.CLICK to { e ->
                e.stopPropagation()
                if (!toast.leaving) action.onClick()
                toast.dismiss()
            })
        }
        body.setChildren(parts)
        val close = Element("span").also { it.className = "guilib-toast-close"; it.setChildren(listOf(TextNode("✕"))) }
        val accent = Element("div").also { it.className = "guilib-toast-accent" }
        el.setChildren(listOf(accent, body, close))
        el.handlers = mapOf(EventType.CLICK to { _ -> toast.dismiss() })
        onUiThread {
            val s = stack ?: Element("div").also {
                it.className = "guilib-toasts"
                doc.openPortal("guilib-toast-portal").setChildren(listOf(it))
                stack = it
            }
            shown += toast
            while (shown.count { !it.leaving } > MAX_TOASTS) shown.first { !it.leaving }.dismiss()
            s.setChildren(shown.map { it.element })
            if (durationMs > 0) toast.timer = doc.setTimeout(durationMs) { toast.dismiss() }
        }
        return toast
    }

    fun info(message: String, title: String? = null, durationMs: Long = DEFAULT_DURATION_MS, action: ToastAction? = null) =
        show(message, "info", title, durationMs, action)
    fun success(message: String, title: String? = null, durationMs: Long = DEFAULT_DURATION_MS, action: ToastAction? = null) =
        show(message, "success", title, durationMs, action)
    fun warning(message: String, title: String? = null, durationMs: Long = DEFAULT_DURATION_MS, action: ToastAction? = null) =
        show(message, "warning", title, durationMs, action)
    fun error(message: String, title: String? = null, durationMs: Long = DEFAULT_DURATION_MS, action: ToastAction? = null) =
        show(message, "error", title, durationMs, action)

    /** Removes all toasts. */
    fun clear() = onUiThread { shown.toList().forEach { dismiss(it) } }

    internal fun dismiss(toast: Toast) = onUiThread {
        if (toast.leaving || toast !in shown) return@onUiThread
        toast.leaving = true
        toast.timer?.cancel()
        toast.element.className += " leaving"
        // Let the exit animation play, then remove it.
        doc.setTimeout(EXIT_MS) {
            shown.remove(toast)
            stack?.setChildren(shown.map { it.element })
        }
    }

    private fun onUiThread(block: () -> Unit) {
        if (doc.isUiThread()) block() else doc.post(block)
    }

    companion object {
        const val DEFAULT_DURATION_MS = 3500L
        internal const val EXIT_MS = 180L
        internal const val MAX_TOASTS = 5
        /** The toaster of [doc] (one per document/screen). */
        @JvmStatic
        fun of(doc: Document): Toaster = synchronized(doc.services) { doc.services.getOrPut(Toaster::class) { Toaster(doc) } as Toaster }
    }
}
