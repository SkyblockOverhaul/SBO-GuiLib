package net.sbo.guilib.fabric

import net.sbo.guilib.core.FrameStats
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.world.item.ItemStack
import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.dom.VNode
import net.sbo.guilib.core.dom.VProvider
import net.sbo.guilib.fabric.font.FontManager
import net.sbo.guilib.fabric.input.Cursors
import net.sbo.guilib.fabric.input.Keys
import net.sbo.guilib.fabric.render.CommandRenderer
import net.sbo.guilib.fabric.resources.Stylesheets
import org.lwjgl.glfw.GLFW

/**
 * A Minecraft [Screen] hosting a GuiLib UI. Usually created through [GuiLib.open].
 *
 * @param content the root node, e.g. `VComponent(App, Unit, null)` (see [GuiLib.open] for the convenient forms)
 * @param stylesheets resource locations like `"mymod:ui/main.css"` (loaded from `assets/mymod/ui/main.css`)
 * @param scale the screen's own GUI scale (physical pixels per CSS px, fractions allowed), or `null` for Minecraft's;
 *   changeable later with `useScreenScale()` / `root.document.scale`
 */
open class GuiLibScreen(
    title: Component,
    private val content: VNode,
    private val stylesheets: List<String> = emptyList(),
    /** Draw Minecraft's default blurred/dimmed background behind the UI. */
    private val vanillaBackground: Boolean = true,
    private val pauseGame: Boolean = false,
    scale: Float? = null,
) : Screen(title) {

    val root: UiRoot = UiRoot(FontManager, Stylesheets.loadAll(stylesheets))
    private var mounted = false

    init {
        root.document.scale = scale
        GuiLib.initDocument(root)
        Stylesheets.watch(this)
    }

    /** Reloads all stylesheets (used by hot reload and `/guilib reload`). */
    fun reloadStylesheets() {
        root.document.setStylesheets(Stylesheets.loadAll(stylesheets))
    }

    /** The translator the UI was last rendered with; a new one (language change, resource reload) re-renders. */
    private var translator: Translator? = null

    override fun init() {
        if (!mounted) {
            renderContent()
            mounted = true
        }
    }

    private fun renderContent() {
        val t = Translator.current()
        translator = t
        root.render(VProvider(TranslatorContext, t, listOf(content), null))
    }

    /** Handles a chat [ClickEvent] (from [text]) like vanilla screens: links ask for confirmation, commands run … */
    internal fun handleClickEvent(event: ClickEvent) = defaultHandleGameClickEvent(event, minecraft, this)

    override fun extractBackground(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        if (vanillaBackground) super.extractBackground(ctx, mouseX, mouseY, delta)
    }

    // ---- own GUI scale ----------------------------------------------------------------------------------------
    // With `document.scale` set, the document lives in its own coordinate space: one CSS px = `scale` physical pixels.
    // It is drawn under an extra pose scale (scale / Minecraft's), mouse positions are divided by the same factor, and
    // text and SVGs are rasterized for the new pixel size (FontManager.activeScale).

    private val mcScale get() = minecraft.window.guiScale.toFloat().coerceAtLeast(1f)

    /** Physical pixels per CSS px for this screen. */
    private fun scale(): Float = root.document.scale?.coerceIn(0.25f, 16f) ?: mcScale

    /** Factor from this document's px to Minecraft's GUI px. */
    private fun factor(): Float = scale() / mcScale

    private inline fun <T> scaled(block: () -> T): T {
        val own = root.document.scale ?: return block()
        val before = FontManager.activeScale
        FontManager.activeScale = own.coerceIn(0.25f, 16f)
        try {
            return block()
        } finally {
            FontManager.activeScale = before
        }
    }

    private fun docX(x: Double) = (x / factor()).toFloat()
    private fun docY(y: Double) = (y / factor()).toFloat()

    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val s = scale()
        root.document.resolution = s
        if (mounted && Translator.current() !== translator) renderContent()
        scaled {
            val t0 = System.nanoTime()
            if (root.document.scale == null) {
                val commands = root.frame(width.toFloat(), height.toFloat())
                val t1 = System.nanoTime()
                CommandRenderer.draw(ctx, commands, mouseX, mouseY)
                FrameStats.frame(t1 - t0, System.nanoTime() - t1)
            } else {
                val k = s / mcScale
                val commands = root.frame(minecraft.window.width / s, minecraft.window.height / s)
                val t1 = System.nanoTime()
                val pose = ctx.pose()
                pose.pushMatrix()
                pose.scale(k, k)
                CommandRenderer.draw(ctx, commands, (mouseX / k).toInt(), (mouseY / k).toInt())
                pose.popMatrix()
                FrameStats.frame(t1 - t0, System.nanoTime() - t1)
            }
        }
        Cursors.of(root.input.cursor)?.let(ctx::requestCursor)
        hoverTooltip(ctx, mouseX, mouseY)
    }

    /**
     * Item / entity tooltips of hovered chat text ([text]) and of `item(…, tooltip = true)` icons, drawn by Minecraft
     * on top of everything like in chat and inventories.
     */
    private fun hoverTooltip(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        (root.input.hoveredItemTooltip() as? ItemStack)?.takeUnless { it.isEmpty }?.let {
            ctx.setTooltipForNextFrame(font, it, mouseX, mouseY)
            return
        }
        when (val hover = root.input.hoveredAttribute(MC_HOVER_ATTR)) {
            is HoverEvent.ShowItem -> ctx.setTooltipForNextFrame(font, hover.item().create(), mouseX, mouseY)
            is HoverEvent.ShowEntity -> if (minecraft.options.advancedItemTooltips) {
                ctx.setComponentTooltipForNextFrame(font, hover.entity().getTooltipLines(), mouseX, mouseY)
            }
        }
    }

    // Input handlers may measure text (caret placement), so they run with the screen's scale active too.

    override fun mouseMoved(x: Double, y: Double) = scaled {
        root.input.mouseMove(docX(x), docY(y))
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean = scaled {
        root.input.mouseDown(docX(click.x()), docY(click.y()), domButton(click.button()), Keys.modifiers(click.modifiers()))
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean = scaled {
        root.input.mouseUp(docX(click.x()), docY(click.y()), domButton(click.button()), Keys.modifiers(click.modifiers()))
    }

    override fun mouseDragged(click: MouseButtonEvent, deltaX: Double, deltaY: Double): Boolean = scaled {
        root.input.mouseMove(docX(click.x()), docY(click.y()), Keys.modifiers(click.modifiers()))
        true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean = scaled {
        root.input.wheel(
            docX(mouseX), docY(mouseY), (-horizontal * SCROLL_STEP).toFloat(), (-vertical * SCROLL_STEP).toFloat(),
            Keys.currentModifiers(), // Minecraft passes no modifiers with the wheel; Shift + wheel scrolls sideways
        )
    }

    override fun keyPressed(keyInput: KeyEvent): Boolean {
        val mods = Keys.modifiers(keyInput.modifiers())
        val key = Keys.keyName(keyInput.key(), keyInput.scancode(), mods)
        if (scaled { root.input.keyDown(key, keyInput.key(), mods) }) return true
        return super.keyPressed(keyInput) // Escape closes the screen
    }

    override fun keyReleased(keyInput: KeyEvent): Boolean = scaled {
        val mods = Keys.modifiers(keyInput.modifiers())
        root.input.keyUp(Keys.keyName(keyInput.key(), keyInput.scancode(), mods), keyInput.key(), mods)
    }

    override fun charTyped(input: CharacterEvent): Boolean = scaled { root.input.charTyped(input.codepointAsString()) }

    override fun isPauseScreen() = pauseGame

    override fun removed() {
        root.document.unmount()
        mounted = false
        Stylesheets.unwatch(this)
        super.removed()
    }

    private fun domButton(glfw: Int) = when (glfw) {
        GLFW.GLFW_MOUSE_BUTTON_LEFT -> 0
        GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> 1
        GLFW.GLFW_MOUSE_BUTTON_RIGHT -> 2
        else -> glfw
    }

    companion object {
        /** Pixels scrolled per mouse wheel notch. */
        var SCROLL_STEP = 20.0
    }
}
