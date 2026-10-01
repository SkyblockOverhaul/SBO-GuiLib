package net.sbo.guilib.core.css

/** Interactive states that map to CSS pseudo-classes. */
enum class PseudoState(val css: String) {
    HOVER("hover"), ACTIVE("active"), FOCUS("focus"), FOCUS_WITHIN("focus-within"), DISABLED("disabled"), CHECKED("checked");

    val bit get() = 1 shl ordinal
}

/** What the selector engine needs to know about an element. Implemented by the DOM element (and by test fakes). */
interface Selectable {
    val styleTag: String
    val styleId: String?
    val styleClasses: Collection<String>
    val styleParent: Selectable?
    val stylePreviousSibling: Selectable?
    val styleNextSibling: Selectable?
    fun hasState(state: PseudoState): Boolean
    /** String value of an attribute for `[attr]` / `[attr=value]` selectors, or `null` if absent. */
    fun styleAttribute(name: String): String? = null
}

sealed interface SimpleSelector {
    fun matches(el: Selectable): Boolean

    data class Type(val name: String) : SimpleSelector {
        override fun matches(el: Selectable) = el.styleTag.equals(name, ignoreCase = true)
    }

    data object Universal : SimpleSelector {
        override fun matches(el: Selectable) = true
    }

    data class Id(val id: String) : SimpleSelector {
        override fun matches(el: Selectable) = el.styleId == id
    }

    data class Class(val name: String) : SimpleSelector {
        override fun matches(el: Selectable) = name in el.styleClasses
    }

    data class State(val state: PseudoState) : SimpleSelector {
        override fun matches(el: Selectable) = el.hasState(state)
    }

    data class Structural(val kind: String) : SimpleSelector {
        override fun matches(el: Selectable) = when (kind) {
            "first-child" -> el.stylePreviousSibling == null
            "last-child" -> el.styleNextSibling == null
            "only-child" -> el.stylePreviousSibling == null && el.styleNextSibling == null
            "root" -> el.styleParent == null
            // Negations of the interactive states, so `:enabled` works as on the web.
            "enabled" -> !el.hasState(PseudoState.DISABLED)
            else -> false
        }
    }

    /**
     * `:nth-child(An+B)` and friends: matches if the element's 1-based position among its siblings (counted from the
     * end for [fromEnd], only siblings with the same tag for [ofType]) is `a·n + b` for some n ≥ 0.
     */
    data class NthChild(val a: Int, val b: Int, val fromEnd: Boolean, val ofType: Boolean) : SimpleSelector {
        override fun matches(el: Selectable): Boolean {
            var index = 1
            var s = if (fromEnd) el.styleNextSibling else el.stylePreviousSibling
            while (s != null) {
                if (!ofType || s.styleTag.equals(el.styleTag, ignoreCase = true)) index++
                s = if (fromEnd) s.styleNextSibling else s.stylePreviousSibling
            }
            if (a == 0) return index == b
            val n = index - b
            return n % a == 0 && n / a >= 0
        }

        val name get() = (if (fromEnd) "nth-last-" else "nth-") + (if (ofType) "of-type" else "child")

        override fun toString() = ":$name(${a}n${if (b >= 0) "+" else ""}$b)"
    }

    /** `[name]`, `[name=value]`, `[name^=value]`, `[name$=value]`, `[name*=value]`. */
    data class Attribute(val name: String, val op: String?, val value: String?) : SimpleSelector {
        override fun matches(el: Selectable): Boolean {
            val actual = el.styleAttribute(name) ?: return false
            val v = value ?: return true
            return when (op) {
                "=" -> actual == v
                "^=" -> actual.startsWith(v)
                "$=" -> actual.endsWith(v)
                "*=" -> actual.contains(v)
                else -> false
            }
        }
    }

    data class Not(val inner: List<Compound>) : SimpleSelector {
        override fun matches(el: Selectable) = inner.none { it.matches(el) }
    }
}

data class Compound(val parts: List<SimpleSelector>) {
    fun matches(el: Selectable) = parts.all { it.matches(el) }

    val specificity: Int
        get() = parts.sumOf { specificityOf(it) }

