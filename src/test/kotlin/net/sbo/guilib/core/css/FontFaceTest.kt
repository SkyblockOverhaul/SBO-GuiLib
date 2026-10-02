package net.sbo.guilib.core.css

import net.sbo.guilib.core.dom.Document
import net.sbo.guilib.core.layout.FontMetrics
import net.sbo.guilib.core.layout.TextMeasurer
import net.sbo.guilib.core.layout.TextStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FontFaceTest {
    @Test
    fun parsesFontFaceRules() {
        val sheet = Stylesheet.parse(
            """
            @font-face {
                font-family: "My Font";
                src: local(Arial), url("mymod:fonts/my.ttf") format("truetype"), url(mymod:fonts/fallback.otf);
                font-weight: bold;
                font-style: italic;
                font-display: swap;
            }
            @font-face { font-family: Variable Sans; src: url("mymod:fonts/var.ttf"); font-weight: 900 100 }
            @font-face { font-family: Broken; }
            @media (min-width: 10px) { @font-face { font-family: inner; src: url("a:b.ttf") } }
            .x { font-family: "My Font", inter }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                FontFace("my font", listOf("mymod:fonts/my.ttf", "mymod:fonts/fallback.otf"), 700, 700, true),
                FontFace("variable sans", listOf("mymod:fonts/var.ttf"), 100, 900, false),
                FontFace("inner", listOf("a:b.ttf"), 400, 400, false),
            ),
            sheet.fontFaces,
        )
        assertEquals(1, sheet.rules.size) // the descriptors don't leak into style rules
    }

    @Test
    fun theDocumentHandsItsFontFacesToTheMeasurer() {
        val seen = ArrayList<List<FontFace>>()
        val measurer = object : TextMeasurer {
            override fun width(text: String, style: TextStyle) = text.length * 4f
            override fun metrics(style: TextStyle) = FontMetrics(6f, 2f, 10f)
            override fun fontFaces(faces: List<FontFace>) { seen += faces }
        }
        val a = Stylesheet.parse("@font-face { font-family: a; src: url('m:a.ttf') }")
        val b = Stylesheet.parse("@font-face { font-family: b; src: url('m:b.ttf') }")
        val doc = Document(measurer, listOf(a))
        doc.setStylesheets(listOf(a, b))
        assertEquals(listOf("a"), seen[0].map { it.family })
        assertEquals(listOf("a", "b"), seen.last().map { it.family })
        assertTrue(seen.size == 2)
    }
}
