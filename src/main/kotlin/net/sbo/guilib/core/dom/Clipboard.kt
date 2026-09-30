package net.sbo.guilib.core.dom

/** Clipboard access used by text inputs. */
interface Clipboard {
    fun get(): String
    fun set(text: String)

    /** Fallback used in tests and before the backend installs the real clipboard. */
    class InMemory : Clipboard {
        private var value = ""
        override fun get() = value
        override fun set(text: String) {
            value = text
        }
    }
}
