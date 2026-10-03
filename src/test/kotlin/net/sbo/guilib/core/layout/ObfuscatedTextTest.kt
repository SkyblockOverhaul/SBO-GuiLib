package net.sbo.guilib.core.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ObfuscatedTextTest {
    private val base = TextStyle(listOf("inter"), 8f, 400, false, 0xFFFFFFFF.toInt())

    @Test
    fun sectionKObfuscatesUntilResetOrColor() {
        val runs = FormattingCodes.parse("a§kb§rc§kd§6e", base)
        assertEquals(listOf("a", "b", "c", "d", "e"), runs.map { it.first })
        assertEquals(listOf(false, true, false, true, false), runs.map { it.second.obfuscated })
    }

    @Test
    fun obfuscationKeepsOtherFormatting() {
        val (text, style) = FormattingCodes.parse("§l§khidden", base).single()
        assertEquals("hidden", text)
        assertTrue(style.obfuscated)
        assertTrue(style.bold)
        assertFalse(base.obfuscated)
    }
}
