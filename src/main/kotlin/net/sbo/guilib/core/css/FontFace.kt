package net.sbo.guilib.core.css

/**
 * An `@font-face` rule: [family] (lower case) is drawn with the first of [sources] that exists, for weights in
 * [weightMin]..[weightMax] (a single weight unless the rule gives a range) and the given style.
 * Sources are resource locations (`url("mymod:fonts/x.ttf")`); `local()` and web URLs are not supported.
 */
data class FontFace(
    val family: String,
    val sources: List<String>,
    val weightMin: Int,
    val weightMax: Int,
    val italic: Boolean,
)

internal object FontFaceParser {
    /** Builds a [FontFace] from the descriptors of one `@font-face` block, or reports why it can't. */
    fun parse(decls: List<Declaration>, warn: (Declaration?, String) -> Unit): FontFace? {
        var family: String? = null
        var sources = emptyList<String>()
        var weight = 400 to 400
        var italic = false
        for (d in decls) {
            val words = Properties.words(d.value)
            when (d.property) {
                "font-family" -> family = familyName(words) ?: return null.also { warn(d, "invalid font-family '${d.value.joinToString("")}' in @font-face") }
                "src" -> sources = sources(d.value, d, warn)
                "font-weight" -> weight = weights(words) ?: return null.also { warn(d, "invalid font-weight '${d.value.joinToString("")}' in @font-face") }
                "font-style" -> italic = when (words.firstOrNull()?.let { tok(it) }?.text?.lowercase()) {
                    "normal" -> false
                    "italic", "oblique" -> true
                    else -> return null.also { warn(d, "invalid font-style '${d.value.joinToString("")}' in @font-face") }
                }
                // Browser hints that change nothing here.
                "font-display", "font-stretch", "unicode-range", "font-feature-settings", "font-variation-settings",
                "ascent-override", "descent-override", "line-gap-override", "size-adjust" -> {}
                else -> warn(d, "unknown @font-face descriptor '${d.property}' was ignored")
            }
        }
        if (family == null) return null.also { warn(null, "@font-face without font-family was ignored") }
        if (sources.isEmpty()) return null.also { warn(null, "@font-face '$family' has no usable src and was ignored") }
        return FontFace(family, sources, weight.first, weight.second, italic)
    }

    private fun tok(v: ComponentValue) = (v as? TokenValue)?.token

    private fun familyName(words: List<ComponentValue>): String? {
        val toks = words.map { tok(it) ?: return null }
        if (toks.size == 1 && toks[0].type == TokenType.STRING) return toks[0].text.lowercase().takeIf { it.isNotBlank() }
        if (toks.isEmpty() || toks.any { it.type != TokenType.IDENT }) return null
        return toks.joinToString(" ") { it.text.lowercase() }
    }

    private fun weight(v: ComponentValue): Int? {
        val t = tok(v) ?: return null
        return when {
            t.isIdent("normal") -> 400
            t.isIdent("bold") -> 700
            t.type == TokenType.NUMBER && t.number in 1.0..1000.0 -> t.number.toInt()
            else -> null
        }
    }

    /** `400`, `bold`, or a range `100 900` (variable fonts). */
    private fun weights(words: List<ComponentValue>): Pair<Int, Int>? = when (words.size) {
        1 -> weight(words[0])?.let { it to it }
        2 -> {
            val a = weight(words[0]) ?: return null
            val b = weight(words[1]) ?: return null
            minOf(a, b) to maxOf(a, b)
        }
        else -> null
    }

    /** `url("a.ttf") format("truetype"), local(Arial), url(b.otf)` → the resource locations, in order. */
    private fun sources(value: List<ComponentValue>, d: Declaration, warn: (Declaration?, String) -> Unit): List<String> {
        val out = ArrayList<String>()
        val parts = ArrayList<MutableList<ComponentValue>>().apply { add(ArrayList()) }
        for (v in Properties.words(value)) {
            if (tok(v)?.type == TokenType.COMMA) parts.add(ArrayList()) else parts.last().add(v)
        }
        for (p in parts) {
            val first = p.firstOrNull() ?: continue
            val url = when {
                tok(first)?.type == TokenType.URL -> tok(first)!!.text
                first is FunctionValue && first.name.equals("url", true) ->
                    Properties.words(first.args).singleOrNull()?.let { tok(it) }?.takeIf { it.type == TokenType.STRING }?.text
                first is FunctionValue && first.name.equals("local", true) -> {
                    warn(d, "local() fonts are not supported, use a resource location (url(\"mymod:fonts/x.ttf\"))"); null
                }
                else -> null
            }
            if (url != null) out += url
        }
        return out
    }
}
