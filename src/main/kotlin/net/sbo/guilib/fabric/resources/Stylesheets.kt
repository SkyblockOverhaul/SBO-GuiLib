package net.sbo.guilib.fabric.resources

import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.sbo.guilib.core.Log
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.fabric.GuiLibScreen
import java.util.Collections
import java.util.WeakHashMap

/** Loads `.css` files from resource packs / mod resources and keeps track of open screens for reloading. */
object Stylesheets {
    const val UA_LOCATION = "guilib:css/ua.css"

    private val openScreens: MutableSet<GuiLibScreen> = Collections.newSetFromMap(WeakHashMap())

    /** Reads `assets/<namespace>/<path>` for a location like `"mymod:ui/main.css"`. */
    fun readText(location: String): String? {
        HotReload.readOverride(location)?.let { return it }
        val id = Identifier.tryParse(location) ?: run {
            Log.warn("GuiLib: invalid stylesheet location '$location' (expected 'modid:path/file.css')")
            return null
        }
        val resource = Minecraft.getInstance().resourceManager.getResource(id)
        if (resource.isEmpty) {
            Log.warn("GuiLib: stylesheet '$location' not found (looked for assets/${id.namespace}/${id.path})")
            return null
        }
        return resource.get().open().use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun load(location: String, origin: Origin = Origin.AUTHOR): Stylesheet? {
        val text = readText(location) ?: return null
        val key = CacheKey(location, origin, text)
        synchronized(cache) { cache[key] }?.let { return it }
        val sheet = Stylesheet.parse(text, location, origin)
        synchronized(cache) { cache[key] = sheet }
        return sheet
    }

    private data class CacheKey(val location: String, val origin: Origin, val text: String)

    /**
     * Parsed stylesheets by their text (parsed sheets are immutable and shared by every screen), so opening a screen
     * doesn't parse ua.css and the mod's CSS again; a changed file (hot reload, resource pack) has a new key.
     */
    private val cache = object : LinkedHashMap<CacheKey, Stylesheet>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Stylesheet>?) = size > 64
    }

    /** Adds the user-agent stylesheet parsed by [net.sbo.guilib.core.Warmup] from the bundled ua.css. */
    internal fun primeUserAgent(text: String, sheet: Stylesheet) {
        synchronized(cache) { cache[CacheKey(UA_LOCATION, Origin.USER_AGENT, text)] = sheet }
    }

    /** The user-agent stylesheet followed by the given author stylesheets. */
    fun loadAll(locations: List<String>): List<Stylesheet> = buildList {
        load(UA_LOCATION, Origin.USER_AGENT)?.let { add(it) }
        for (l in locations) load(l)?.let { add(it) }
    }

    fun watch(screen: GuiLibScreen) {
        openScreens += screen
    }

    fun unwatch(screen: GuiLibScreen) {
        openScreens -= screen
    }

    /** Re-reads the stylesheets of every open GuiLib screen. */
    fun reloadAll() {
        Log.resetOnce()
        openScreens.toList().forEach { it.reloadStylesheets() }
    }
}
