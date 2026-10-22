package net.sbo.guilib.fabric

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.sbo.guilib.core.FrameStats
import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.ElementProps
import net.sbo.guilib.core.dom.Rect
import net.sbo.guilib.core.dom.Ref
import net.sbo.guilib.core.dom.VElement
import net.sbo.guilib.core.dom.VNode
import net.sbo.guilib.core.dom.VProvider
import net.sbo.guilib.core.dsl.NodeBuilder
import net.sbo.guilib.fabric.font.FontManager
import net.sbo.guilib.fabric.render.CommandRenderer
import net.sbo.guilib.fabric.resources.Stylesheets

/**
 * A GuiLib document drawn on the HUD (or over any screen) instead of in a screen of its own, for overlays and trackers:
 *
 * ```kotlin
 * val hud = GuiLibHud(listOf("mymod:css/hud.css"))
 * hud.render { div(className = "tracker") { +"Kills: $kills" } }   // again whenever the data changes
 * hud.draw(ctx, x, y, scale)                                          // every frame, from a HUD render callback
 * ```
 *
 * The content is laid out at its own size from the top left (it sits in a shrink-to-fit `.guilib-hud` box), [width]
 * and [height] are that size in GUI px at scale 1, so the mod can place it, keep it on the screen or hit-test it.
 * Paint commands are reused while nothing changes, so drawing it every frame costs little; re-render only when the
 * shown data changed. `<item>`, `<img>` and `<player-head>` work like in screens.
 *
 * It gets no mouse or keyboard input (use [bounds] to hit-test a part of it yourself), and its work is not counted in the metrics window of an open screen.
 * Call [unmount] when it is not needed anymore.
 */
class GuiLibHud(private val stylesheets: List<String> = emptyList()) {
    val root: UiRoot = UiRoot(FontManager, Stylesheets.loadAll(stylesheets))
    private val box = Ref<Element?>(null)

    init {
        GuiLib.initDocument(root)
    }

    /** Shows [content], replacing what was shown before (state of components that stay is kept). */
    fun render(content: VNode) = render(listOf(content))

    /** Shows the built content: `hud.render { div { +"Hi" } }`. */
    fun render(content: NodeBuilder.() -> Unit) = render(NodeBuilder().apply(content).nodes)

    private fun render(content: List<VNode>) {
        val wrapped = VElement("div", ElementProps(className = "guilib-hud", ref = box), content, null)
        root.render(VProvider(TranslatorContext, Translator.current(), listOf(wrapped), null))
    }

    /** Reloads the stylesheets, e.g. after a resource reload. */
    fun reloadStylesheets() {
        root.document.setStylesheets(Stylesheets.loadAll(stylesheets))
    }

    /** Width of the content in GUI px at scale 1, as of the last [draw]. */
    var width: Float = 0f
        private set

    /** Height of the content in GUI px at scale 1, as of the last [draw]. */
    var height: Float = 0f
        private set

    /**
     * Where the first element matching [selector] is, relative to the top left of the content in GUI px at scale 1,
     * as of the last [draw]; null if nothing matches. For hit-testing a part of the content, e.g. a button.
     */
    fun bounds(selector: String): Rect? {
        val element = box.current?.querySelector(selector) ?: return null
        val origin = box.current?.getBoundingClientRect() ?: return null
        val rect = element.getBoundingClientRect()
        return Rect(rect.x - origin.x, rect.y - origin.y, rect.width, rect.height)
    }

    /** Draws the content with its top left corner at GUI position ([x], [y]), [scale] times its size. */
    fun draw(ctx: GuiGraphicsExtractor, x: Float, y: Float, scale: Float = 1f) {
        val window = Minecraft.getInstance().window
        val mcScale = window.guiScale.toFloat().coerceAtLeast(1f)
        // Laid out in its own px, so text and SVGs are rasterized sharp at the drawn size
        val resolution = (mcScale * scale).coerceIn(0.25f, 16f)
        val before = FontManager.activeScale
        FontManager.activeScale = resolution
        try {
            FrameStats.uncounted {
                root.document.resolution = resolution
                val commands = root.frame(window.width / resolution, window.height / resolution)
                // Measured once per draw, callers may ask for the size several times per frame
                val rect = box.current?.getBoundingClientRect()
                width = rect?.width ?: 0f
                height = rect?.height ?: 0f
                val pose = ctx.pose()
                pose.pushMatrix()
                pose.translate(x, y)
                pose.scale(scale, scale)
                CommandRenderer.draw(ctx, commands)
                pose.popMatrix()
            }
        } finally {
            FontManager.activeScale = before
        }
    }

    /** Unmounts the content (runs effect cleanups). */
    fun unmount() = root.document.unmount()
}
