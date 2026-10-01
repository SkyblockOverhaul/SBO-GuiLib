package net.sbo.guilib.core.css

import java.util.EnumMap

/**
 * Matches rules against elements and runs the cascade: origin → !important → specificity → source order,
 * then inheritance, `var()` substitution and unit resolution.
 */
class StyleEngine(sheets: List<Stylesheet> = emptyList()) {

    private class IndexedRule(val rule: StyleRule, val selector: Selector, val origin: Origin, val order: Int)

    private val byId = HashMap<String, MutableList<IndexedRule>>()
    private val byClass = HashMap<String, MutableList<IndexedRule>>()
    private val byTag = HashMap<String, MutableList<IndexedRule>>()
    private val universal = ArrayList<IndexedRule>()

    /** True if some selector tests an interactive state (`:hover` …) on an ancestor/sibling, e.g. `.card:hover .title`. */
    var dependsOnAncestorState = false
        private set

    /** True if some selector depends on sibling position (`:first-child`, `+`, `~` …). */
    var usesStructural = false
        private set

    /** Bumped whenever the stylesheets change, so callers can invalidate cached styles. */
    var generation = 0
        private set

    var stylesheets: List<Stylesheet> = emptyList()
        set(value) {
            field = value
            rebuildIndex()
        }

    init {
        stylesheets = sheets
    }

    private fun rebuildIndex() {
        byId.clear(); byClass.clear(); byTag.clear(); universal.clear()
        dependsOnAncestorState = false
        usesStructural = false
        var order = 0
        for (sheet in stylesheets) {
            for (rule in sheet.rules) {
                for (selector in rule.selectors) {
                    val ir = IndexedRule(rule, selector, sheet.origin, order)
                    if (selector.compounds.dropLast(1).any { c -> c.parts.any(::involvesState) }) dependsOnAncestorState = true
                    if (selector.combinators.any { it == Combinator.NEXT_SIBLING || it == Combinator.SUBSEQUENT_SIBLING } ||
                        selector.compounds.any { c -> c.parts.any(::involvesStructure) }) usesStructural = true
                    val subject = selector.subject.parts
                    val id = subject.firstNotNullOfOrNull { it as? SimpleSelector.Id }
                    val cls = subject.firstNotNullOfOrNull { it as? SimpleSelector.Class }
                    val tag = subject.firstNotNullOfOrNull { it as? SimpleSelector.Type }
                    when {
                        id != null -> byId.getOrPut(id.id) { ArrayList() } += ir
                        cls != null -> byClass.getOrPut(cls.name) { ArrayList() } += ir
                        tag != null -> byTag.getOrPut(tag.name) { ArrayList() } += ir
                        else -> universal += ir
                    }
                }
                order++
            }
        }
        generation++
    }

    private fun involvesState(s: SimpleSelector): Boolean = when (s) {
        is SimpleSelector.State -> true
        is SimpleSelector.Structural -> s.kind == "enabled"
        is SimpleSelector.Not -> s.inner.any { c -> c.parts.any(::involvesState) }
        else -> false
    }

    private fun involvesStructure(s: SimpleSelector): Boolean = when (s) {
        is SimpleSelector.Structural -> s.kind != "enabled" && s.kind != "root"
        is SimpleSelector.Not -> s.inner.any { c -> c.parts.any(::involvesStructure) }
        else -> false
    }

    private class Matched(val decl: Declaration, val rank: Int, val specificity: Int, val order: Int)

    /** Returns all rules matching [el], each with its highest matching specificity. Exposed for tests/devtools. */
    fun matchingRules(el: Selectable): List<Pair<StyleRule, Int>> {
        val best = LinkedHashMap<StyleRule, Int>()
        fun consider(list: List<IndexedRule>?) {
            list ?: return
            for (ir in list) {
                if (ir.selector.matches(el)) {
                    val prev = best[ir.rule]
                    if (prev == null || ir.selector.specificity > prev) best[ir.rule] = ir.selector.specificity
                }
            }
        }
        el.styleId?.let { consider(byId[it]) }
        for (c in el.styleClasses) consider(byClass[c])
        consider(byTag[el.styleTag.lowercase()])
        consider(universal)
        return best.entries.map { it.key to it.value }
    }

