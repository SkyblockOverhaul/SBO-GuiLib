package net.sbo.guilib.core.controls

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.ComponentScope
import net.sbo.guilib.core.dsl.numberInput
import net.sbo.guilib.core.dsl.rangeSlider
import net.sbo.guilib.core.dsl.slider
import net.sbo.guilib.core.dsl.switch
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SliderTest {
    // Minimal styles so the parts have sizes (the real ones live in ua.css): a 110px slider whose rail is 100px wide.
    private val ua = """
        div { display: block } span { display: inline } label { display: inline-flex }
        .guilib-slider { position: relative; width: 110px; height: 10px }
        .guilib-slider-track { position: absolute; left: 0; right: 0; top: 3px; height: 4px }
        .guilib-slider-rail { position: absolute; left: 5px; right: 5px; top: 0; bottom: 0; pointer-events: none }
        .guilib-slider-fill { position: absolute; left: -5px; top: 3px; height: 4px }
        .guilib-slider-thumb { position: absolute; top: 0; width: 10px; height: 10px; margin-left: -5px }
        .guilib-switch-track { display: block; width: 20px; height: 10px }
        input { display: inline-block; position: relative; overflow: hidden; white-space: pre; width: 40px }
        .guilib-number { display: flex; width: 80px }
        button { display: inline-block; width: 10px; height: 10px }
    """.trimIndent()

    private fun ui(content: ComponentScope.() -> Unit): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT)), clock = { 0L })
        root.render(VComponent(component("T") { content() }, Unit, null))
        root.frame(300f, 200f)
        return root
    }

    private fun UiRoot.el(sel: String): Element = document.body.querySelector(sel)!!

    /** Screen x of [fraction] along the rail of the first slider. */
    private fun UiRoot.railX(fraction: Float): Float {
        val r = el(".guilib-slider-rail").getBoundingClientRect()
        return r.x + r.width * fraction
    }

    private fun UiRoot.sliderY() = el(".guilib-slider").getBoundingClientRect().let { it.y + it.height / 2f }

    private fun UiRoot.key(key: String) {
        input.keyDown(key, 0)
        frame(300f, 200f)
    }

    @Test
    fun snapping() {
        assertEquals(30f, snapSliderValue(31.4f, 0f, 100f, 5f))
        assertEquals(0.3f, snapSliderValue(0.29f, 0f, 1f, 0.05f))
        assertEquals(9f, snapSliderValue(10f, 0f, 10f, 3f))
        assertEquals(-5f, snapSliderValue(-100f, -5f, 5f, 1f))
        assertEquals(1.2345f, snapSliderValue(1.2345f, 0f, 2f, 0f))
        assertEquals("0.25", formatSliderValue(0.25f, 0.05f))
        assertEquals("40", formatSliderValue(40f, 1f))
    }

    @Test
    fun clickAndDragSetTheValueAndReleaseReportsTheEnd() {
        var value = 0f
        var changes = 0
        var ended: Float? = null
        val root = ui {
            var v by useState(0f)
            value = v
            slider(value = v, onChange = { v = it; changes++ }, max = 100f, step = 10f, onChangeEnd = { ended = it })
        }
        val y = root.sliderY()
        root.input.mouseDown(root.railX(0.5f), y, 0)
        root.frame(300f, 200f)
        assertEquals(50f, value)
        assertNull(ended)
        assertTrue(root.el(".guilib-slider").classList.contains("dragging"))
        // Dragging keeps working outside the slider and clamps to the range.
        root.input.mouseMove(root.railX(0.73f), y + 40f)
        root.frame(300f, 200f)
        assertEquals(70f, value)
        root.input.mouseMove(root.railX(1.5f), y)
        root.frame(300f, 200f)
        assertEquals(100f, value)
        // Moving within the same step reports nothing new.
        val before = changes
        root.input.mouseMove(root.railX(0.98f), y)
        root.frame(300f, 200f)
        assertEquals(before, changes)
        root.input.mouseUp(root.railX(0.98f), y, 0)
        root.frame(300f, 200f)
        assertEquals(100f, ended)
        assertFalse(root.el(".guilib-slider").classList.contains("dragging"))
        // The fill ends at the thumb's center.
        val thumb = root.el(".guilib-slider-thumb").getBoundingClientRect()
        val fill = root.el(".guilib-slider-fill").getBoundingClientRect()
        assertEquals(thumb.x + thumb.width / 2f, fill.right, 0.01f)
        // Moving after the release changes nothing.
        root.input.mouseMove(root.railX(0.1f), y)
        root.frame(300f, 200f)
        assertEquals(100f, value)
    }

    @Test
    fun keyboardMovesByStepsAndJumpsToTheEnds() {
        var value = 0
        var ended = -1
        val root = ui {
            var v by useState(50)
            value = v
            slider(value = v, onChange = { v = it }, min = 0, max = 200, step = 5, onChangeEnd = { ended = it })
        }
        root.input.mouseDown(root.railX(0.25f), root.sliderY(), 0)
        root.input.mouseUp(root.railX(0.25f), root.sliderY(), 0)
        root.frame(300f, 200f)
        assertEquals(50, value)
        assertTrue(root.document.focusedElement?.classList?.contains("guilib-slider") == true)
        root.key("ArrowRight")
        assertEquals(55, value)
        assertEquals(55, ended)
        root.key("ArrowDown")
        root.key("ArrowDown")
        assertEquals(45, value)
        root.key("PageUp")
        assertEquals(65, value)
        root.key("End")
        assertEquals(200, value)
        root.key("ArrowRight")
        assertEquals(200, value)
        root.key("Home")
        assertEquals(0, value)
    }

    @Test
    fun showValueUsesTheFormatter() {
        val root = ui {
            var v by useState(0.25f)
            slider(value = v, onChange = { v = it }, min = 0f, max = 1f, step = 0.05f, showValue = true)
            slider(value = 40, format = { "$it%" }, showValue = true)
        }
        val labels = root.document.body.querySelectorAll(".guilib-slider-value").map { (it.children.single() as net.sbo.guilib.core.dom.TextNode).data }
        assertEquals(listOf("0.25", "40%"), labels)
    }

    @Test
    fun disabledSliderIgnoresInput() {
        var changed = false
        val root = ui { slider(value = 10f, onChange = { changed = true }, disabled = true) }
        root.input.mouseDown(root.railX(0.8f), root.sliderY(), 0)
        root.input.mouseUp(root.railX(0.8f), root.sliderY(), 0)
        root.frame(300f, 200f)
        root.key("ArrowRight")
        assertFalse(changed)
    }

    @Test
    fun switchTogglesByClickAndKeyboard() {
        var on = false
        var disabledChanged = false
        val root = ui {
            var v by useState(false)
            on = v
            switch(checked = v, onChange = { v = it.checked }, label = "Sounds")
            switch(checked = false, onChange = { disabledChanged = true }, disabled = true, className = "off")
        }
        val sw = root.el(".guilib-switch")
        val r = sw.getBoundingClientRect()
        root.input.mouseDown(r.x + 2f, r.y + 2f, 0)
        root.input.mouseUp(r.x + 2f, r.y + 2f, 0)
        root.frame(300f, 200f)
        assertTrue(on)
        assertTrue(root.el(".guilib-switch").classList.contains("checked"))
        root.key(" ")
        assertFalse(on)
        root.key("Enter")
        assertTrue(on)

        val off = root.el(".guilib-switch.off").getBoundingClientRect()
        root.input.mouseDown(off.x + 2f, off.y + 2f, 0)
        root.input.mouseUp(off.x + 2f, off.y + 2f, 0)
        root.frame(300f, 200f)
        assertFalse(disabledChanged)
    }

    @Test
    fun rangeSliderMovesTheNearerThumbAndThumbsDontCross() {
        var range = 0 to 0
        var ended: Pair<Int, Int>? = null
        val root = ui {
            var lo by useState(20)
            var hi by useState(80)
            range = lo to hi
            rangeSlider(low = lo, high = hi, onChange = { a, b -> lo = a; hi = b }, onChangeEnd = { a, b -> ended = a to b }, step = 10)
        }
        val y = root.sliderY()
        // Near the low thumb: drags it; it stops at the high thumb.
        root.input.mouseDown(root.railX(0.32f), y, 0)
        root.frame(300f, 200f)
        assertEquals(30 to 80, range)
        assertTrue(root.document.focusedElement?.classList?.contains("low") == true)
        root.input.mouseMove(root.railX(0.95f), y)
        root.frame(300f, 200f)
        assertEquals(80 to 80, range)
        root.input.mouseUp(root.railX(0.95f), y, 0)
        root.frame(300f, 200f)
        assertEquals(80 to 80, ended)
        // Both on the same spot: pressing right of them takes the high thumb.
        root.input.mouseDown(root.railX(0.9f), y, 0)
        root.input.mouseUp(root.railX(0.9f), y, 0)
        root.frame(300f, 200f)
        assertEquals(80 to 90, range)
        assertTrue(root.document.focusedElement?.classList?.contains("high") == true)
        root.key("End")
        assertEquals(80 to 100, range)
        root.document.focus(root.el(".guilib-slider-thumb.low"))
        root.key("Home")
        assertEquals(0 to 100, range)
        // The fill spans the range.
        val fill = root.el(".guilib-slider-fill").getBoundingClientRect()
        assertEquals(root.railX(0f), fill.x, 0.01f)
        assertEquals(root.railX(1f), fill.right, 0.01f)
    }

    @Test
    fun numberInputClampsStepsAndUsesTheWheel() {
        var value = 0
        val root = ui {
            var v by useState(3)
            value = v
            numberInput(value = v, onChange = { v = it }, min = 1, max = 5)
        }
        val field = root.el(".guilib-number-input")
        fun text() = (field.control as InputControl).text
        root.click(root.el(".guilib-number-inc"))
        assertEquals(4, value)
        root.click(root.el(".guilib-number-inc"))
        root.click(root.el(".guilib-number-inc"))
        assertEquals(5, value)
        assertTrue(root.el(".guilib-number-inc").disabled)
        val r = field.getBoundingClientRect()
        root.input.wheel(r.x + 2f, r.y + 2f, 0f, -1f)
        root.frame(300f, 200f)
        assertEquals(5, value)
        root.input.wheel(r.x + 2f, r.y + 2f, 0f, 1f)
        root.frame(300f, 200f)
        assertEquals(4, value)
        // Typing out of range is clamped on Enter; in range it's reported right away.
        root.click(field)
        root.key("ArrowDown")
        assertEquals(3, value)
        root.input.keyDown("a", 65, net.sbo.guilib.core.event.Modifiers(ctrl = true))
        root.input.charTyped("9")
        root.frame(300f, 200f)
        assertEquals(3, value)
        assertEquals("9", text())
        root.key("Enter")
        assertEquals(5, value)
        assertEquals("5", text())
        root.input.keyDown("a", 65, net.sbo.guilib.core.event.Modifiers(ctrl = true))
        root.input.charTyped("2")
        root.frame(300f, 200f)
        assertEquals(2, value)
    }

    @Test
    fun nullableNumberInputCanBeEmpty() {
        var value: Int? = 7
        val root = ui {
            var v by useState<Int?>(7)
            value = v
            numberInput(value = v, onChange = { v = it }, allowEmpty = true, min = 1, max = 5, placeholder = "any")
        }
        val field = root.el(".guilib-number-input")
        fun text() = (field.control as InputControl).text
        // Clearing the field reports null right away and keeps it empty after Enter.
        root.click(field)
        root.input.keyDown("a", 65, net.sbo.guilib.core.event.Modifiers(ctrl = true))
        root.key("Backspace")
        assertNull(value)
        root.key("Enter")
        assertNull(value)
        assertEquals("", text())
        assertTrue(root.el(".guilib-number-dec").disabled)
        // + on an empty field starts at 1 (here also min).
        root.click(root.el(".guilib-number-inc"))
        assertEquals(1, value)
        root.click(root.el(".guilib-number-inc"))
        assertEquals(2, value)
        assertEquals("2", text())
    }

    @Test
    fun nullableNumberInputStepsFromAndBackToEmpty() {
        for (min in listOf(0, 1)) {
            var value: Int? = null
            val changes = ArrayList<Int?>()
            val root = ui {
                var v by useState<Int?>(null)
                value = v
                numberInput(value = v, onChange = { v = it; changes += it }, allowEmpty = true, min = min, max = 9)
            }
            val dec = root.el(".guilib-number-dec")
            val inc = root.el(".guilib-number-inc")
            // − on an empty field does nothing (the button is disabled).
            assertTrue(dec.disabled, "min=$min")
            root.click(dec)
            assertEquals(emptyList<Int?>(), changes, "min=$min")
            // + on an empty field starts at 1 (one step up from empty), never below min.
            root.click(inc)
            assertEquals(1, value, "min=$min")
            // − at min clears the field instead of being disabled.
            if (min == 0) root.click(dec)
            assertEquals(0.coerceAtLeast(min), value ?: -1, "min=$min")
            assertFalse(dec.disabled, "min=$min")
            root.click(dec)
            assertNull(value, "min=$min")
            assertTrue(dec.disabled, "min=$min")
        }
    }

    @Test
    fun nonNullNumberInputRestoresTheValueWhenCleared() {
        var value = 3
        val root = ui {
            var v by useState(3)
            value = v
            numberInput(value = v, onChange = { v = it }, min = 1, max = 5)
        }
        val field = root.el(".guilib-number-input")
        root.click(field)
        root.input.keyDown("a", 65, net.sbo.guilib.core.event.Modifiers(ctrl = true))
        root.key("Backspace")
        root.key("Enter")
        assertEquals(3, value)
        assertEquals("3", (field.control as InputControl).text)
    }

    private fun UiRoot.click(el: Element) {
        val r = el.getBoundingClientRect()
        input.mouseDown(r.x + 2f, r.y + 2f, 0)
        input.mouseUp(r.x + 2f, r.y + 2f, 0)
        frame(300f, 200f)
    }
}
