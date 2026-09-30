package net.sbo.guilib.core.css

/** Token types, loosely following CSS Syntax Level 3. */
enum class TokenType {
    IDENT, FUNCTION, AT_KEYWORD, HASH, STRING, URL, NUMBER, PERCENTAGE, DIMENSION,
    WHITESPACE, COLON, SEMICOLON, COMMA, LBRACE, RBRACE, LPAREN, RPAREN, LBRACKET, RBRACKET, DELIM, EOF
}

data class Token(
    val type: TokenType,
    /** Ident/function/at-keyword/hash name, string contents, url, delim char or the unit-less source text of a number. */
    val text: String,
    val line: Int,
    val col: Int,
    val number: Double = 0.0,
    val unit: String = "",
) {
    fun isDelim(c: Char) = type == TokenType.DELIM && text.length == 1 && text[0] == c
    fun isIdent(name: String) = type == TokenType.IDENT && text.equals(name, ignoreCase = true)

    override fun toString(): String = when (type) {
        TokenType.IDENT, TokenType.DELIM -> text
        TokenType.FUNCTION -> "$text("
        TokenType.AT_KEYWORD -> "@$text"
        TokenType.HASH -> "#$text"
        TokenType.STRING -> "\"$text\""
        TokenType.URL -> "url($text)"
        TokenType.NUMBER -> fmt(number)
        TokenType.PERCENTAGE -> fmt(number) + "%"
        TokenType.DIMENSION -> fmt(number) + unit
        TokenType.WHITESPACE -> " "
        TokenType.COLON -> ":"
        TokenType.SEMICOLON -> ";"
        TokenType.COMMA -> ","
        TokenType.LBRACE -> "{"
        TokenType.RBRACE -> "}"
        TokenType.LPAREN -> "("
        TokenType.RPAREN -> ")"
        TokenType.LBRACKET -> "["
        TokenType.RBRACKET -> "]"
        TokenType.EOF -> ""
    }

    private fun fmt(d: Double) = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
}

/** Turns CSS source text into tokens. Comments are dropped, positions are 1-based. */
class Tokenizer(private val src: String) {
    private var pos = 0
    private var line = 1
    private var col = 1

    fun tokenize(): List<Token> {
        val out = ArrayList<Token>()
        while (true) {
            val t = next()
            out += t
            if (t.type == TokenType.EOF) return out
        }
    }

    private fun peek(offset: Int = 0): Char = if (pos + offset < src.length) src[pos + offset] else '\u0000'

    private fun advance(): Char {
        val c = src[pos++]
        if (c == '\n') {
            line++; col = 1
        } else col++
        return c
    }

    private fun next(): Token {
        skipComments()
        if (pos >= src.length) return Token(TokenType.EOF, "", line, col)
        val l = line
        val c0 = col
        val c = peek()
        fun tok(type: TokenType, text: String = c.toString()) = Token(type, text, l, c0)

        return when {
            c.isWhitespace() -> {
                while (pos < src.length && peek().isWhitespace()) advance()
                tok(TokenType.WHITESPACE, " ")
            }
            c == '"' || c == '\'' -> tok(TokenType.STRING, readString(advance()))
            c == '#' -> {
                advance()
                if (isNameChar(peek()) || peek() == '\\') tok(TokenType.HASH, readName()) else tok(TokenType.DELIM, "#")
            }
            c == '@' -> {
                advance()
                if (startsIdent(0)) tok(TokenType.AT_KEYWORD, readName()) else tok(TokenType.DELIM, "@")
            }
            startsNumber() -> readNumeric(l, c0)
            startsIdent(0) -> readIdentLike(l, c0)
            else -> {
                advance()
                when (c) {
                    ':' -> tok(TokenType.COLON)
                    ';' -> tok(TokenType.SEMICOLON)
                    ',' -> tok(TokenType.COMMA)
                    '{' -> tok(TokenType.LBRACE)
                    '}' -> tok(TokenType.RBRACE)
                    '(' -> tok(TokenType.LPAREN)
                    ')' -> tok(TokenType.RPAREN)
                    '[' -> tok(TokenType.LBRACKET)
                    ']' -> tok(TokenType.RBRACKET)
                    else -> tok(TokenType.DELIM)
                }
            }
        }
    }