    /**
     * Computes the style of [el]. [inline] are the declarations from its `style` attribute,
     * [parent] the computed style of its parent (or `null` for the root).
     */
    fun compute(el: Selectable, inline: List<Declaration>, parent: ComputedStyle?, ctx: StyleContext): ComputedStyle {
        val originOf = HashMap<StyleRule, Origin>()
        val orderOf = HashMap<StyleRule, Int>()
        // Recover origin/order for matched rules (cheap; the index keeps them per selector).
        fun remember(list: List<IndexedRule>?) = list?.forEach { originOf[it.rule] = it.origin; orderOf[it.rule] = it.order }
        el.styleId?.let { remember(byId[it]) }
        for (c in el.styleClasses) remember(byClass[c])
        remember(byTag[el.styleTag.lowercase()])
        remember(universal)

        val matched = ArrayList<Matched>()
        for ((rule, spec) in matchingRules(el)) {
            val origin = originOf.getValue(rule)
            val order = orderOf.getValue(rule)
            for (d in rule.declarations) matched += Matched(d, rank(origin, d.important), spec, order)
        }
        for (d in inline) matched += Matched(d, rank(Origin.INLINE, d.important), Int.MAX_VALUE, Int.MAX_VALUE)
        matched.sortWith(compareBy<Matched>({ it.rank }, { it.specificity }, { it.order }))
        return computeFromCascade(matched.map { it.decl }, parent, ctx, el.styleTag)
    }

    companion object {
        private fun rank(origin: Origin, important: Boolean): Int = if (!important) origin.ordinal else when (origin) {
            Origin.AUTHOR -> 3
            Origin.INLINE -> 4
            Origin.USER_AGENT -> 5
        }

        /** Cascade already sorted ascending by priority → computed style. */
        internal fun computeFromCascade(sorted: List<Declaration>, parent: ComputedStyle?, ctx: StyleContext, where: String): ComputedStyle {
            // 1. Custom properties inherit and are applied first so var() can see them.
            val custom = LinkedHashMap<String, List<ComponentValue>>(parent?.customProperties ?: emptyMap())
            for (d in sorted) if (d.isCustom) custom[d.property] = d.value

            // 2. Winning longhand value per property (later = higher priority).
            val winners = EnumMap<Prop, Any>(Prop::class.java)
            val resolver = VarResolver(custom)
            for (d in sorted) {
                if (d.isCustom) continue
                val longhands = d.parsed ?: run {
                    val substituted = resolver.substitute(d.value)
                    val parsed = substituted?.let { Properties.parse(d.property, CssParser.trimWhitespace(it)) }
                    if (parsed == null) {
                        CssParser.warnAt(d.location, "value '${d.value.joinToString("")}' of '${d.property}' is invalid after var() substitution; using 'unset'")
                        (Properties.parse(d.property, listOf(TokenValue(Token(TokenType.IDENT, "unset", d.line, d.col)))) ?: emptyList())
                    } else parsed
                }
                for ((p, v) in longhands) winners[p] = v
            }
            return computeFrom(winners, custom, parent, ctx, where)
        }

        internal fun computeFrom(
            winners: Map<Prop, Any>,
            custom: Map<String, List<ComponentValue>>,
            parent: ComputedStyle?,
            ctx: StyleContext,
            @Suppress("UNUSED_PARAMETER") where: String,
        ): ComputedStyle {
            val values = arrayOfNulls<Any?>(Prop.entries.size)
            val parentFontSize = parent?.fontSize ?: (Prop.FONT_SIZE.initial as Float)

            fun specified(p: Prop): Any? {
                val v = winners[p]
                val inherit = when (v) {
                    CssWide.INHERIT -> true
                    CssWide.INITIAL -> false
                    CssWide.UNSET, null -> p.inherited
                    else -> return v
                }
                if (inherit && parent != null) return InheritedMarker(parent[p])
                return p.initial
            }

            // font-size first: em/% are relative to the parent's font size.
            val fontSize = when (val v = specified(Prop.FONT_SIZE)) {
                is InheritedMarker -> v.value as Float
                is Length -> when (v.unit) {
                    "calc" -> v.calc!!.resolveUnits(parentFontSize, ctx).eval(parentFontSize) ?: parentFontSize
                    "em" -> v.value * parentFontSize
                    "%" -> v.value / 100f * parentFontSize
                    else -> toPx(v, parentFontSize, ctx)
                }
                else -> v as Float
            }
            values[Prop.FONT_SIZE.ordinal] = fontSize

            // color next, so currentColor works everywhere else.
            val color = when (val v = specified(Prop.COLOR)) {
                is InheritedMarker -> v.value as Int
                CurrentColor -> parent?.color ?: Colors.WHITE
                else -> v as Int
            }
            values[Prop.COLOR.ordinal] = color

            for (p in Prop.entries) {
                if (p == Prop.FONT_SIZE || p == Prop.COLOR) continue
                val v = specified(p)
                values[p.ordinal] = if (v is InheritedMarker) v.value else resolve(p, v, fontSize, color, ctx)
            }
            return ComputedStyle.create(values, custom)
        }

        private class InheritedMarker(val value: Any?)

        private fun resolve(p: Prop, v: Any?, fontSize: Float, color: Int, ctx: StyleContext): Any? = when (v) {
            is Length -> when {
                p == Prop.BORDER_TOP_WIDTH || p == Prop.BORDER_RIGHT_WIDTH || p == Prop.BORDER_BOTTOM_WIDTH || p == Prop.BORDER_LEFT_WIDTH -> toPx(v, fontSize, ctx)
                v.isCalc -> {
                    val node = v.calc!!.resolveUnits(fontSize, ctx)
                    // Without percentages the result is a plain length.
                    if (node.hasPercent) Dim.Calc(node) else Dim.Px(node.eval(null) ?: 0f)
                }
                v.isPercent -> Dim.Pct(v.value)
                else -> Dim.Px(toPx(v, fontSize, ctx))
            }
            CurrentColor -> color
            Properties.NoImage -> null
            is Properties.LineHeightLength -> LineHeight.Px(toPx(v.length, fontSize, ctx))
            is Properties.TextShadowValue -> TextShadow(toPx(v.x, fontSize, ctx), toPx(v.y, fontSize, ctx), if (v.color == CurrentColor) color else v.color)
            is List<*> -> if (p == Prop.BACKGROUND_IMAGE) v.map { resolveLayer(it as BackgroundLayer, fontSize, color, ctx) } else v
            is Properties.ScrollbarColor -> Pair(if (v.thumb == CurrentColor) color else v.thumb as Int, if (v.track == CurrentColor) color else v.track as Int)
            else -> v
        }

        /** Resolves currentColor and font/viewport-relative units inside a background layer. */
        private fun resolveLayer(layer: BackgroundLayer, fontSize: Float, color: Int, ctx: StyleContext): BackgroundLayer {
            if (layer !is BackgroundLayer.Gradient) return layer
            fun len(l: Length) = if (l.isPercent || l.unit == "px") l else Length(toPx(l, fontSize, ctx), "px")
            return layer.copy(
                stops = layer.stops.map { BackgroundLayer.Stop(if (it.color == CurrentColor) color else it.color, it.position?.let(::len)) },
                centerX = len(layer.centerX),
                centerY = len(layer.centerY),
                explicitSize = layer.explicitSize?.let { (a, b) -> len(a) to len(b) },
            )
        }

        fun toPx(l: Length, fontSize: Float, ctx: StyleContext): Float = when (l.unit) {
            "px" -> l.value
            "calc" -> l.calc!!.resolveUnits(fontSize, ctx).eval(null) ?: 0f
            "em" -> l.value * fontSize
            "rem" -> l.value * ctx.rootFontSize
            "vw" -> l.value * ctx.viewportWidth / 100f
            "vh" -> l.value * ctx.viewportHeight / 100f
            "vmin" -> l.value * minOf(ctx.viewportWidth, ctx.viewportHeight) / 100f
            "vmax" -> l.value * maxOf(ctx.viewportWidth, ctx.viewportHeight) / 100f
            else -> l.value
        }
    }
}

