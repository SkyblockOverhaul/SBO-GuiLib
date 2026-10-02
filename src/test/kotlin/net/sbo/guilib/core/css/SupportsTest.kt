package net.sbo.guilib.core.css

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SupportsTest {
    /** The class names of the rules that survived parsing. */
    private fun kept(css: String) = Stylesheet.parse(css).rules.map { it.selectors.joinToString { s -> s.toString() } }

    private fun supported(condition: String) = kept("@supports $condition { .yes { color: red } }") == listOf(".yes")

    @Test
    fun declarationsAreSupportedWhenThePropertyAndValueParse() {
        assertEquals(true, supported("(display: grid)"))
        assertEquals(true, supported("(background: conic-gradient(red, blue))"))
        assertEquals(true, supported("(color: var(--x))"))
        assertEquals(true, supported("(--anything: 1)"))
        assertEquals(false, supported("(display: contents)"))
        assertEquals(false, supported("(backdrop-filter: blur(4px))")) // unknown property
        assertEquals(false, supported("(transform: perspective(10px))"))
    }

    @Test
    fun logicAndSelectors() {
        assertEquals(true, supported("not (display: contents)"))
        assertEquals(false, supported("not (display: grid)"))
        assertEquals(true, supported("(display: grid) and (gap: 4px)"))
        assertEquals(false, supported("(display: grid) and (display: contents)"))
        assertEquals(true, supported("(display: contents) or (display: flex)"))
        assertEquals(true, supported("((display: contents) or (display: flex)) and (not (display: contents))"))
        assertEquals(true, supported("selector(.a > b:nth-child(2n+1))"))
        assertEquals(true, supported("selector(::before)"))
        assertEquals(false, supported("selector(:has(a))"))
        assertEquals(false, supported("font-tech(color-COLRv1)"))
        // Malformed conditions never apply.
        assertEquals(false, supported("(display: grid) and (gap: 1px) or (color: red)"))
        assertEquals(false, supported("display: grid"))
    }

    @Test
    fun blocksNestAndKeepTheirAtRules() {
        val sheet = Stylesheet.parse(
            """
            .a { color: red }
            @supports (display: grid) {
                .b { display: grid }
                @media (min-width: 100px) { .c { color: blue } }
                @keyframes spin { to { opacity: 0 } }
            }
            @supports (display: contents) { .d { display: contents } @keyframes nope { to { opacity: 0 } } }
            @media (min-width: 1px) { @supports (gap: 1px) { .e { gap: 1px } } }
            .f { color: red }
            """.trimIndent(),
        )
        assertEquals(listOf(".a", ".b", ".c", ".e", ".f"), sheet.rules.map { it.selectors.joinToString { s -> s.toString() } })
        assertEquals(1, sheet.rules.first { it.selectors.toString().contains(".c") }.media.size)
        assertEquals(1, sheet.rules.first { it.selectors.toString().contains(".e") }.media.size)
        assertEquals(setOf("spin"), sheet.keyframes.keys)
    }
}
