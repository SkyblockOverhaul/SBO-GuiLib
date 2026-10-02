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
    fun attributeSelectors() {
        val el = object : Selectable by FakeElement("input") {
            override fun styleAttribute(name: String) = mapOf("type" to "checkbox", "value" to "hello")[name]
        }
        assertTrue(sel("input[type=checkbox]").matches(el))
        assertTrue(sel("[type='checkbox']").matches(el))
        assertTrue(sel("[value]").matches(el))
        assertTrue(sel("[value^=he]").matches(el))
        assertTrue(sel("[value\$=lo]").matches(el))
        assertTrue(sel("[value*=ll]").matches(el))
        assertFalse(sel("[type=text]").matches(el))
        assertFalse(sel("[placeholder]").matches(el))
        assertEquals(1_000 + 1, sel("input[type=checkbox]").specificity)
    }

    @Test
    fun parsesSelectorLists() {
        val list = Selector.parse("a, .b > c ,#d")
        assertEquals(listOf("a", ".b > c", "#d"), list.map { it.toString() })
    }

    @Test
    fun matchesNthChild() {
        val list = FakeElement("ul")
        // li, li, p, li, li, li  (positions 1..6)
        val kids = listOf("li", "li", "p", "li", "li", "li").map { list.add(FakeElement(it)) }
        fun matching(selector: String) = kids.indices.filter { sel(selector).matches(kids[it]) }.map { it + 1 }
        assertEquals(listOf(1, 3, 5), matching(":nth-child(odd)"))
        assertEquals(listOf(2, 4, 6), matching(":nth-child(even)"))
        assertEquals(listOf(2, 4, 6), matching(":nth-child(2n)"))
        assertEquals(listOf(3), matching(":nth-child(3)"))
        assertEquals(listOf(1, 2, 3), matching(":nth-child(-n+3)"))
        assertEquals(listOf(3, 4, 5, 6), matching(":nth-child(n + 3)"))
        assertEquals(listOf(2, 5), matching(":nth-child(3n-1)"))
        assertEquals(listOf(5, 6), matching(":nth-last-child(-n+2)"))
        assertEquals(listOf(1, 4, 6), matching("li:nth-of-type(odd)")) // li #1, #3, #5
        assertEquals(listOf(6), matching("li:nth-last-of-type(1)"))
        assertEquals(1_000, sel(":nth-child(2n+1)").specificity)
        assertEquals("li:nth-child(2n+1)", sel("li:nth-child(odd)").toString())
    }

    @Test
    fun matchesNthChildOfSelector() {
        val list = FakeElement("ul")
        // positions 1..6; "x" marks the rows S matches
        val kids = listOf("x", "", "x", "x", "", "x").map { list.add(FakeElement("li", classes = it)) }
        fun matching(selector: String) = kids.indices.filter { sel(selector).matches(kids[it]) }.map { it + 1 }
        assertEquals(listOf(1, 4), matching(":nth-child(odd of .x)")) // .x rows are 1, 3, 4, 6 → 1st and 3rd
        assertEquals(listOf(3, 6), matching(":nth-child(even of .x)"))
        assertEquals(listOf(6), matching(":nth-last-child(1 of .x)"))
        assertEquals(listOf(2), matching(":nth-child(1 of :not(.x))"))
        assertEquals(listOf(1, 2), matching(":nth-child(-n+2 of li.x, li:not(.x))")) // a selector list
        assertEquals(listOf(4), matching(":nth-child(2 of ul > .x:not(:first-child))")) // complex selectors
        assertEquals(listOf(1, 5), matching(":not(:nth-child(even of .x)):nth-child(odd)")) // odd minus row 3
        assertEquals(2_000, sel(":nth-child(odd of .x)").specificity)
        assertEquals(1_000_000 + 1_000, sel(":nth-child(odd of .x, #y)").specificity)
        assertEquals(":nth-child(2n+1 of .x, li)", sel(":nth-child(odd of .x, li)").toString())
    }

    @Test
    fun rejectsUnsupportedSyntax() {
        assertThrows(IllegalArgumentException::class.java) { Selector.parse("a::selection") }
        assertThrows(IllegalArgumentException::class.java) { Selector.parse("a:nth-child(foo)") }
        assertThrows(IllegalArgumentException::class.java) { Selector.parse("a:nth-of-type(2n+1 of .x)") }
        assertThrows(IllegalArgumentException::class.java) { Selector.parse("a:nth-child(2n+1 of)") }
        assertThrows(IllegalArgumentException::class.java) { Selector.parse("a:nth-child(odd of .x::before)") }
        assertThrows(IllegalArgumentException::class.java) { Selector.parse("> a") }
        assertThrows(IllegalArgumentException::class.java) { Selector.parse("a >") }
        assertThrows(IllegalArgumentException::class.java) { Selector.parse(".a.") }
    }
}
