package net.sbo.guilib.core.css

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SelectorTest {
    private fun sel(s: String) = Selector.parse(s).single()

    @Test
    fun specificityFollowsWebRules() {
        assertEquals(1, sel("div").specificity)
        assertEquals(1_000, sel(".a").specificity)
        assertEquals(1_000_000, sel("#x").specificity)
        assertEquals(1_000_000 + 2_000 + 1, sel("#x button.primary:hover").specificity)
        assertEquals(0, sel("*").specificity)
        assertEquals(1_000 + 1_000, sel(".a:not(.b)").specificity)
        assertTrue(sel(".a .b").specificity > sel("div div div").specificity)
    }

    @Test
    fun matchesCompoundAndStates() {
        val btn = FakeElement("button", "go", "btn primary", PseudoState.HOVER)
        assertTrue(sel("button.btn.primary#go:hover").matches(btn))
        assertFalse(sel("button:active").matches(btn))
        assertTrue(sel(":not(:disabled)").matches(btn))
        assertTrue(sel("button:enabled").matches(btn))
        btn.states += PseudoState.DISABLED
        assertFalse(sel("button:enabled").matches(btn))
        assertTrue(sel("BUTTON").matches(btn)) // tag names are case-insensitive
    }

    @Test
    fun matchesDescendantAndChildCombinators() {
        val root = FakeElement("div", classes = "app")
        val list = root.add(FakeElement("div", classes = "list"))
        val row = list.add(FakeElement("div", classes = "row"))
        val label = row.add(FakeElement("span"))

        assertTrue(sel(".app span").matches(label))
        assertTrue(sel(".app .list > .row span").matches(label))
        assertFalse(sel(".app > .row").matches(row))
        assertTrue(sel(".app > .list > .row").matches(row))
        assertTrue(sel("div div span").matches(label))
        assertFalse(sel(".row > .list span").matches(label))
    }

    @Test
    fun matchesSiblingsAndStructuralPseudos() {
        val parent = FakeElement("ul")
        val a = parent.add(FakeElement("li", classes = "a"))
        val b = parent.add(FakeElement("li", classes = "b"))
        val c = parent.add(FakeElement("li", classes = "c"))
        assertTrue(sel("li:first-child").matches(a))
        assertFalse(sel("li:first-child").matches(b))
        assertTrue(sel("li:last-child").matches(c))
        assertTrue(sel(".a + .b").matches(b))
        assertFalse(sel(".a + .c").matches(c))
        assertTrue(sel(".a ~ .c").matches(c))
        assertTrue(sel(":root").matches(parent))
    }

    @Test
    fun parsesSelectorLists() {
        val list = Selector.parse("a, .b > c ,#d")
        assertEquals(listOf("a", ".b > c", "#d"), list.map { it.toString() })
    }

    @Test
    fun rejectsUnsupportedSyntax() {
        assertThrows(IllegalArgumentException::class.java) { Selector.parse("a::after") }
        assertThrows(IllegalArgumentException::class.java) { Selector.parse("a:nth-child(2)") }
        assertThrows(IllegalArgumentException::class.java) { Selector.parse("> a") }
        assertThrows(IllegalArgumentException::class.java) { Selector.parse("a >") }
        assertThrows(IllegalArgumentException::class.java) { Selector.parse(".a.") }
    }
}
