package net.sbo.guilib.dev

import com.mojang.blaze3d.pipeline.RenderTarget
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.sbo.guilib.core.Log
import net.sbo.guilib.core.event.Modifiers
import net.sbo.guilib.fabric.GuiLib
import net.sbo.guilib.fabric.GuiLibScreen
import net.sbo.guilib.fabric.showcase.Showcase

/**
 * DEVELOPMENT ONLY — lives in the `dev` source set, is loaded only by GuiLib's own `runClient`
 * and is never part of the published jar.
 *
 * Visual checks without manual clicking: start the client with `-Pguilib.dev.shots=Buttons,Layout` (or `all`).
 * Once the title screen is up, each showcase section is opened, optionally hovered/clicked/typed into, captured to
 * `run/screenshots/guilib-*.png`, and the game quits afterwards.
 *
 * With `-Pguilib.dev.world=<name>` a creative singleplayer world (created on first use, kept in `run/saves`) is loaded
 * first, for things that need one (items, `entity`/`FakePlayer`).
 */
object DevAutomation : ClientModInitializer {
    private class Step(val ticks: Int, val action: () -> Unit)

    private val steps = ArrayDeque<Step>()
    private var wait = 0
    private var started = false
    private val world: String? = System.getProperty("guilib.dev.world")
    private var worldRequested = false

