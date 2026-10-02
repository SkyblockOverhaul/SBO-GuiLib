package net.sbo.guilib.core.anim

import net.sbo.guilib.core.css.CssParser
import net.sbo.guilib.core.css.Dim
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.css.TimingFunction
import net.sbo.guilib.core.dom.Document
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.ComponentScope
import net.sbo.guilib.core.dsl.classNames
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnimationTest {
    private var now = 0L

    private fun doc(css: String, content: ComponentScope.() -> Unit): Document {
        val d = Document(FakeMeasurer, listOf(Stylesheet.parse("div { display: block } span { display: inline }", "ua", Origin.USER_AGENT), Stylesheet.parse(css, "t.css")), clock = { now })
        d.render(VComponent(component("T") { content() }, Unit, null))
        d.update(200f, 100f)
        return d
    }

    private fun Document.at(ms: Long): Document {
        now = ms
        update(200f, 100f)
        return this
    }

    private fun Document.el(sel: String): Element = body.querySelector(sel)!!

    @Test
    fun timingFunctions() {
        assertEquals(0.5f, TimingFunction.LINEAR.ease(0.5f), 0.001f)
        assertEquals(0.8024f, TimingFunction.EASE.ease(0.5f), 0.01f)
        assertEquals(0f, TimingFunction.EASE_IN.ease(0f))
        assertEquals(1f, TimingFunction.EASE_OUT.ease(1f))
        assertEquals(0.5f, TimingFunction.Steps(2, "jump-end").ease(0.6f), 0.001f)
        assertEquals(0.5f, TimingFunction.Steps(2, "jump-start").ease(0.1f), 0.001f)
    }

    @Test
    fun parsesShorthands() {
        val decl = CssParser.parseDeclarations("transition: opacity 200ms ease-in 50ms, color 1s").single()
        val map = decl.parsed!!.toMap()
        assertEquals(listOf("opacity", "color"), map[net.sbo.guilib.core.css.Prop.TRANSITION_PROPERTY])
        assertEquals(listOf(200f, 1000f), map[net.sbo.guilib.core.css.Prop.TRANSITION_DURATION])
        assertEquals(listOf(50f, 0f), map[net.sbo.guilib.core.css.Prop.TRANSITION_DELAY])

        val anim = CssParser.parseDeclarations("animation: pulse 2s linear 100ms infinite alternate both").single().parsed!!.toMap()
        assertEquals(listOf("pulse"), anim[net.sbo.guilib.core.css.Prop.ANIMATION_NAME])
        assertEquals(listOf(Float.POSITIVE_INFINITY), anim[net.sbo.guilib.core.css.Prop.ANIMATION_ITERATION_COUNT])

        val sheet = Stylesheet.parse("@keyframes fade { from { opacity: 0 } 50%, 75% { opacity: 0.5 } to { opacity: 1 } }")
        assertEquals(listOf(0f, 0.5f, 0.75f, 1f), sheet.keyframes.getValue("fade").frames.map { it.offset })
    }

    @Test
    fun transitionInterpolatesOnClassChange() {
        lateinit var setOn: (Boolean) -> Unit
        val d = doc(".box { width: 10px; height: 10px; opacity: 0.2; background-color: #000000; transition: opacity 100ms linear, width 100ms linear } .box.on { opacity: 1; width: 110px; background-color: #ffffff }") {
            val (on, s) = useState(false)
            setOn = s
            div(className = classNames("box", "on" to on))
        }
        val box = d.el(".box")
        setOn(true)
        d.at(0)
        assertEquals(0.2f, box.style.opacity, 0.01f)
        d.at(50)
        assertEquals(0.6f, box.style.opacity, 0.01f)
        assertEquals(60f, box.box.width, 0.5f)                       // layout follows the animated width
        assertEquals(0xFFFFFFFF.toInt(), box.style.backgroundColor)  // not listed in `transition` → jumps
        d.at(100)
        assertEquals(1f, box.style.opacity, 0.01f)
        d.at(150)
        assertFalse(d.isAnimating)
        assertNull(box.animatedStyle)
        assertEquals(110f, box.box.width, 0.01f)
    }

    @Test
    fun transitionRetargetsFromTheCurrentValue() {
        lateinit var setOn: (Boolean) -> Unit
        val d = doc(".box { opacity: 0; transition: opacity 100ms linear } .box.on { opacity: 1 }") {
            val (on, s) = useState(false)
            setOn = s
            div(className = classNames("box", "on" to on))
        }
        setOn(true); d.at(0); d.at(50)
        assertEquals(0.5f, d.el(".box").style.opacity, 0.01f)
        setOn(false); d.at(50)        // reverse half-way: starts at 0.5, not at 1
        assertEquals(0.5f, d.el(".box").style.opacity, 0.01f)
        d.at(100)
        assertEquals(0.25f, d.el(".box").style.opacity, 0.01f)
    }

    @Test
    fun keyframeAnimationsLoopAlternateAndFill() {
        val d = doc(
            """
            @keyframes grow { from { width: 0px } to { width: 100px } }
            @keyframes fade { from { opacity: 0 } to { opacity: 1 } }
            .a { height: 1px; animation: grow 100ms linear infinite alternate }
            .b { height: 1px; width: 5px; animation: fade 100ms linear 50ms both }
            """.trimIndent(),
        ) {
            div(className = "a")
            div(className = "b")
        }
        val a = d.el(".a")
        val b = d.el(".b")
        d.at(25)
        assertEquals(25f, a.box.width, 0.5f)
        assertEquals(0f, b.style.opacity, 0.01f)       // backwards fill during the delay
        d.at(125)
        assertEquals(75f, a.box.width, 0.5f)           // 2nd iteration runs backwards (alternate)
        assertEquals(0.75f, b.style.opacity, 0.01f)
        d.at(1000)
        assertEquals(1f, b.style.opacity, 0.01f)       // forwards fill keeps the end value
        assertTrue(d.isAnimating)                      // infinite + filled animations keep running
    }

    @Test
    fun animatedInheritedValuesReachChildren() {
        val d = doc("@keyframes tint { from { color: #000000 } to { color: #ffffff } } .p { animation: tint 100ms linear }") {
            div(className = "p") { span(className = "c") { +"x" } }
        }
        d.at(50)
        assertEquals(0xFF808080.toInt(), d.el(".c").style.color)
        d.at(200)
        assertFalse(d.isAnimating)
        assertEquals(d.el(".p").computed!!.color, d.el(".c").style.color)
    }

    @Test
    fun mixedUnitsInterpolateThroughCalc() {
        lateinit var setOn: (Boolean) -> Unit
        val d = doc(".outer { width: 200px } .box { height: 1px; width: 0px; transition: width 100ms linear } .box.on { width: 50% }") {
            val (on, s) = useState(false)
            setOn = s
            div(className = "outer") { div(className = classNames("box", "on" to on)) }
        }
        setOn(true); d.at(0); d.at(50)
        assertTrue(d.el(".box").style.width is Dim.Calc)
        assertEquals(50f, d.el(".box").box.width, 0.5f) // halfway between 0px and 50% of 200px
    }

    @Test
    fun aFinishedAnimationDoesNotReplayWhenTheElementIsRestyled() {
        // Regression: a sortable item with an entry animation jumped back to its old place on every mouse move while
        // dragging, because each new inline transform restyled it and restarted the finished animation.
        lateinit var setShift: (Int) -> Unit
        lateinit var setAnimated: (Boolean) -> Unit
        val d = doc(".box { width: 10px; height: 10px } .box.anim { animation: grow 100ms linear } @keyframes grow { from { width: 50px } }") {
            val (shift, s) = useState(0)
            val (animated, a) = useState(true)
            setShift = s
            setAnimated = a
            div(className = classNames("box", "anim" to animated), style = "transform: translateY(${shift}px)")
        }
        val box = d.el(".box")
        d.at(50)
        assertEquals(30f, box.box.width, 0.5f)
        d.at(200)
        assertEquals(10f, box.box.width, 0.01f)
        // Restyles (inline style changes) don't start it again.
        for (t in 1..5) {
            setShift(t * 3)
            d.at(200L + t * 16)
            assertEquals(10f, box.box.width, 0.01f, "restyle $t")
            assertNull(box.animatedStyle, "restyle $t")
        }
        // Removing the name and adding it back plays it again, like in browsers.
        setAnimated(false)
        d.at(300)
        setAnimated(true)
        d.at(300)
        d.at(350)
        assertEquals(30f, box.box.width, 0.5f)
    }
}
