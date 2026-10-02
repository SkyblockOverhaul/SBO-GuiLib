package net.sbo.guilib.core.css

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.useToast
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Every color in the real ua.css comes from a `--guilib-*` variable, so a theme only has to override variables. */
class ThemeVariablesTest {
    private val ua = javaClass.getResource("/assets/guilib/css/ua.css")!!.readText()

    private fun root(author: String = ""): UiRoot {
        val sheets = listOf(Stylesheet.parse(ua, "ua.css", Origin.USER_AGENT), Stylesheet.parse(author, "theme.css", Origin.AUTHOR))
        val root = UiRoot(FakeMeasurer, sheets, clock = { 0L })
        root.render(VComponent(component("App") {
            val toast = useToast()
            useEffect { toast.error("Server not reachable", durationMs = 0) }
            button { +"Join" }
        }, Unit, null))
        root.frame(300f, 200f)
        return root
    }

    private fun UiRoot.button() = document.body.querySelector("button")!!
    private fun UiRoot.toast() = document.overlayRoot.querySelector(".guilib-toast")!!
    private fun UiRoot.toastAccent() = document.overlayRoot.querySelector(".guilib-toast-accent")!!

    @Test
    fun theDefaultsKeepTheBuiltInLook() {
        val root = root()
        assertEquals(0xFFF2F3F5.toInt(), root.document.body.style.color)
        assertEquals(0xFF3C3F45.toInt(), root.button().style.backgroundColor)
        assertEquals(0xFF55595F.toInt(), root.button().style.borderTopColor)
        assertEquals(0xFF111214.toInt(), root.toast().style.backgroundColor)
        assertEquals(0xFFED4245.toInt(), root.toastAccent().style.backgroundColor)
    }

    @Test
    fun overridingVariablesOnRootRestylesControlsAndTheOverlay() {
        val root = root(
            """
            :root {
                --guilib-text: #101010;
                --guilib-button: #ff0000;
                --guilib-button-border: #00ff00;
                --guilib-surface-3: #0000ff;
                --guilib-danger: #123456;
            }
            """
        )
        assertEquals(0xFF101010.toInt(), root.document.body.style.color)
        assertEquals(0xFF101010.toInt(), root.button().style.color)
        assertEquals(0xFFFF0000.toInt(), root.button().style.backgroundColor)
        assertEquals(0xFF00FF00.toInt(), root.button().style.borderTopColor)
        // Menus, tooltips and toasts live in the overlay, which is inside body, so they inherit the variables too.
        assertEquals(0xFF0000FF.toInt(), root.toast().style.backgroundColor)
        assertEquals(0xFF123456.toInt(), root.toastAccent().style.backgroundColor)
    }

    @Test
    fun uaCssHasNoLiteralColorsOutsideTheVariableBlock() {
        val bodyStart = ua.indexOf("body {")
        val rest = ua.substring(ua.indexOf('}', bodyStart) + 1)
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val literals = Regex("[^\\n]*(#[0-9a-fA-F]{3,8}\\b|rgba?\\(|hsla?\\()[^\\n]*").findAll(rest).map { it.value.trim() }.toList()
        assertTrue(literals.isEmpty(), "use a --guilib-* variable instead of: $literals")
    }
}
