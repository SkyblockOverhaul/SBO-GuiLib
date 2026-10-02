package net.sbo.guilib.core.event

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Cursor
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CursorTest {
    private val css = """
        div { display: block; height: 10px }
        .grab { cursor: grab }
        .grab:active { cursor: grabbing }
        .link { cursor: pointer }
        .resize { cursor: col-resize }
        .hidden { cursor: none }
    """.trimIndent()

    private fun ui(): UiRoot {
        val app = component("T") {
            div(className = "grab") {}
            div(className = "link") {}
            div(className = "resize") {}
            div {}
            div(className = "hidden") {}
        }
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(css, "t", Origin.USER_AGENT)))
        root.render(VComponent(app, Unit, null))
        root.frame(100f, 100f)
        return root
    }

    @Test
    fun hoverActiveAndNewKeywords() {
        val root = ui()
        root.input.mouseMove(5f, 5f); root.frame(100f, 100f)
        assertEquals(Cursor.GRAB, root.input.cursor)
        root.input.mouseDown(5f, 5f, 0); root.frame(100f, 100f)
        assertEquals(Cursor.GRABBING, root.input.cursor)
        root.input.mouseUp(5f, 5f, 0); root.frame(100f, 100f)
        root.input.mouseMove(5f, 25f); root.frame(100f, 100f)
        assertEquals(Cursor.COL_RESIZE, root.input.cursor)
    }

    @Test
    fun dragCursorsStayWhileTheButtonIsHeld() {
        val root = ui()
        root.input.mouseMove(5f, 5f)
        root.input.mouseDown(5f, 5f, 0); root.frame(100f, 100f)
        root.input.mouseMove(5f, 35f); root.frame(100f, 100f) // over the plain div
        assertEquals(Cursor.GRABBING, root.input.cursor)
        root.input.mouseUp(5f, 35f, 0); root.frame(100f, 100f)
        assertEquals(Cursor.AUTO, root.input.cursor)

        // A pointer cursor is not a drag cursor: it follows the mouse as usual.
        root.input.mouseDown(5f, 15f, 0); root.frame(100f, 100f)
        root.input.mouseMove(5f, 35f); root.frame(100f, 100f)
        assertEquals(Cursor.AUTO, root.input.cursor)
    }

    @Test
    fun noneHidesTheCursor() {
        val root = ui()
        root.input.mouseMove(5f, 45f); root.frame(100f, 100f)
        assertEquals(Cursor.NONE, root.input.cursor)
    }
}
