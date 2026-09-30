package net.sbo.guilib.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal logging facade for the Minecraft-free core. The Fabric layer replaces [sink] with an SLF4J logger.
 */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    @Volatile
    var sink: (Level, String) -> Unit = { level, msg -> System.err.println("[GuiLib/$level] $msg") }

    private val seen = ConcurrentHashMap.newKeySet<String>()

    fun info(msg: String) = sink(Level.INFO, msg)
    fun warn(msg: String) = sink(Level.WARN, msg)
    fun error(msg: String) = sink(Level.ERROR, msg)

    /** Logs [msg] only the first time it is seen, so per-frame code can't spam the log. */
    fun warnOnce(msg: String) {
        if (seen.add(msg)) warn(msg)
    }

    /** Forget which warnings were already printed, e.g. after a stylesheet reload. */
    fun resetOnce() = seen.clear()
}
