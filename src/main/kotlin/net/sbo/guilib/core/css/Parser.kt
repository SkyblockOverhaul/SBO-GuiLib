package net.sbo.guilib.core.css

import net.sbo.guilib.core.Log

/** A token, or a function / parenthesised block with nested component values. */
sealed interface ComponentValue {
    val line: Int
    val col: Int
}

data class TokenValue(val token: Token) : ComponentValue {
    override val line get() = token.line
    override val col get() = token.col
    override fun toString() = token.toString()
}

data class FunctionValue(val name: String, val args: List<ComponentValue>, override val line: Int, override val col: Int) : ComponentValue {
    override fun toString() = "$name(${args.joinToString("")})"
}

data class BlockValue(val open: Char, val content: List<ComponentValue>, override val line: Int, override val col: Int) : ComponentValue {
    override fun toString(): String {
        val close = when (open) { '(' -> ')'; '[' -> ']'; else -> '}' }
        return "$open${content.joinToString("")}$close"
    }
}

/** Where a stylesheet comes from; later origins win in the cascade (like the web). */
enum class Origin { USER_AGENT, AUTHOR, INLINE }

class Declaration(
    /** Lower-case property name; custom properties (`--foo`) keep their case. */
    val property: String,
    val value: List<ComponentValue>,
    val important: Boolean,
    val source: String,
    val line: Int,
    val col: Int,
) {
    val isCustom get() = property.startsWith("--")
    val location get() = "$source:$line:$col"

    /** Longhand values parsed once at load time; `null` when the value uses `var()` and must be resolved per element. */
    internal var parsed: List<Pair<Prop, Any>>? = null

    override fun toString() = "$property: ${value.joinToString("")}${if (important) " !important" else ""}"
}

/** [media]: the enclosing `@media` rules (all must match; empty = always applies). */
class StyleRule(
    val selectors: List<Selector>,
    val declarations: List<Declaration>,
    val line: Int,
    val media: List<MediaQueryList> = emptyList(),
) {
    fun appliesTo(ctx: StyleContext) = media.isEmpty() || media.all { it.matches(ctx) }
}

class Stylesheet(
    val source: String,
    val rules: List<StyleRule>,
    val origin: Origin = Origin.AUTHOR,
    /** `@keyframes` defined in this sheet, by name. */
    val keyframes: Map<String, Keyframes> = emptyMap(),
    /** `@font-face` rules of this sheet, in order. */
    val fontFaces: List<FontFace> = emptyList(),
) {
    companion object {
        fun parse(text: String, source: String = "<inline>", origin: Origin = Origin.AUTHOR) = CssParser.parseStylesheet(text, source, origin)
    }
}

/**
 * Parses stylesheets and declaration lists. Never throws: problems are reported via [Log.warnOnce]
 * with `file:line:col` and the offending rule/declaration is dropped, as browsers do.
 */
object CssParser {

    fun parseStylesheet(text: String, source: String = "<inline>", origin: Origin = Origin.AUTHOR): Stylesheet {
        val tokens = Tokenizer(text).tokenize()
        val rules = ArrayList<StyleRule>()
        val keyframes = LinkedHashMap<String, Keyframes>()
        val fontFaces = ArrayList<FontFace>()
        parseRules(tokens, source, rules, keyframes, fontFaces, emptyList())
        return Stylesheet(source, rules, origin, keyframes, fontFaces)
    }

