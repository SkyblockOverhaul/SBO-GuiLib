package net.sbo.guilib.fabric.resources

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.sbo.guilib.core.Log
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Development-time stylesheet hot reload.
 *
 * Active in a development environment (or with `-Dguilib.hotReload=true`). For a stylesheet `modid:path/x.css` the
 * matching *source* file (`src/main/resources/assets/modid/path/x.css`) is located and read directly, so edits show
 * up without rebuilding. Files are polled twice a second; on a change all open GuiLib screens reload their CSS.
 *
 * Extra source roots can be given with `-Dguilib.resourceDirs=path/a;path/b` (each containing `assets/…`).
 */
object HotReload {
    val enabled: Boolean =
        System.getProperty("guilib.hotReload")?.toBoolean() ?: FabricLoader.getInstance().isDevelopmentEnvironment

    private class Watched(val file: Path, var lastModified: Long)

    private val watched = HashMap<String, Watched?>()
    private var ticks = 0

    private val extraRoots: List<Path> = System.getProperty("guilib.resourceDirs")
        ?.split(';', java.io.File.pathSeparatorChar)?.filter { it.isNotBlank() }?.map { Paths.get(it.trim()) }
        ?: emptyList()

    fun init() {
        if (!enabled) return
        Log.info("GuiLib: CSS hot reload enabled")
        ClientTickEvents.END_CLIENT_TICK.register {
            if (++ticks % 10 != 0) return@register
            val changed = watched.filterValues { w ->
                w != null && Files.exists(w.file) && Files.getLastModifiedTime(w.file).toMillis() != w.lastModified
            }
            if (changed.isNotEmpty()) {
                changed.values.forEach { it!!.lastModified = Files.getLastModifiedTime(it.file).toMillis() }
                Log.info("GuiLib: reloading ${changed.keys.joinToString()}")
                Stylesheets.reloadAll()
            }
        }
    }

    /** Source text of [location] when hot reload can find its source file, otherwise `null` (use the packed resource). */
    fun readOverride(location: String): String? {
        if (!enabled) return null
        val w = watched.getOrPut(location) { findSource(location)?.let { Watched(it, Files.getLastModifiedTime(it).toMillis()) } } ?: return null
        return try {
            Files.readString(w.file)
        } catch (e: Exception) {
            Log.warn("GuiLib: hot reload could not read ${w.file}: $e")
            null
        }
    }

    private fun findSource(location: String): Path? {
        val ns = location.substringBefore(':', "minecraft")
        val path = location.substringAfter(':')
        val rel = "assets/$ns/$path"
        for (root in extraRoots) root.resolve(rel).takeIf { Files.isRegularFile(it) }?.let { return it }

        // Walk up from where the mod's resources are loaded (e.g. build/resources/main) to a src/main/resources.
        val container = FabricLoader.getInstance().getModContainer(ns).orElse(null) ?: return null
        val packed = container.findPath(rel).orElse(null) ?: return null
        if (packed.fileSystem != java.nio.file.FileSystems.getDefault()) return null // inside a jar: not a dev setup
        var dir: Path? = packed.parent
        repeat(12) {
            val d = dir ?: return null
            val candidate = d.resolve("src/main/resources").resolve(rel)
            if (Files.isRegularFile(candidate)) {
                Log.info("GuiLib: hot reload watches $candidate")
                return candidate
            }
            dir = d.parent
        }
        return null
    }
}
