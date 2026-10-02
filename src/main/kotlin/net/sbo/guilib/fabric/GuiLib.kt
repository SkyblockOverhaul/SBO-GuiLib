package net.sbo.guilib.fabric

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.dom.Clipboard
import net.sbo.guilib.core.dom.Document
import net.sbo.guilib.core.dom.ComponentType
import net.sbo.guilib.core.dom.ReplacedContent
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.VNode
import net.sbo.guilib.core.dsl.NodeBuilder
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.fabric.image.Images

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

    /** The screen that is currently open (works on every supported Minecraft version). */
    fun currentScreen(): net.minecraft.client.gui.screens.Screen? {
        val mc = Minecraft.getInstance()
        //#if MC >= 26.2
        //$$ return mc.gui.screen()
        //#else
        return mc.screen
        //#endif
    }

    /**
     * The document of the open GuiLib screen, or `null` if none is open. Lets code outside the UI change it, e.g.
     * `GuiLib.currentDocument()?.body?.classList?.toggle("font-mc", enabled)` after a config change (on the render
     * thread; use [runOnUi] from elsewhere). Inside components use `useDocument()` / `useBodyClass()`.
     */
    fun currentDocument(): Document? = (currentScreen() as? GuiLibScreen)?.root?.document

    /** Handles a chat click event the way vanilla screens do (used by [text]). */
    internal fun handleClickEvent(event: net.minecraft.network.chat.ClickEvent) {
        (currentScreen() as? GuiLibScreen)?.handleClickEvent(event)
    }

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

    /** Shown for images that failed to load (like a browser's broken-image icon, but empty). */
    private object BrokenImage : ReplacedContent {
        override val width = 16f
        override val height = 16f
    }

    private object ItemContent : ReplacedContent {
        override val width = 16f
        override val height = 16f
    }

    private object PlayerHeadContent : ReplacedContent {
        override val width = 16f
        override val height = 16f
    }

    private object EntityContent : ReplacedContent {
        override val width = 48f
        override val height = 72f
    }

    /** The system clipboard, through Minecraft (GLFW, which only works on the render thread). */
    private object SystemClipboard : Clipboard {
        override fun get(): String = Minecraft.getInstance().keyboardHandler.clipboard
        override fun set(text: String) {
            val mc = Minecraft.getInstance()
            if (mc.isSameThread) mc.keyboardHandler.clipboard = text else mc.execute { mc.keyboardHandler.clipboard = text }
        }
    }

    /** Wires backend services into a new UI (clipboard, replaced content for `<item>`/`<entity>`/`<player-head>`/`<img>`). */
    internal fun initDocument(root: UiRoot) {
        root.document.clipboard = SystemClipboard
        root.document.elementInitializer = { el ->
            when (el.tagName) {
                "item" -> if (el.replaced == null) el.replaced = ItemContent
                "entity" -> if (el.replaced == null) el.replaced = EntityContent
                "player-head" -> if (el.replaced == null) el.replaced = PlayerHeadContent
                "img" -> {
                    val src = el.getAttribute("src") as? String
                    val entry = src?.let { Images.entry(it) }
                    val next = entry ?: BrokenImage
                    if (el.replaced !== next) el.replaced = next
                }
            }
        }
    }
}
