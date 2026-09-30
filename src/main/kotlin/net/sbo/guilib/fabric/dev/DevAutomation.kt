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
            steps += Step(20) { Showcase.open(s) }
            steps += Step(20) { hover(System.getProperty("guilib.dev.hover.$s")) }
            steps += Step(10) { shot("guilib-${i + 1}-${s.lowercase()}.png") }
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
                Log.error("GuiLib dev automation step failed: $e")
            }
            wait = steps.firstOrNull()?.ticks ?: 0
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
