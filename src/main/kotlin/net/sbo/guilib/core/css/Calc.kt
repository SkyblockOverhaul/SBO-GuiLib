package net.sbo.guilib.core.css

/**
 * Expression tree of `calc()`, `min()`, `max()` and `clamp()`.
 *
 * Parsed values contain [Len] leaves; [resolveUnits] turns them into [Px] / [Pct] once font size and viewport are
 * known, and [eval] computes the result for a percentage base (the containing block size) during layout.
 */
sealed interface CalcNode {
    /** Result in px, or `null` if a percentage needs a [base] that isn't known (indefinite size). */
    fun eval(base: Float?): Float?

    /** Converts `em/rem/vw/…` leaves to px; percentages stay. */
    fun resolveUnits(fontSize: Float, ctx: StyleContext): CalcNode

    /** True if some leaf is a percentage. */
    val hasPercent: Boolean

    data class Num(val value: Float) : CalcNode {
        override fun eval(base: Float?) = value
        override fun resolveUnits(fontSize: Float, ctx: StyleContext) = this
        override val hasPercent get() = false
    }

    data class Px(val px: Float) : CalcNode {
        override fun eval(base: Float?) = px
        override fun resolveUnits(fontSize: Float, ctx: StyleContext) = this
        override val hasPercent get() = false
    }

    data class Pct(val pct: Float) : CalcNode {
        override fun eval(base: Float?) = base?.let { it * pct / 100f }
        override fun resolveUnits(fontSize: Float, ctx: StyleContext) = this
        override val hasPercent get() = true
    }

    data class Len(val length: Length) : CalcNode {
        override fun eval(base: Float?) = if (length.isPercent) base?.let { it * length.value / 100f } else length.value
        override fun resolveUnits(fontSize: Float, ctx: StyleContext): CalcNode =
            if (length.isPercent) Pct(length.value) else Px(StyleEngine.toPx(length, fontSize, ctx))
        override val hasPercent get() = length.isPercent
    }

    data class Op(val op: Char, val a: CalcNode, val b: CalcNode) : CalcNode {
        override fun eval(base: Float?): Float? {
            val x = a.eval(base) ?: return null
            val y = b.eval(base) ?: return null
            return when (op) {
                '+' -> x + y
                '-' -> x - y
                '*' -> x * y
                else -> if (y == 0f) null else x / y
            }
        }
        override fun resolveUnits(fontSize: Float, ctx: StyleContext) = Op(op, a.resolveUnits(fontSize, ctx), b.resolveUnits(fontSize, ctx))
        override val hasPercent get() = a.hasPercent || b.hasPercent
    }

    /** `min(...)` / `max(...)`. */
    data class Extreme(val min: Boolean, val args: List<CalcNode>) : CalcNode {
        override fun eval(base: Float?): Float? {
            val values = args.map { it.eval(base) ?: return null }
            return if (min) values.min() else values.max()
        }
        override fun resolveUnits(fontSize: Float, ctx: StyleContext) = Extreme(min, args.map { it.resolveUnits(fontSize, ctx) })
        override val hasPercent get() = args.any { it.hasPercent }
    }

    data class Clamp(val lo: CalcNode, val value: CalcNode, val hi: CalcNode) : CalcNode {
        override fun eval(base: Float?): Float? {
            val l = lo.eval(base) ?: return null
            val v = value.eval(base) ?: return null
            val h = hi.eval(base) ?: return null
            return maxOf(l, minOf(v, h))
        }
        override fun resolveUnits(fontSize: Float, ctx: StyleContext) =
            Clamp(lo.resolveUnits(fontSize, ctx), value.resolveUnits(fontSize, ctx), hi.resolveUnits(fontSize, ctx))
        override val hasPercent get() = lo.hasPercent || value.hasPercent || hi.hasPercent
    }

    companion object {
        val FUNCTIONS = setOf("calc", "min", "max", "clamp")

        /** Parses a `calc()`/`min()`/`max()`/`clamp()` function value, or returns `null` if it's invalid. */
        fun parse(f: FunctionValue): CalcNode? = try {
            parseFunction(f)
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun parseFunction(f: FunctionValue): CalcNode = when (f.name) {
            "calc" -> Parser(f.args).sumToEnd()
            "min", "max" -> Extreme(f.name == "min", splitArgs(f.args).map { Parser(it).sumToEnd() }.also { require(it.isNotEmpty()) })
            "clamp" -> splitArgs(f.args).map { Parser(it).sumToEnd() }.let {
                require(it.size == 3)
                Clamp(it[0], it[1], it[2])
            }
            else -> throw IllegalArgumentException("not a math function")
        }

        private fun splitArgs(values: List<ComponentValue>): List<List<ComponentValue>> {
            val out = ArrayList<List<ComponentValue>>()
            var cur = ArrayList<ComponentValue>()
            for (v in values) {
                if (v is TokenValue && v.token.type == TokenType.COMMA) {
                    out += cur; cur = ArrayList()
                } else cur += v
            }
            out += cur
            return out
        }

        /** Recursive-descent parser over component values (whitespace is skipped). */
        private class Parser(values: List<ComponentValue>) {
            private val items = values.filter { !(it is TokenValue && it.token.type == TokenType.WHITESPACE) }
            private var i = 0

            fun sumToEnd(): CalcNode {
                val n = sum()
                require(i == items.size) { "unexpected trailing input" }
                return n
            }

            private fun sum(): CalcNode {
                var left = product()
                while (i < items.size) {
                    val t = (items[i] as? TokenValue)?.token
                    when {
                        t != null && (t.isDelim('+') || t.isDelim('-')) -> {
                            i++
                            left = Op(t.text[0], left, product())
                        }
                        // Lenient: "100% -20px" (missing space after the minus) is read as a subtraction.
                        t != null && (t.type == TokenType.DIMENSION || t.type == TokenType.NUMBER || t.type == TokenType.PERCENTAGE) && t.number < 0 ->
                            left = Op('+', left, product())
                        else -> return left
                    }
                }
                return left
            }

            private fun product(): CalcNode {
                var left = value()
                while (i < items.size) {
                    val t = (items[i] as? TokenValue)?.token ?: return left
                    if (!t.isDelim('*') && !t.isDelim('/')) return left
                    i++
                    left = Op(t.text[0], left, value())
                }
                return left
            }

            private fun value(): CalcNode {
                require(i < items.size) { "missing value" }
                val v = items[i++]
                return when (v) {
                    is BlockValue -> {
                        require(v.open == '(')
                        Parser(v.content).sumToEnd()
                    }
                    is FunctionValue -> parseFunction(v)
                    is TokenValue -> {
                        val t = v.token
                        when (t.type) {
                            TokenType.NUMBER -> Num(t.number.toFloat())
                            TokenType.PERCENTAGE -> Len(Length(t.number.toFloat(), "%"))
                            TokenType.DIMENSION -> Len(Properties.length(v) ?: throw IllegalArgumentException("unit ${t.unit}"))
                            else -> throw IllegalArgumentException("unexpected '$t'")
                        }
                    }
                }
            }
        }
    }
}