    /** Parses a list of rules ([tokens] = a whole sheet or the body of an `@media` block). */
    private fun parseRules(
        tokens: List<Token>, source: String, rules: MutableList<StyleRule>, keyframes: MutableMap<String, Keyframes>,
        fontFaces: MutableList<FontFace>, media: List<MediaQueryList>,
    ) {
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when (t.type) {
                TokenType.EOF -> break
                TokenType.WHITESPACE, TokenType.SEMICOLON -> i++
                TokenType.AT_KEYWORD -> {
                    if (t.text.equals("keyframes", true) || t.text.equals("-webkit-keyframes", true)) {
                        i = parseKeyframes(tokens, i + 1, source, keyframes)
                    } else if (t.text.equals("media", true)) {
                        var open = i + 1
                        while (open < tokens.size && tokens[open].type != TokenType.LBRACE && tokens[open].type != TokenType.SEMICOLON &&
                            tokens[open].type != TokenType.EOF) open++
                        if (open >= tokens.size || tokens[open].type != TokenType.LBRACE) {
                            warn(source, t, "@media without a block was ignored")
                            i = skipAtRule(tokens, i + 1)
                            continue
                        }
                        val queries = MediaParser.parse(toComponentValues(tokens.subList(i + 1, open))) { warn(source, t, it) }
                        val end = findBlockEnd(tokens, open)
                        parseRules(tokens.subList(open + 1, end), source, rules, keyframes, fontFaces, media + queries)
                        i = end + 1
                    } else if (t.text.equals("font-face", true)) {
                        // Fonts are global like in browsers (an enclosing @media doesn't limit them).
                        var open = i + 1
                        while (open < tokens.size && tokens[open].type == TokenType.WHITESPACE) open++
                        if (open >= tokens.size || tokens[open].type != TokenType.LBRACE) {
                            warn(source, t, "@font-face needs a block; rule ignored")
                            i = skipAtRule(tokens, i + 1)
                            continue
                        }
                        val end = findBlockEnd(tokens, open)
                        val decls = parseDeclarationTokens(tokens.subList(open + 1, end), source, descriptors = true)
                        FontFaceParser.parse(decls) { d, msg -> if (d != null) warnAt(d.location, msg) else warn(source, t, msg) }?.let { fontFaces += it }
                        i = end + 1
                    } else {
                        warn(source, t, "at-rule '@${t.text}' is not supported yet and was ignored")
                        i = skipAtRule(tokens, i + 1)
                    }
                }
                else -> {
                    val preludeStart = i
                    while (tokens[i].type != TokenType.LBRACE && tokens[i].type != TokenType.EOF) i++
                    if (tokens[i].type == TokenType.EOF) {
                        warn(source, t, "unexpected end of file, rule without '{' was ignored")
                        break
                    }
                    val prelude = tokens.subList(preludeStart, i)
                    val blockEnd = findBlockEnd(tokens, i)
                    val body = tokens.subList(i + 1, blockEnd)
                    i = if (blockEnd < tokens.size && tokens[blockEnd].type == TokenType.RBRACE) blockEnd + 1 else blockEnd

                    when (val parsed = SelectorParser.parse(prelude)) {
                        is SelectorParser.Result.Error -> warn(source, parsed.at ?: t, "invalid selector '${prelude.joinToString("").trim()}': ${parsed.message}; rule ignored")
                        is SelectorParser.Result.Ok -> {
                            val decls = parseDeclarationTokens(body, source)
                            rules += StyleRule(parsed.selectors, decls, t.line, media)
                        }
                    }
                }
            }
        }
    }

    /** Parses `color: red; padding: 4px` as used by inline `style` attributes. */
    fun parseDeclarations(text: String, source: String = "<style>"): List<Declaration> =
        parseDeclarationTokens(Tokenizer(text).tokenize().filter { it.type != TokenType.EOF }, source)

    /** `@keyframes name { from { … } 50% { … } to { … } }`; returns the index after the rule. */
    private fun parseKeyframes(tokens: List<Token>, start: Int, source: String, out: MutableMap<String, Keyframes>): Int {
        var i = start
        while (i < tokens.size && tokens[i].type == TokenType.WHITESPACE) i++
        val nameTok = tokens.getOrNull(i)
        if (nameTok == null || (nameTok.type != TokenType.IDENT && nameTok.type != TokenType.STRING)) {
            warn(source, nameTok ?: tokens.last(), "@keyframes needs a name; rule ignored")
            return skipAtRule(tokens, i)
        }
        i++
        while (i < tokens.size && tokens[i].type != TokenType.LBRACE) {
            if (tokens[i].type == TokenType.EOF) return i
            i++
        }
        val end = findBlockEnd(tokens, i)
        val body = tokens.subList(i + 1, end)
        val frames = ArrayList<Keyframe>()
        var j = 0
        while (j < body.size) {
            val t = body[j]
            if (t.type == TokenType.WHITESPACE || t.type == TokenType.SEMICOLON) {
                j++; continue
            }
            var k = j
            while (k < body.size && body[k].type != TokenType.LBRACE) k++
            if (k >= body.size) break
            val prelude = body.subList(j, k).filter { it.type != TokenType.WHITESPACE }
            val blockEnd = findBlockEnd(body, k)
            val decls = parseDeclarationTokens(body.subList(k + 1, blockEnd), source)
            val offsets = ArrayList<Float>()
            var valid = true
            for (p in prelude) when {
                p.type == TokenType.COMMA -> {}
                p.isIdent("from") -> offsets += 0f
                p.isIdent("to") -> offsets += 1f
                p.type == TokenType.PERCENTAGE && p.number in 0.0..100.0 -> offsets += p.number.toFloat() / 100f
                else -> valid = false
            }
            if (!valid || offsets.isEmpty()) warn(source, t, "invalid keyframe selector '${prelude.joinToString("")}'; keyframe ignored")
            else offsets.forEach { frames += Keyframe(it, decls) }
            j = blockEnd + 1
        }
        out[nameTok.text] = Keyframes(nameTok.text, frames.sortedBy { it.offset })
        return end + 1
    }

    /** [descriptors]: an at-rule body (`@font-face`) whose names aren't properties, so values are kept unparsed. */
    private fun parseDeclarationTokens(tokens: List<Token>, source: String, descriptors: Boolean = false): List<Declaration> {
        val out = ArrayList<Declaration>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.type == TokenType.WHITESPACE || t.type == TokenType.SEMICOLON) {
                i++; continue
            }
            // A declaration runs until the next top-level ';'.
            var end = i
            var depth = 0
            while (end < tokens.size) {
                val e = tokens[end]
                when (e.type) {
                    TokenType.FUNCTION, TokenType.LPAREN, TokenType.LBRACKET, TokenType.LBRACE -> depth++
                    TokenType.RPAREN, TokenType.RBRACKET, TokenType.RBRACE -> depth--
                    TokenType.SEMICOLON -> if (depth <= 0) break
                    else -> {}
                }
                end++
            }
            parseDeclaration(tokens.subList(i, end), source, descriptors)?.let { out += it }
            i = end + 1
        }
        return out
    }

    private fun parseDeclaration(tokens: List<Token>, source: String, descriptor: Boolean = false): Declaration? {
        val nameTok = tokens.first()
        if (nameTok.type != TokenType.IDENT) {
            warn(source, nameTok, "expected a property name but found '$nameTok'; declaration ignored")
            return null
        }
        var i = 1
        while (i < tokens.size && tokens[i].type == TokenType.WHITESPACE) i++
        if (i >= tokens.size || tokens[i].type != TokenType.COLON) {
            warn(source, nameTok, "expected ':' after '${nameTok.text}'; declaration ignored")
            return null
        }
        var valueTokens = tokens.subList(i + 1, tokens.size).toMutableList()
        var important = false
        // Strip trailing "!important".
        val trimmed = valueTokens.dropLastWhile { it.type == TokenType.WHITESPACE }
        if (trimmed.size >= 2 && trimmed.last().isIdent("important")) {
            val bang = trimmed.dropLast(1).dropLastWhile { it.type == TokenType.WHITESPACE }
            if (bang.isNotEmpty() && bang.last().isDelim('!')) {
                important = true
                valueTokens = bang.dropLast(1).toMutableList()
            }
        }
        val name = if (nameTok.text.startsWith("--")) nameTok.text else nameTok.text.lowercase()
        val value = trimWhitespace(toComponentValues(valueTokens))
        val decl = Declaration(name, value, important, source, nameTok.line, nameTok.col)

        if (decl.isCustom || descriptor) return decl
        if (!Properties.isKnown(name)) {
            warnAt(decl.location, "unknown property '$name'${Properties.suggest(name)?.let { "; did you mean '$it'?" } ?: ""}; declaration ignored")
            return null
        }
        if (value.isEmpty()) {
            warnAt(decl.location, "empty value for '$name'; declaration ignored")
            return null
        }
        if (!containsVar(value)) {
            val parsed = Properties.parse(name, value)
            if (parsed == null) {
                warnAt(decl.location, "invalid value '${value.joinToString("")}' for '$name'; declaration ignored")
                return null
            }
            decl.parsed = parsed
        }
        return decl
    }

    /** Groups a flat token list into nested component values (functions and bracket blocks). */
    fun toComponentValues(tokens: List<Token>): List<ComponentValue> {
        var i = 0
        fun parseList(stop: TokenType?): List<ComponentValue> {
            val out = ArrayList<ComponentValue>()
            while (i < tokens.size) {
                val t = tokens[i]
                if (t.type == TokenType.EOF) break
                if (stop != null && t.type == stop) {
                    i++; return out
                }
                i++
                out += when (t.type) {
                    TokenType.FUNCTION -> FunctionValue(t.text.lowercase(), parseList(TokenType.RPAREN), t.line, t.col)
                    TokenType.LPAREN -> BlockValue('(', parseList(TokenType.RPAREN), t.line, t.col)
                    TokenType.LBRACKET -> BlockValue('[', parseList(TokenType.RBRACKET), t.line, t.col)
                    TokenType.LBRACE -> BlockValue('{', parseList(TokenType.RBRACE), t.line, t.col)
                    else -> TokenValue(t)
                }
            }
            return out
        }
        return parseList(null)
    }

    fun trimWhitespace(values: List<ComponentValue>): List<ComponentValue> {
        fun ws(v: ComponentValue) = v is TokenValue && v.token.type == TokenType.WHITESPACE
        val start = values.indexOfFirst { !ws(it) }
        if (start < 0) return emptyList()
        val end = values.indexOfLast { !ws(it) }
        return values.subList(start, end + 1)
    }

    fun containsVar(values: List<ComponentValue>): Boolean = values.any {
        when (it) {
            is FunctionValue -> it.name == "var" || containsVar(it.args)
            is BlockValue -> containsVar(it.content)
            is TokenValue -> false
        }
    }

    private fun skipAtRule(tokens: List<Token>, from: Int): Int {
        var i = from
        while (i < tokens.size) {
            when (tokens[i].type) {
                TokenType.SEMICOLON -> return i + 1
                TokenType.LBRACE -> {
                    val end = findBlockEnd(tokens, i)
                    return end + 1
                }
                TokenType.EOF -> return i
                else -> i++
            }
        }
        return i
    }

    /** Index of the '}' matching the '{' at [open] (or EOF index). */
    private fun findBlockEnd(tokens: List<Token>, open: Int): Int {
        var depth = 0
        var i = open
        while (i < tokens.size) {
            when (tokens[i].type) {
                TokenType.LBRACE -> depth++
                TokenType.RBRACE -> {
                    depth--
                    if (depth == 0) return i
                }
                TokenType.EOF -> return i
                else -> {}
            }
            i++
        }
        return tokens.size - 1
    }

    private fun warn(source: String, t: Token, msg: String) = warnAt("$source:${t.line}:${t.col}", msg)

    internal fun warnAt(location: String, msg: String) = Log.warnOnce("CSS $location: $msg")
}