/** Substitutes `var(--name, fallback)` references; detects cycles. */
internal class VarResolver(private val custom: Map<String, List<ComponentValue>>) {
    private val resolved = HashMap<String, List<ComponentValue>?>()
    private val visiting = HashSet<String>()

    /** Returns the substituted values, or `null` if a referenced variable is missing without fallback (or cyclic). */
    fun substitute(values: List<ComponentValue>): List<ComponentValue>? {
        val out = ArrayList<ComponentValue>(values.size)
        for (v in values) {
            when (v) {
                is FunctionValue -> if (v.name == "var") {
                    out += resolveVar(v) ?: return null
                } else {
                    out += FunctionValue(v.name, substitute(v.args) ?: return null, v.line, v.col)
                }
                is BlockValue -> out += BlockValue(v.open, substitute(v.content) ?: return null, v.line, v.col)
                is TokenValue -> out += v
            }
        }
        return out
    }

    private fun resolveVar(f: FunctionValue): List<ComponentValue>? {
        val args = f.args
        val nameIdx = args.indexOfFirst { !(it is TokenValue && it.token.type == TokenType.WHITESPACE) }
        val nameTok = (args.getOrNull(nameIdx) as? TokenValue)?.token ?: return null
        if (nameTok.type != TokenType.IDENT || !nameTok.text.startsWith("--")) return null
        val commaIdx = args.indexOfFirst { it is TokenValue && it.token.type == TokenType.COMMA }
        val fallback = if (commaIdx >= 0) CssParser.trimWhitespace(args.subList(commaIdx + 1, args.size)) else null

        val value = lookup(nameTok.text)
        if (value != null) return value
        return fallback?.let { substitute(it) }
    }

    private fun lookup(name: String): List<ComponentValue>? {
        if (resolved.containsKey(name)) return resolved[name]
        val raw = custom[name] ?: return null
        if (!visiting.add(name)) return null // cycle
        val result = substitute(raw)
        visiting.remove(name)
        resolved[name] = result
        return result
    }
}