    override fun onInitializeClient() {
        val spec = System.getProperty("guilib.dev.shots") ?: return
        val sections = if (spec == "all") Showcase.SECTIONS else spec.split(',').map { it.trim() }
        Log.info("GuiLib dev automation: capturing ${sections.joinToString()}")
        sections.forEachIndexed { i, s ->
            // "Forms#2" opens the Forms section again with its own hover/script properties.
            steps += Step(20) {
                // "call:com.example.MyGui.open" opens any screen via a static/object method instead of a showcase section.
                if (s.startsWith("call:")) callOpen(s.removePrefix("call:").substringBefore('#')) else Showcase.open(s.substringBefore('#'))
            }
            // Frame statistics per section (logged before its final screenshot), for profiling.
            steps += Step(1) { statsStart = System.nanoTime(); net.sbo.guilib.core.FrameStats.reset() }
            steps += Step(20) { hover(System.getProperty("guilib.dev.hover.$s")) }
            // Optional script: -Pguilib.dev.script.Forms="click:input;type:Steve;click:select"
            // "shot" captures one tick after the previous step (to catch animations mid-way).
            System.getProperty("guilib.dev.script.$s")?.split(';')?.filter { it.isNotBlank() }?.forEachIndexed { j, action ->
                when {
                    action.startsWith("wait:") -> steps += Step(action.substringAfter(':').toInt()) {}
                    action == "shot" -> steps += Step(1) { shot("guilib-${i + 1}-${s.substringAfterLast('.').lowercase().replace('#', '-')}-step$j.png") }
                    else -> steps += Step(8) { runAction(action) }
                }
            }
            steps += Step(10) {
                logStats(s)
                shot("guilib-${i + 1}-${s.substringAfterLast('.').lowercase().replace('#', '-')}.png")
            }
        }
        steps += Step(20) { Minecraft.getInstance().stop() }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!started) {
                if (world != null && worldRequested) {
                    val mc = Minecraft.getInstance()
                    if (mc.level == null || mc.player == null || GuiLib.currentScreen() != null) return@register
                    started = true
                    wait = 60 // let chunks and skins load
                    applyGuiScale()
                    return@register
                }
                if (GuiLib.currentScreen() !is TitleScreen) return@register
                if (world != null) {
                    worldRequested = true
                    openWorld(world)
                    return@register
                }
                started = true
                wait = 40
                applyGuiScale()
                return@register
            }
            if (--wait > 0) return@register
            val step = steps.removeFirstOrNull() ?: return@register
            try {
                step.action()
            } catch (e: Throwable) {
                Log.error("GuiLib dev automation step failed: ${e.stackTraceToString().lineSequence().take(12).joinToString("\n")}")
            }
            wait = steps.firstOrNull()?.ticks ?: 0
        }
    }

    private var statsStart = 0L

    private fun logStats(section: String) {
        val st = net.sbo.guilib.core.FrameStats
        val secs = (System.nanoTime() - statsStart) / 1e9
        val f = st.frames.coerceAtLeast(1)
        Log.info(
            String.format(
                java.util.Locale.ROOT,
                "GuiLib stats %-12s %5.0f fps | update %.3f ms + draw %.3f ms per frame | per s: %.1f styles, %.1f layouts, %.1f paints",
                section, st.frames / secs, st.updateNanos / 1e6 / f, st.drawNanos / 1e6 / f, st.styles / secs, st.layouts / secs, st.paints / secs,
            ),
        )
        // -Dguilib.dev.heap=true: heap in use after a full GC (dev only, the GC itself causes a hitch), to spot leaks.
        if (System.getProperty("guilib.dev.heap") == "true") {
            val rt = Runtime.getRuntime()
            System.gc()
            Log.info(String.format(java.util.Locale.ROOT, "GuiLib heap %-12s %.1f MB used after GC", section, (rt.totalMemory() - rt.freeMemory()) / 1048576.0))
        }
    }

    /** -Pguilib.dev.guiscale=3 sets Minecraft's GUI scale (0 = auto) before the first section opens. */
    private fun applyGuiScale() {
        val scale = System.getProperty("guilib.dev.guiscale")?.toIntOrNull() ?: return
        val mc = Minecraft.getInstance()
        mc.options.guiScale().set(scale)
        mc.resizeGui()
        Log.info("GuiLib dev automation: GUI scale ${mc.window.guiScale}")
    }

    private fun openWorld(name: String) {
        val mc = Minecraft.getInstance()
        Log.info("GuiLib dev automation: loading world '$name'")
        val flows = mc.createWorldOpenFlows()
        if (mc.levelSource.levelExists(name)) {
            flows.openWorld(name) { Log.error("GuiLib dev automation: could not open world '$name'") }
        } else {
            val settings = LevelSettings(name, GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT)
            flows.createFreshLevel(name, settings, WorldOptions(0L, false, false), WorldPresets::createNormalWorldDimensions, TitleScreen())
        }
    }

    private fun callOpen(target: String) {
        val cls = Class.forName(target.substringBeforeLast('.'))
        val method = cls.getMethod(target.substringAfterLast('.'))
        val instance = runCatching { cls.getField("INSTANCE").get(null) }.getOrNull() // Kotlin object
        method.invoke(instance)
    }

    private fun runAction(action: String) {
        val screen = GuiLib.currentScreen() as? GuiLibScreen ?: return
        val root = screen.root
        val (verb, arg) = action.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        // The overlay (menus, popovers, toasts) is searched too, after the body.
        fun find(sel: String) = root.document.body.querySelector(sel) ?: root.document.overlayRoot.querySelector(sel)
        when (verb) {
            // "rclick:.row" right-clicks (opens context menus).
            "rclick" -> find(arg)?.getBoundingClientRect()?.let { r ->
                root.input.mouseDown(r.x + 2f, r.y + r.height / 2f, 2)
                root.input.mouseUp(r.x + 2f, r.y + r.height / 2f, 2)
            }
            "click" -> find(arg)?.getBoundingClientRect()?.let { r ->
                root.input.mouseDown(r.x + 2f, r.y + r.height / 2f, 0)
                root.input.mouseUp(r.x + 2f, r.y + r.height / 2f, 0)
            }
            "hover" -> hover(arg)
            // "uiscale:1.5" gives the screen its own GUI scale (like useScreenScale); "uiscale:mc" resets it.
            "uiscale" -> root.document.scale = arg.toFloatOrNull()
            // "mcclick:.btn" clicks through Minecraft's Screen.mouseClicked/mouseReleased (Minecraft GUI coordinates),
            // so the screen's own coordinate mapping (useScreenScale) is part of the test.
            "mcclick" -> find(arg)?.getBoundingClientRect()?.let { r ->
                val (x, y) = toMinecraft(screen, r.x + r.width / 2f, r.y + r.height / 2f)
                val event = net.minecraft.client.input.MouseButtonEvent(x, y, net.minecraft.client.input.MouseButtonInfo(net.sbo.guilib.fabric.input.Keys.MOUSE_LEFT, 0))
                screen.mouseClicked(event, false)
                screen.mouseReleased(event)
            }
            // "type:abc"; U+65E5 escapes for text the Windows command line would mangle. Typed per code point, like GLFW does.
            "type" -> unescape(arg).codePoints().forEach { root.input.charTyped(String(Character.toChars(it))) }
            // "key:Enter", with modifiers "key:shift+Home" / "key:ctrl+a".
            "key" -> {
                val parts = arg.split('+')
                val mods = parts.dropLast(1).map { it.lowercase() }
                val modifiers = Modifiers(shift = "shift" in mods, ctrl = "ctrl" in mods, alt = "alt" in mods, meta = "meta" in mods)
                root.input.keyDown(parts.last(), 0, modifiers)
            }
            // "wheel:.content:200" scrolls the scroll container matching the selector by 200px.
            "wheel" -> {
                val sel = arg.substringBeforeLast(':')
                val dy = arg.substringAfterLast(':').toFloat()
                find(sel)?.getBoundingClientRect()?.let { r -> root.input.wheel(r.x + r.width / 2f, r.y + r.height / 2f, 0f, dy) }
            }
            // "hwheel:.h-scroll:100" scrolls sideways (like Shift + wheel or a trackpad).
            "hwheel" -> {
                val sel = arg.substringBeforeLast(':')
                val dx = arg.substringAfterLast(':').toFloat()
                find(sel)?.getBoundingClientRect()?.let { r -> root.input.wheel(r.x + r.width / 2f, r.y + r.height / 2f, dx, 0f) }
            }
            // "notch:.h-scroll:3" turns the mouse wheel 3 notches down over the element, through Minecraft's own
            // scroll callback (unlike "wheel", which talks to GuiLib directly).
            "notch" -> {
                val sel = arg.substringBeforeLast(':')
                val notches = arg.substringAfterLast(':').toDouble()
                find(sel)?.getBoundingClientRect()?.let { r ->
                    val (x, y) = toMinecraft(screen, r.x + r.width / 2f, r.y + r.height / 2f)
                    repeat(kotlin.math.abs(notches).toInt()) {
                        screen.mouseScrolled(x, y, 0.0, -kotlin.math.sign(notches))
                    }
                }
            }
            // Dragging: "press:.item" holds the left button at the element's center, "move:0,30" moves the mouse
            // relative to the last position (button still held), "release" lets go.
            "press" -> find(arg)?.getBoundingClientRect()?.let { r ->
                pointerX = r.x + r.width / 2f
                pointerY = r.y + r.height / 2f
                root.input.mouseDown(pointerX, pointerY, 0)
            }
            "move" -> {
                val (dx, dy) = arg.split(',').map { it.trim().toFloat() }
                // In small steps, like a real mouse.
                repeat(4) {
                    pointerX += dx / 4f
                    pointerY += dy / 4f
                    root.input.mouseMove(pointerX, pointerY)
                }
            }
            "release" -> root.input.mouseUp(pointerX, pointerY, 0)
        }
    }

    /** Document px → Minecraft GUI px (they differ when the screen has its own scale). */
    private fun toMinecraft(screen: GuiLibScreen, x: Float, y: Float): Pair<Double, Double> {
        val k = (screen.root.document.scale ?: return x.toDouble() to y.toDouble()) / Minecraft.getInstance().window.guiScale.toFloat()
        return (x * k).toDouble() to (y * k).toDouble()
    }

    private var pointerX = 0f
    private var pointerY = 0f

    private fun unescape(s: String) = Regex("""U\+([0-9a-fA-F]{4,6})""").replace(s) { String(Character.toChars(it.groupValues[1].toInt(16))) }

    /** Moves the virtual mouse over the first element matching [selector] (for :hover screenshots). */
    private fun hover(selector: String?) {
        val screen = GuiLib.currentScreen() as? GuiLibScreen ?: return
        val el = selector?.let { screen.root.document.body.querySelector(it) } ?: return
        val r = el.getBoundingClientRect()
        pointerX = r.x + r.width / 2f
        pointerY = r.y + r.height / 2f
        screen.root.input.mouseMove(pointerX, pointerY) // later move: steps start here
    }

    private fun shot(name: String) {
        val mc = Minecraft.getInstance()
        Screenshot.grab(mc.gameDirectory, name, mainRenderTarget(mc), 1) { msg -> Log.info("GuiLib dev automation: ${msg.string}") }
    }

    /** `mc.getMainRenderTarget()` (26.1) or `mc.gameRenderer.mainRenderTarget()` (26.2); this source set isn't preprocessed. */
    private fun mainRenderTarget(mc: Minecraft): RenderTarget {
        runCatching { return Minecraft::class.java.getMethod("getMainRenderTarget").invoke(mc) as RenderTarget }
        val renderer = Minecraft::class.java.getField("gameRenderer").get(mc)
        return renderer.javaClass.getMethod("mainRenderTarget").invoke(renderer) as RenderTarget
    }
}