    override fun toString() = parts.joinToString("") {
        when (it) {
            is SimpleSelector.Type -> it.name
            SimpleSelector.Universal -> "*"
            is SimpleSelector.Id -> "#${it.id}"
            is SimpleSelector.Class -> ".${it.name}"
            is SimpleSelector.State -> ":${it.state.css}"
            is SimpleSelector.Structural -> ":${it.kind}"
            is SimpleSelector.NthChild -> it.toString()
            is SimpleSelector.Not -> ":not(${it.inner.joinToString(", ")})"
            is SimpleSelector.Attribute -> "[${it.name}${it.op ?: ""}${it.value?.let { v -> "\"$v\"" } ?: ""}]"
        }
    }

    private companion object {
        fun specificityOf(s: SimpleSelector): Int = when (s) {
            is SimpleSelector.Id -> Selector.ID_WEIGHT
            is SimpleSelector.Class, is SimpleSelector.State, is SimpleSelector.Structural, is SimpleSelector.NthChild,
            is SimpleSelector.Attribute -> Selector.CLASS_WEIGHT
            is SimpleSelector.Type -> 1
            SimpleSelector.Universal -> 0
            is SimpleSelector.Not -> s.inner.maxOf { it.specificity }
        }
    }
}

enum class Combinator(val css: String) { DESCENDANT(" "), CHILD(" > "), NEXT_SIBLING(" + "), SUBSEQUENT_SIBLING(" ~ ") }

/**
 * A complex selector like `.list > .row:hover span`. [compounds] are in source order, the last one is the subject;
 * `combinators[i]` joins `compounds[i]` and `compounds[i + 1]`.
 */
class Selector(val compounds: List<Compound>, val combinators: List<Combinator>) {
    /** (ids, classes, types) packed as `ids * 1_000_000 + classes * 1_000 + types`, compared as a single int like the web. */
    val specificity: Int = compounds.sumOf { it.specificity }

    val subject get() = compounds.last()

    fun matches(el: Selectable): Boolean = matchAt(el, compounds.size - 1)

    private fun matchAt(el: Selectable, index: Int): Boolean {
        if (!compounds[index].matches(el)) return false
        if (index == 0) return true
        return when (combinators[index - 1]) {
            Combinator.CHILD -> el.styleParent?.let { matchAt(it, index - 1) } ?: false
            Combinator.DESCENDANT -> {
                var p = el.styleParent
                while (p != null) {
                    if (matchAt(p, index - 1)) return true
                    p = p.styleParent
                }
                false
            }
            Combinator.NEXT_SIBLING -> el.stylePreviousSibling?.let { matchAt(it, index - 1) } ?: false
            Combinator.SUBSEQUENT_SIBLING -> {
                var s = el.stylePreviousSibling
                while (s != null) {
                    if (matchAt(s, index - 1)) return true
                    s = s.stylePreviousSibling
                }
                false
            }
        }
    }

    override fun toString() = buildString {
        compounds.forEachIndexed { i, c ->
            if (i > 0) append(combinators[i - 1].css)
            append(c)
        }
    }

    companion object {
        const val ID_WEIGHT = 1_000_000
        const val CLASS_WEIGHT = 1_000

        /** Parses a selector list such as `button.primary:hover, .card > span`. Throws [IllegalArgumentException] if invalid. */
        fun parse(text: String): List<Selector> =
            when (val r = SelectorParser.parse(Tokenizer(text).tokenize().filter { it.type != TokenType.EOF })) {
                is SelectorParser.Result.Ok -> r.selectors
                is SelectorParser.Result.Error -> throw IllegalArgumentException(r.message)
            }
    }
}

object SelectorParser {
    sealed interface Result {
        data class Ok(val selectors: List<Selector>) : Result
        data class Error(val message: String, val at: Token?) : Result
    }

    private class Fail(message: String, val at: Token?) : Exception(message)

    private val STATES = PseudoState.entries.associateBy { it.css }
    private val NTH = setOf("nth-child", "nth-last-child", "nth-of-type", "nth-last-of-type")

    private val AN_PLUS_B = Regex("""^([+-]?\d*)n([+-]\d+)?$""")

