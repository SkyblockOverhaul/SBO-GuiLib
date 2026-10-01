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
            steps += Step(10) { shot("guilib-${i + 1}-${s.substringAfterLast('.').lowercase().replace('#', '-')}.png") }
        }
        steps += Step(20) { Minecraft.getInstance().stop() }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!started) {
                if (world != null && worldRequested) {
                    val mc = Minecraft.getInstance()
                    if (mc.level == null || mc.player == null || GuiLib.currentScreen() != null) return@register
                    started = true
                    wait = 60 // let chunks and skins load
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
        fun find(sel: String) = root.document.body.querySelector(sel)
        when (verb) {
            "click" -> find(arg)?.getBoundingClientRect()?.let { r ->
                root.input.mouseDown(r.x + 2f, r.y + r.height / 2f, 0)
                root.input.mouseUp(r.x + 2f, r.y + r.height / 2f, 0)
            }
            "hover" -> hover(arg)
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

    private var pointerX = 0f
    private var pointerY = 0f

    private fun unescape(s: String) = Regex("""U\+([0-9a-fA-F]{4,6})""").replace(s) { String(Character.toChars(it.groupValues[1].toInt(16))) }

    /** Moves the virtual mouse over the first element matching [selector] (for :hover screenshots). */
    private fun hover(selector: String?) {
        val screen = GuiLib.currentScreen() as? GuiLibScreen ?: return
        val el = selector?.let { screen.root.document.body.querySelector(it) } ?: return
        val r = el.getBoundingClientRect()
        screen.root.input.mouseMove(r.x + r.width / 2f, r.y + r.height / 2f)
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
