package net.sbo.guilib.fabric.dev

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.sbo.guilib.core.Log
import net.sbo.guilib.fabric.GuiLibScreen
import net.sbo.guilib.fabric.showcase.Showcase

/**
 * Development helper for visual checks without manual clicking. Start the client with
 * `-Dguilib.dev.shots=Buttons,Layout` (or `all`): once the title screen is up, each showcase section is opened,
 * optionally hovered, captured to `run/screenshots/guilib-*.png`, and the game quits afterwards.
 */
object DevAutomation {
    private class Step(val ticks: Int, val action: () -> Unit)

    private val steps = ArrayDeque<Step>()
    private var wait = 0
    private var started = false

    fun init() {
        val spec = System.getProperty("guilib.dev.shots") ?: return
        val sections = if (spec == "all") Showcase.SECTIONS else spec.split(',').map { it.trim() }
        Log.info("GuiLib dev automation: capturing ${sections.joinToString()}")
        sections.forEachIndexed { i, s ->
            // "Forms#2" opens the Forms section again with its own hover/script properties.
            steps += Step(20) { Showcase.open(s.substringBefore('#')) }
            steps += Step(20) { hover(System.getProperty("guilib.dev.hover.$s")) }
            // Optional script: -Dguilib.dev.script.Forms="click:input;type:Steve;click:select"
            System.getProperty("guilib.dev.script.$s")?.split(';')?.filter { it.isNotBlank() }?.forEach { action ->
                steps += Step(8) { runAction(action) }
            }
            steps += Step(10) { shot("guilib-${i + 1}-${s.lowercase().replace('#', '-')}.png") }
        }
        steps += Step(20) { Minecraft.getInstance().stop() }

        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (!started) {
                if (currentScreen(mc) !is TitleScreen) return@register
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

    private fun runAction(action: String) {
        val screen = currentScreen(Minecraft.getInstance()) as? GuiLibScreen ?: return
        val root = screen.root
        val (verb, arg) = action.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        fun find(sel: String) = root.document.body.querySelector(sel)
        when (verb) {
            "click" -> find(arg)?.getBoundingClientRect()?.let { r ->
                root.input.mouseDown(r.x + 2f, r.y + r.height / 2f, 0)
                root.input.mouseUp(r.x + 2f, r.y + r.height / 2f, 0)
            }
            "hover" -> hover(arg)
            "type" -> arg.forEach { root.input.charTyped(it.toString()) }
            "key" -> root.input.keyDown(arg, 0)
        }
    }

    /** Moves the virtual mouse over the first element matching [selector] (for :hover screenshots). */
    private fun hover(selector: String?) {
        val screen = currentScreen(Minecraft.getInstance()) as? GuiLibScreen ?: return
        val el = selector?.let { screen.root.document.body.querySelector(it) } ?: return
        val r = el.getBoundingClientRect()
        screen.root.input.mouseMove(r.x + r.width / 2f, r.y + r.height / 2f)
    }

    private fun shot(name: String) {
        val mc = Minecraft.getInstance()
        //#if MC >= 26.2
        //$$ val target = mc.gameRenderer.mainRenderTarget()
        //#else
        val target = mc.mainRenderTarget
        //#endif
        Screenshot.grab(mc.gameDirectory, name, target, 1) { msg -> Log.info("GuiLib dev automation: ${msg.string}") }
    }

    private fun currentScreen(mc: Minecraft): Screen? {
        //#if MC >= 26.2
        //$$ return mc.gui.screen()
        //#else
        return mc.screen
        //#endif
    }
}