    /** `An+B`, `odd`, `even` or a plain integer, from the tokens between the parentheses. */
    private fun parseAnPlusB(tokens: List<Token>): Pair<Int, Int>? {
        val text = tokens.filter { it.type != TokenType.WHITESPACE }.map {
            when (it.type) {
                TokenType.IDENT, TokenType.NUMBER, TokenType.DELIM -> it.text
                TokenType.DIMENSION -> it.text + it.unit
                else -> return null
            }
        }.joinToString("").lowercase()
        if (text == "odd") return 2 to 1
        if (text == "even") return 2 to 0
        text.toIntOrNull()?.let { return 0 to it }
        val m = AN_PLUS_B.matchEntire(text) ?: return null
        val a = when (val s = m.groupValues[1]) {
            "", "+" -> 1
            "-" -> -1
            else -> s.toInt()
        }
        val b = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        return a to b
    }

    private val STRUCTURAL = setOf("first-child", "last-child", "only-child", "root", "enabled")

    fun parse(tokens: List<Token>): Result = try {
        val lists = splitTopLevel(tokens.filter { it.type != TokenType.EOF })
        Result.Ok(lists.map { parseComplex(it) })
    } catch (e: Fail) {
        Result.Error(e.message ?: "invalid selector", e.at)
    }

    private fun splitTopLevel(tokens: List<Token>): List<List<Token>> {
        val out = ArrayList<List<Token>>()
        var depth = 0
        var start = 0
        for ((i, t) in tokens.withIndex()) {
            when (t.type) {
                TokenType.FUNCTION, TokenType.LPAREN -> depth++
                TokenType.RPAREN -> depth--
                TokenType.COMMA -> if (depth == 0) {
                    out += tokens.subList(start, i); start = i + 1
                }
                else -> {}
            }
        }
        out += tokens.subList(start, tokens.size)
        return out
    }

    private fun parseComplex(raw: List<Token>): Selector {
        val tokens = raw.dropWhile { it.type == TokenType.WHITESPACE }.dropLastWhile { it.type == TokenType.WHITESPACE }
        if (tokens.isEmpty()) throw Fail("empty selector", raw.firstOrNull())
        val compounds = ArrayList<Compound>()
        val combinators = ArrayList<Combinator>()
        var i = 0
        var pending: Combinator? = null
        while (i < tokens.size) {
            val t = tokens[i]
            val explicit = when {
                t.isDelim('>') -> Combinator.CHILD
                t.isDelim('+') -> Combinator.NEXT_SIBLING
                t.isDelim('~') -> Combinator.SUBSEQUENT_SIBLING
                else -> null
            }
            when {
                t.type == TokenType.WHITESPACE -> {
                    if (pending == null) pending = Combinator.DESCENDANT
                    i++
                }
                explicit != null -> {
                    if (compounds.isEmpty()) throw Fail("selector can't start with '${t.text}'", t)
                    if (pending != null && pending != Combinator.DESCENDANT) throw Fail("two combinators in a row", t)
                    pending = explicit
                    i++
                }
                else -> {
                    if (compounds.isNotEmpty()) {
                        combinators += pending ?: throw Fail("missing combinator", t)
                    }
                    pending = null
                    val (compound, next) = parseCompound(tokens, i)
                    compounds += compound
                    i = next
                }
            }
        }
        if (pending != null && pending != Combinator.DESCENDANT) throw Fail("selector ends with a combinator", tokens.last())
        return Selector(compounds, combinators)
    }

