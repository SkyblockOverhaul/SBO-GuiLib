package net.sbo.guilib.core.debug

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What the CSS inspector shows: selector, path and matching rules of an element. */
class InspectorTest {
    @Test
    fun selectorPathAndMatchingRules() {
        val ua = Stylesheet.parse("button { display: block }", "ua.css", Origin.USER_AGENT)
        val screen = Stylesheet.parse("div { display: block }\n.win .btn { color: red }\n.other { color: blue }\n#go.btn { color: green }", "screen.css")
        val root = UiRoot(FakeMeasurer, listOf(ua, screen))
        root.render(VComponent(component("App") {
            div(className = "win") { button(className = "btn primary", id = "go") { +"Go" } }
        }, Unit, null))
        root.document.body.classList.add("sbo")
        root.frame(200f, 100f)
        val btn = root.document.body.querySelector(".btn")!!

        assertEquals("button#go.btn.primary", Inspector.selectorOf(btn))
        assertEquals("button#go.btn.primary", Inspector.pathOf(btn).substringAfterLast(" > "))
        assertEquals("body.sbo", Inspector.pathOf(btn).substringBefore(" > "))
        assertEquals(listOf("screen.css:2  .win .btn", "screen.css:4  #go.btn"), Inspector.matchingRules(root.document, btn))
        assertEquals("ua.css:1  button", Inspector.matchingRules(root.document, btn, userAgent = true).first())
    }
}
