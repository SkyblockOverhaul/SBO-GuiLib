package net.sbo.guilib.core.controls

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Colors
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.ComponentScope
import net.sbo.guilib.core.dsl.colorInput
import net.sbo.guilib.core.dsl.colorPicker
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ColorPickerTest {
    // Minimal styles so the parts have sizes (the real ones live in ua.css).
    private val ua = """
        div { display: block } span { display: inline } button { display: inline-flex }
        input { display: inline-block; position: relative; overflow: hidden; white-space: pre; width: 60px }
        .guilib-cp-sv { position: relative; width: 100px; height: 50px }
        .guilib-cp-hue, .guilib-cp-alpha { position: relative; width: 100px; height: 10px }
        .guilib-cp-handle, .guilib-cp-thumb { position: absolute; width: 4px; height: 4px }
        #guilib-overlay { position: fixed; left: 0; top: 0; width: 100%; height: 100%; pointer-events: none; z-index: 100 }
        .guilib-portal { position: absolute; left: 0; top: 0; width: 100%; height: 100%; pointer-events: none }
        .guilib-portal > * { pointer-events: auto }
    """.trimIndent()

    private fun ui(content: ComponentScope.() -> Unit): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT)), clock = { 0L })
        root.render(VComponent(component("T") { content() }, Unit, null))
        root.frame(300f, 200f)
        return root
    }

    private fun UiRoot.el(sel: String): Element = document.body.querySelector(sel) ?: document.overlayRoot.querySelector(sel)!!

    private fun UiRoot.dragOn(el: Element, fx: Float, fy: Float) {
        val r = el.getBoundingClientRect()
        val x = r.x + r.width * fx
        val y = r.y + r.height * fy
        input.mouseDown(x, y, 0)
        input.mouseUp(x, y, 0)
        frame(300f, 200f)
    }

    @Test
    fun hsvRoundTrip() {
        for (c in listOf(0xFFFF0000.toInt(), 0xFF5B8DEF.toInt(), 0xFF00FF7F.toInt(), 0xFF808080.toInt(), 0x80FFFFFF.toInt())) {
            val hsv = Colors.argbToHsv(c)
            assertEquals(c, Colors.hsvToArgb(hsv[0], hsv[1], hsv[2], hsv[3]))
        }
        assertEquals("#5b8def", Colors.toHex(0xFF5B8DEF.toInt()))
        assertEquals("#5b8def80", Colors.toHex(0x805B8DEF.toInt()))
    }

    @Test
    fun clickingTheAreaAndHueSliderPicksColors() {
        var color = 0xFFFF0000.toInt()
        val root = ui {
            var c by useState(0xFFFF0000.toInt())
            color = c
            colorPicker(value = c, onChange = { c = it })
        }
        // Top-left of the area: white (saturation 0, value 1).
        root.dragOn(root.el(".guilib-cp-sv"), 0f, 0f)
        assertEquals(0xFFFFFFFF.toInt(), color)
        // Bottom: black.
        root.dragOn(root.el(".guilib-cp-sv"), 0.5f, 0.999f)
        assertEquals(0f, Colors.argbToHsv(color)[2], 0.02f)
        // Top-right + hue slider at 1/3 → pure green.
        root.dragOn(root.el(".guilib-cp-sv"), 0.999f, 0f)
        root.dragOn(root.el(".guilib-cp-hue"), 1f / 3f, 0.5f)
        val hsv = Colors.argbToHsv(color)
        assertEquals(120f, hsv[0], 2f)
        assertEquals(1f, hsv[1], 0.02f)
    }

    @Test
    fun draggingContinuesOutsideTheArea() {
        var color = 0xFF0000FF.toInt()
        val root = ui {
            var c by useState(0xFF0000FF.toInt())
            color = c
            colorPicker(value = c, onChange = { c = it })
        }
        val hue = root.el(".guilib-cp-hue").getBoundingClientRect()
        root.input.mouseDown(hue.x + 1f, hue.y + 2f, 0)
        root.input.mouseMove(hue.right + 50f, hue.y + 80f) // far outside → clamps to the end (hue 360 = red)
        root.input.mouseUp(hue.right + 50f, hue.y + 80f, 0)
        root.frame(300f, 200f)
        assertEquals(0xFFFF0000.toInt(), color)
    }

    @Test
    fun hexInputAndControlledValue() {
        var color = 0xFF000000.toInt()
        lateinit var set: (Int) -> Unit
        val root = ui {
            val (c, s) = useState(0xFF000000.toInt())
            color = c; set = s
            colorPicker(value = c, onChange = s)
        }
        val hex = root.el(".guilib-cp-hex")
        val r = hex.getBoundingClientRect()
        root.input.mouseDown(r.x + 1f, r.y + 1f, 0); root.input.mouseUp(r.x + 1f, r.y + 1f, 0)
        root.input.keyDown("a", 0, net.sbo.guilib.core.event.Modifiers(ctrl = true))
        for (ch in "#ff8800") root.input.charTyped(ch.toString())
        root.frame(300f, 200f)
        assertEquals(0xFFFF8800.toInt(), color)

        // Changing the value from outside moves the picker (handle position follows).
        set(0xFFFFFFFF.toInt()); root.frame(300f, 200f)
        val handle = root.el(".guilib-cp-handle").getBoundingClientRect()
        val area = root.el(".guilib-cp-sv").getBoundingClientRect()
        assertEquals(area.y, handle.y, 0.5f) // value 1 → top
    }

    @Test
    fun colorInputOpensPopover() {
        var color = 0xFF5B8DEF.toInt()
        val root = ui {
            var c by useState(0xFF5B8DEF.toInt())
            color = c
            colorInput(value = c, onChange = { c = it })
        }
        assertNull(root.document.overlayRoot.querySelector(".guilib-color-picker"))
        val btn = root.el(".guilib-color-input")
        val r = btn.getBoundingClientRect()
        root.input.mouseDown(r.x + 1f, r.y + 1f, 0); root.input.mouseUp(r.x + 1f, r.y + 1f, 0)
        root.frame(300f, 200f)
        assertNotNull(root.document.overlayRoot.querySelector(".guilib-color-picker"))
        root.dragOn(root.el(".guilib-cp-sv"), 0f, 0f)
        assertEquals(0xFFFFFFFF.toInt(), color)
        // Escape closes the popover.
        root.input.keyDown("Escape", 256); root.frame(300f, 200f)
        assertNull(root.document.overlayRoot.querySelector(".guilib-color-picker"))
    }
}
