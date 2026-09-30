package net.sbo.guilib.fabric.resources

/** Development-time stylesheet reloading. Filled in by the hot-reload step; for now resources are only read from jars. */
object HotReload {
    fun readOverride(location: String): String? = null
}