    private fun skipComments() {
        while (peek() == '/' && peek(1) == '*') {
            advance(); advance()
            while (pos < src.length && !(peek() == '*' && peek(1) == '/')) advance()
            if (pos < src.length) {
                advance(); advance()
            }
        }
    }

    private fun readString(quote: Char): String {
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = advance()
            when {
                c == quote -> return sb.toString()
                c == '\\' && pos < src.length -> sb.append(readEscape())
                c == '\n' -> return sb.toString() // bad string, recover
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun readEscape(): String {
        // Called after the backslash was consumed.
        val hex = StringBuilder()
        while (hex.length < 6 && peek().isHexDigit()) hex.append(advance())
        if (hex.isNotEmpty()) {
            if (peek().isWhitespace()) advance()
            return String(Character.toChars(hex.toString().toInt(16).coerceIn(0, 0x10FFFF)))
        }
        return if (pos < src.length) advance().toString() else ""
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun isNameStart(c: Char) = c.isLetter() || c == '_' || c.code >= 0x80
    private fun isNameChar(c: Char) = isNameStart(c) || c.isDigit() || c == '-'

    private fun startsIdent(offset: Int): Boolean {
        val c = peek(offset)
        return when {
            c == '-' -> isNameStart(peek(offset + 1)) || peek(offset + 1) == '-' || (peek(offset + 1) == '\\')
            c == '\\' -> true
            else -> isNameStart(c)
        }
    }

    private fun startsNumber(): Boolean {
        val c = peek()
        return when {
            c.isDigit() -> true
            c == '.' -> peek(1).isDigit()
            c == '+' || c == '-' -> peek(1).isDigit() || (peek(1) == '.' && peek(2).isDigit())
            else -> false
        }
    }

    private fun readName(): String {
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = peek()
            when {
                isNameChar(c) -> sb.append(advance())
                c == '\\' -> {
                    advance(); sb.append(readEscape())
                }
                else -> break
            }
        }
        return sb.toString()
    }

    private fun readNumeric(l: Int, c0: Int): Token {
        val sb = StringBuilder()
        if (peek() == '+' || peek() == '-') sb.append(advance())
        while (peek().isDigit()) sb.append(advance())
        if (peek() == '.' && peek(1).isDigit()) {
            sb.append(advance())
            while (peek().isDigit()) sb.append(advance())
        }
        if ((peek() == 'e' || peek() == 'E') && (peek(1).isDigit() || ((peek(1) == '+' || peek(1) == '-') && peek(2).isDigit()))) {
            sb.append(advance())
            if (peek() == '+' || peek() == '-') sb.append(advance())
            while (peek().isDigit()) sb.append(advance())
        }
        val value = sb.toString().toDouble()
        return when {
            peek() == '%' -> {
                advance(); Token(TokenType.PERCENTAGE, sb.toString(), l, c0, value)
            }
            startsIdent(0) -> Token(TokenType.DIMENSION, sb.toString(), l, c0, value, readName().lowercase())
            else -> Token(TokenType.NUMBER, sb.toString(), l, c0, value)
        }
    }

    private fun readIdentLike(l: Int, c0: Int): Token {
        val name = readName()
        if (peek() == '(') {
            advance()
            if (name.equals("url", ignoreCase = true)) {
                while (peek().isWhitespace()) advance()
                if (peek() != '"' && peek() != '\'') {
                    val sb = StringBuilder()
                    while (pos < src.length && peek() != ')') sb.append(advance())
                    if (pos < src.length) advance()
                    return Token(TokenType.URL, sb.toString().trim(), l, c0)
                }
            }
            return Token(TokenType.FUNCTION, name, l, c0)
        }
        return Token(TokenType.IDENT, name, l, c0)
    }
}