    /** Parses one compound starting at [start]; returns it and the index after it. */
    private fun parseCompound(tokens: List<Token>, start: Int): Pair<Compound, Int> {
        val parts = ArrayList<SimpleSelector>()
        var i = start
        loop@ while (i < tokens.size) {
            val t = tokens[i]
            when {
                t.type == TokenType.IDENT -> {
                    if (parts.isNotEmpty()) throw Fail("type selector '${t.text}' must come first in a compound", t)
                    parts += SimpleSelector.Type(t.text.lowercase()); i++
                }
                t.isDelim('*') -> {
                    if (parts.isNotEmpty()) throw Fail("'*' must come first in a compound", t)
                    parts += SimpleSelector.Universal; i++
                }
                t.type == TokenType.HASH -> {
                    parts += SimpleSelector.Id(t.text); i++
                }
                t.isDelim('.') -> {
                    val n = tokens.getOrNull(i + 1)
                    if (n?.type != TokenType.IDENT) throw Fail("expected a class name after '.'", t)
                    parts += SimpleSelector.Class(n.text); i += 2
                }
                t.type == TokenType.COLON -> {
                    val n = tokens.getOrNull(i + 1) ?: throw Fail("expected a pseudo-class after ':'", t)
                    when {
                        n.type == TokenType.COLON -> throw Fail("pseudo-elements (::${tokens.getOrNull(i + 2)?.text ?: ""}) are not supported", n)
                        n.type == TokenType.IDENT -> {
                            val name = n.text.lowercase()
                            parts += STATES[name]?.let { SimpleSelector.State(it) }
                                ?: if (name in STRUCTURAL) SimpleSelector.Structural(name) else throw Fail("unsupported pseudo-class ':$name'", n)
                            i += 2
                        }
                        n.type == TokenType.FUNCTION && n.text.lowercase() in NTH -> {
                            val close = (i + 2 until tokens.size).firstOrNull { tokens[it].type == TokenType.RPAREN }
                                ?: throw Fail("unclosed ':${n.text}('", n)
                            val (a, b) = parseAnPlusB(tokens.subList(i + 2, close)) ?: throw Fail("invalid argument for ':${n.text}()'", n)
                            val name = n.text.lowercase()
                            parts += SimpleSelector.NthChild(a, b, fromEnd = "-last-" in name, ofType = name.endsWith("of-type"))
                            i = close + 1
                        }
                        n.type == TokenType.FUNCTION && n.text.equals("not", ignoreCase = true) -> {
                            var depth = 1
                            var j = i + 2
                            while (j < tokens.size && depth > 0) {
                                if (tokens[j].type == TokenType.FUNCTION || tokens[j].type == TokenType.LPAREN) depth++
                                if (tokens[j].type == TokenType.RPAREN) depth--
                                j++
                            }
                            if (depth != 0) throw Fail("unclosed ':not('", n)
                            val inner = splitTopLevel(tokens.subList(i + 2, j - 1)).map { part ->
                                val trimmed = part.dropWhile { it.type == TokenType.WHITESPACE }.dropLastWhile { it.type == TokenType.WHITESPACE }
                                val (c, end) = parseCompound(trimmed, 0)
                                if (end != trimmed.size) throw Fail(":not() only accepts compound selectors", n)
                                c
                            }
                            parts += SimpleSelector.Not(inner)
                            i = j
                        }
                        else -> throw Fail("unsupported pseudo-class ':${n.text}'", n)
                    }
                }
                t.type == TokenType.LBRACKET -> {
                    var j = i + 1
                    fun skipWs() { while (j < tokens.size && tokens[j].type == TokenType.WHITESPACE) j++ }
                    skipWs()
                    val name = tokens.getOrNull(j)?.takeIf { it.type == TokenType.IDENT } ?: throw Fail("expected an attribute name after '['", t)
                    j++; skipWs()
                    var op: String? = null
                    var value: String? = null
                    val o = tokens.getOrNull(j)
                    if (o != null && o.type == TokenType.DELIM) {
                        op = when {
                            o.text == "=" -> "="
                            o.text in listOf("^", "$", "*") && tokens.getOrNull(j + 1)?.isDelim('=') == true -> { j++; o.text + "=" }
                            else -> throw Fail("unsupported attribute operator '${o.text}'", o)
                        }
                        j++; skipWs()
                        val v = tokens.getOrNull(j)?.takeIf { it.type == TokenType.IDENT || it.type == TokenType.STRING || it.type == TokenType.NUMBER }
                            ?: throw Fail("expected a value in attribute selector", o)
                        value = v.text
                        j++; skipWs()
                    }
                    if (tokens.getOrNull(j)?.type != TokenType.RBRACKET) throw Fail("expected ']'", t)
                    parts += SimpleSelector.Attribute(name.text.lowercase(), op, value)
                    i = j + 1
                }
                else -> break@loop
            }
        }
        if (parts.isEmpty()) throw Fail("unexpected '${tokens.getOrNull(start)}'", tokens.getOrNull(start))
        return Compound(parts) to i
    }
}
