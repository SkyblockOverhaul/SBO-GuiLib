package net.sbo.guilib.core.event

import net.sbo.guilib.core.Log
import net.sbo.guilib.core.dom.Element

/**
 * DOM-style propagation: capture (root → parent, handlers registered as `"type:capture"`), target,
 * then bubble (parent → root) if [UIEvent.bubbles]. [UIEvent.stopPropagation] ends it.
 */
object EventDispatcher {
    fun captureKey(type: String) = "$type:capture"

    /** Dispatches [event] to [target]; returns false if `preventDefault()` was called. */
    fun dispatch(event: UIEvent, target: Element): Boolean {
        event.target = target
        target.ownerDocument?.dispatchGlobal(event)
        if (event.propagationStopped) return !event.defaultPrevented
        val path = ArrayList<Element>()
        var e: Element? = target
        while (e != null) {
            path += e
            e = e.parent
        }

        event.eventPhase = UIEvent.Phase.CAPTURING
        for (i in path.indices.reversed()) {
            if (i == 0) break
            invoke(path[i], captureKey(event.type), event)
            if (event.propagationStopped) return !event.defaultPrevented
        }

        event.eventPhase = UIEvent.Phase.AT_TARGET
        invoke(target, captureKey(event.type), event)
        if (!event.propagationStopped) invoke(target, event.type, event)

        if (event.bubbles) {
            event.eventPhase = UIEvent.Phase.BUBBLING
            for (i in 1 until path.size) {
                if (event.propagationStopped) break
                invoke(path[i], event.type, event)
            }
        }
        event.eventPhase = UIEvent.Phase.NONE
        return !event.defaultPrevented
    }

    private fun invoke(el: Element, key: String, event: UIEvent) {
        val handler = el.handlers[key] ?: return
        event.currentTarget = el
        try {
            handler(event)
        } catch (e: Throwable) {
            Log.error("GuiLib: '${event.type}' handler on <${el.describe()}> threw: $e\n${e.stackTraceToString().lineSequence().take(8).joinToString("\n")}")
        }
    }
}
