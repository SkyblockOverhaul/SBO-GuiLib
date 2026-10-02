package net.sbo.guilib.core

import net.sbo.guilib.core.controls.Controls
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.Document
import net.sbo.guilib.core.dom.VNode
import net.sbo.guilib.core.event.InteractionController
import net.sbo.guilib.core.layout.LetterSpacing
import net.sbo.guilib.core.layout.TextMeasurer
import net.sbo.guilib.core.paint.PaintCommand
import net.sbo.guilib.core.paint.Painter

/**
 * Everything a backend needs to host a UI: the [document], the [painter] and the [input] controller.
 * Call [frame] once per frame and draw the returned commands.
 */
class UiRoot(measurer: TextMeasurer, stylesheets: List<Stylesheet> = emptyList(), clock: () -> Long = System::currentTimeMillis) {
    private val measurer = LetterSpacing.wrap(measurer)
    val document = Document(this.measurer, stylesheets, clock)
    val painter = Painter(this.measurer)
    val input = InteractionController(document) { x, y -> painter.hitTest(x, y) }
    private var painted = false

    init {
        Controls.install(document, input)
    }

    fun render(vnode: VNode) = document.render(vnode)

    /** Updates state/style/layout and returns the paint commands for this frame. */
    fun frame(width: Float, height: Float): List<PaintCommand> {
        val changed = document.update(width, height)
        if (changed || !painted) {
            painter.paint(document.body)
            FrameStats.paint()
            painted = true
            // Content may have moved under the mouse.
            input.refreshHover()
            if (document.update(width, height, animate = false)) {
                painter.paint(document.body)
                FrameStats.paint()
            }
        }
        return painter.commands
    }
}
