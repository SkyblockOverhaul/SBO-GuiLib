package net.sbo.guilib.fabric

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.dom.ComponentType
import net.sbo.guilib.core.dom.ReplacedContent
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.VNode
import net.sbo.guilib.core.dsl.NodeBuilder
import net.sbo.guilib.core.dom.component

/**
 * Entry point for mods:
 *
 * ```kotlin
 * val App = component("App") { div(className = "panel") { +"Hello" } }
 * GuiLib.open(App, stylesheets = listOf("mymod:ui/app.css"))
 * ```
 */
object GuiLib {

    /** Opens [app] as a screen. Must be called on the client thread (use [runOnUi] otherwise). */
    fun open(
        app: ComponentType<Unit>,
        stylesheets: List<String> = emptyList(),
        title: String = app.name,
        vanillaBackground: Boolean = true,
        pauseGame: Boolean = false,
    ): GuiLibScreen {
        val screen = GuiLibScreen(Component.literal(title), VComponent(app, Unit, null), stylesheets, vanillaBackground, pauseGame)
        setScreen(screen)
        return screen
    }

    /** Opens an inline UI: `GuiLib.open(listOf("mymod:ui.css")) { div { +"Hi" } }`. */
    fun open(stylesheets: List<String> = emptyList(), title: String = "GuiLib", content: NodeBuilder.() -> Unit): GuiLibScreen =
        open(component(title) { content() }, stylesheets, title)

    /** Creates the screen without opening it (e.g. to return it from a config button). */
    fun screen(app: ComponentType<Unit>, stylesheets: List<String> = emptyList(), title: String = app.name): GuiLibScreen =
        GuiLibScreen(Component.literal(title), VComponent(app, Unit, null), stylesheets)

    fun screen(content: VNode, stylesheets: List<String> = emptyList(), title: String = "GuiLib"): GuiLibScreen =
        GuiLibScreen(Component.literal(title), content, stylesheets)

    /** Closes the current screen. */
    fun close() = setScreen(null)

    /** Runs [block] on the client (render) thread. State setters are already thread-safe; use this for other work. */
    fun runOnUi(block: () -> Unit) = Minecraft.getInstance().execute(block)

    private fun setScreen(screen: net.minecraft.client.gui.screens.Screen?) {
        val mc = Minecraft.getInstance()
        //#if MC >= 26.2
        //$$ mc.gui.setScreen(screen)
        //#else
        mc.setScreen(screen)
        //#endif
    }

    private object ItemContent : ReplacedContent {
        override val width = 16f
        override val height = 16f
    }

    /** Wires backend services into a new UI (replaced content for `<item>`/`<img>`, default actions of controls). */
    internal fun initDocument(root: UiRoot) {
        root.document.elementInitializer = { el ->
            when (el.tagName) {
                "item" -> if (el.replaced == null) el.replaced = ItemContent
            }
        }
    }
}
