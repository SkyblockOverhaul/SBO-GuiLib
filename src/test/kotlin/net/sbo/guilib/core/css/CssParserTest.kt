package net.sbo.guilib.core.css

import net.sbo.guilib.core.Log
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CssParserTest {
    private val warnings = ArrayList<String>()
    private val oldSink = Log.sink

    @BeforeEach
    fun captureLog() {
        Log.resetOnce()
        Log.sink = { level, msg -> if (level == Log.Level.WARN) warnings += msg }
    }

    @AfterEach
    fun restoreLog() {
        Log.sink = oldSink
    }

    @Test
    fun tokenizesNumbersUnitsAndHashes() {
        val tokens = Tokenizer("width: -1.5em; color:#ff0 /* c */ 50%").tokenize().filter { it.type != TokenType.WHITESPACE }
        assertEquals(
            listOf(TokenType.IDENT, TokenType.COLON, TokenType.DIMENSION, TokenType.SEMICOLON, TokenType.IDENT, TokenType.COLON, TokenType.HASH, TokenType.PERCENTAGE, TokenType.EOF),
            tokens.map { it.type },
        )
        assertEquals(-1.5, tokens[2].number)
        assertEquals("em", tokens[2].unit)
        assertEquals("ff0", tokens[6].text)
    }

    @Test
    fun parsesRulesAndDeclarations() {
        val sheet = Stylesheet.parse(
            """
            .card, #main > button:hover {
                padding: 4px 8px;
                color: red !important;
            }
            div { display: flex }
            """.trimIndent(), "test.css",
        )
        assertEquals(2, sheet.rules.size)
        val first = sheet.rules[0]
        assertEquals(2, first.selectors.size)
        assertEquals("#main > button:hover", first.selectors[1].toString())
        assertEquals(listOf("padding", "color"), first.declarations.map { it.property })
        assertTrue(first.declarations[1].important)
        assertTrue(warnings.isEmpty(), warnings.toString())
    }

    @Test
    fun unknownPropertyWarnsWithLocationAndSuggestion() {
        val sheet = Stylesheet.parse(".a {\n  colr: red;\n  width: 10px;\n}", "ui.css")
        assertEquals(listOf("width"), sheet.rules[0].declarations.map { it.property })
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("ui.css:2:3"), warnings[0])
        assertTrue(warnings[0].contains("did you mean 'color'"), warnings[0])
    }

    @Test
    fun invalidValueIsDroppedWithoutCrashing() {
        val sheet = Stylesheet.parse(".a { width: banana; height: 5px }", "x.css")
        assertEquals(listOf("height"), sheet.rules[0].declarations.map { it.property })
        assertTrue(warnings.single().contains("invalid value 'banana'"))
    }

    @Test
    fun invalidSelectorDropsOnlyThatRule() {
        val sheet = Stylesheet.parse("a::placeholder { color: red } .ok { color: blue } a:nth-child(2n of .x) { color: red }", "x.css")
        assertEquals(1, sheet.rules.size)
        assertEquals(".ok", sheet.rules[0].selectors[0].toString())
        assertEquals(2, warnings.size)
    }

    @Test
    fun atRulesAreSkippedWithWarning() {
        val sheet = Stylesheet.parse("@supports (display: grid) { .a { color: red } } .b { color: blue }", "x.css")
        assertEquals(1, sheet.rules.size)
        assertTrue(warnings.single().contains("@supports"))
    }

    @Test
    fun parsesColors() {
        fun c(v: String) = Properties.parse("color", CssParser.parseDeclarations("color: $v").single().value)!!.single().second
        assertEquals(0xFFFF0000.toInt(), c("red"))
        assertEquals(0xFFFFFF00.toInt(), c("#ff0"))
        assertEquals(0x80112233.toInt(), c("#11223380"))
        assertEquals(0x80FF0000.toInt(), c("rgba(255, 0, 0, 0.5)"))
        assertEquals(0x80FF0000.toInt(), c("rgb(255 0 0 / 50%)"))
        assertEquals(0xFF00FF00.toInt(), c("hsl(120, 100%, 50%)"))
        assertEquals(CurrentColor, c("currentColor"))
    }

    @Test
    fun expandsShorthands() {
        fun expand(decl: String) = CssParser.parseDeclarations(decl).single().parsed!!.associate { it.first to it.second }
        val padding = expand("padding: 1px 2px 3px")
        assertEquals(Length(1f, "px"), padding[Prop.PADDING_TOP])
        assertEquals(Length(2f, "px"), padding[Prop.PADDING_RIGHT])
        assertEquals(Length(3f, "px"), padding[Prop.PADDING_BOTTOM])
        assertEquals(Length(2f, "px"), padding[Prop.PADDING_LEFT])

        val border = expand("border: 2px solid #fff")
        assertEquals(Length(2f, "px"), border[Prop.BORDER_LEFT_WIDTH])
        assertEquals(BorderStyle.SOLID, border[Prop.BORDER_TOP_STYLE])

        val flex = expand("flex: 1")
        assertEquals(1f, flex[Prop.FLEX_GROW])
        assertEquals(1f, flex[Prop.FLEX_SHRINK])
        assertEquals(Length(0f, "%"), flex[Prop.FLEX_BASIS])

        assertEquals(Overflow.HIDDEN, expand("overflow: hidden scroll")[Prop.OVERFLOW_X])
        assertEquals(JustifyContent.SPACE_BETWEEN, expand("justify-content: space-between")[Prop.JUSTIFY_CONTENT])
    }

    @Test
    fun inlineStyleParsing() {
        val decls = CssParser.parseDeclarations("color: var(--accent, red); margin: 0 auto")
        assertEquals(2, decls.size)
        assertEquals(null, decls[0].parsed) // contains var(), resolved per element
        assertEquals(Dim.Auto, decls[1].parsed!!.first { it.first == Prop.MARGIN_LEFT }.second)
    }
}
