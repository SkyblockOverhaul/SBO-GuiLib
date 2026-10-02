package net.sbo.guilib.core.css

/**
 * Evaluates an `@supports` condition once, at parse time (what GuiLib supports never changes at runtime):
 * - `(property: value)` – the property is known and GuiLib can parse the value (custom properties and values using
 *   `var()` count as supported, like in browsers)
 * - `selector(…)` – GuiLib can parse the selector (e.g. `selector(:has(a))` is false)
 * - `not …`, `… and …`, `… or …`, parentheses
 * Anything else (unknown functions, malformed conditions) is false, so the block is skipped.
 */
internal object SupportsParser {
    private class Fail(message: String) : Exception(message)

    fun evaluate(prelude: List<ComponentValue>, warn: (String) -> Unit): Boolean {
        val words = Properties.words(prelude)
        return try {
            if (words.isEmpty()) throw Fail("@supports needs a condition")
            condition(words)
        } catch (e: Fail) {
            warn("invalid @supports condition '${prelude.joinToString("").trim()}': ${e.message}; block ignored")
            false
        }
    }

    private fun isWord(v: ComponentValue, w: String) = (v as? TokenValue)?.token?.isIdent(w) == true

    /** `not X` | `X (and X)*` | `X (or X)*` (mixing and/or without parentheses is invalid, as in CSS). */
    private fun condition(words: List<ComponentValue>): Boolean {
        if (isWord(words[0], "not")) {
            if (words.size != 2) throw Fail("'not' takes one parenthesized condition")
            return !inParens(words[1])
        }
        var result = inParens(words[0])
        var op: String? = null
        var i = 1
        while (i < words.size) {
            val w = words[i]
            val next = when {
                isWord(w, "and") -> "and"
                isWord(w, "or") -> "or"
                else -> throw Fail("expected 'and' or 'or' but found '$w'")
            }
            if (op != null && op != next) throw Fail("mix of 'and' and 'or' needs parentheses")
            op = next
            val rhs = inParens(words.getOrNull(i + 1) ?: throw Fail("'$next' needs a condition after it"))
            result = if (next == "and") result && rhs else result || rhs
            i += 2
        }
        return result
    }

    private fun inParens(v: ComponentValue): Boolean = when {
        v is BlockValue && v.open == '(' -> {
            val inner = Properties.words(v.content)
            when {
                inner.isEmpty() -> throw Fail("empty parentheses")
                // A nested condition starts with `not` or another group: "((a) or (b))", "(not (a))".
                isWord(inner[0], "not") || inner[0] is BlockValue || inner[0] is FunctionValue -> condition(inner)
                else -> declaration(v.content)
            }
        }
        v is FunctionValue && v.name.equals("selector", ignoreCase = true) -> {
            val tokens = tokens(v.args)
            tokens.any { it.type != TokenType.WHITESPACE } && SelectorParser.parse(tokens) is SelectorParser.Result.Ok
        }
        // Unknown functions (`font-tech()`, …) are "general enclosed": valid syntax, never supported here.
        v is FunctionValue -> false
        else -> throw Fail("expected '(' but found '$v'")
    }

    /** Back to tokens (text would lose details like the sign in `2n+1`). */
    private fun tokens(values: List<ComponentValue>): List<Token> = values.flatMap { v ->
        when (v) {
            is TokenValue -> listOf(v.token)
            is FunctionValue -> listOf(Token(TokenType.FUNCTION, v.name, v.line, v.col)) + tokens(v.args) + Token(TokenType.RPAREN, ")", v.line, v.col)
            is BlockValue -> {
                val (open, close) = when (v.open) {
                    '(' -> TokenType.LPAREN to TokenType.RPAREN
                    '[' -> TokenType.LBRACKET to TokenType.RBRACKET
                    else -> TokenType.LBRACE to TokenType.RBRACE
                }
                listOf(Token(open, v.open.toString(), v.line, v.col)) + tokens(v.content) + Token(close, "", v.line, v.col)
            }
        }
    }

    /** `property: value` inside parentheses. */
    private fun declaration(content: List<ComponentValue>): Boolean {
        val colon = content.indexOfFirst { (it as? TokenValue)?.token?.type == TokenType.COLON }
        if (colon < 0) throw Fail("expected 'property: value'")
        val nameTok = Properties.words(content.subList(0, colon)).singleOrNull()?.let { (it as? TokenValue)?.token }
        if (nameTok?.type != TokenType.IDENT) throw Fail("expected a property name")
        val value = CssParser.trimWhitespace(content.subList(colon + 1, content.size))
        if (value.isEmpty()) return false
        if (nameTok.text.startsWith("--")) return true
        val name = nameTok.text.lowercase()
        if (!Properties.isKnown(name)) return false
        return CssParser.containsVar(value) || Properties.parse(name, value) != null
    }
}
