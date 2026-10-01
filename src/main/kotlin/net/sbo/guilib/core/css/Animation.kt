package net.sbo.guilib.core.css

import kotlin.math.abs
import kotlin.math.floor

/** CSS easing functions (`ease`, `linear`, `cubic-bezier()`, `steps()`). */
sealed interface TimingFunction {
    /** Maps progress 0..1 to eased progress. */
    fun ease(t: Float): Float

    data class CubicBezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : TimingFunction {
        override fun ease(t: Float): Float {
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            // Solve x(s) = t for the curve parameter s (Newton with bisection fallback), then return y(s).
            fun bez(a: Float, b: Float, s: Float): Float {
                val u = 1f - s
                return 3f * u * u * s * a + 3f * u * s * s * b + s * s * s
            }
            fun dbez(a: Float, b: Float, s: Float): Float {
                val u = 1f - s
                return 3f * u * u * a + 6f * u * s * (b - a) + 3f * s * s * (1f - b)
            }
            var s = t
            repeat(8) {
                val x = bez(x1, x2, s) - t
                val d = dbez(x1, x2, s)
                if (abs(x) < 1e-5f) return bez(y1, y2, s)
                if (abs(d) < 1e-6f) return@repeat
                s = (s - x / d).coerceIn(0f, 1f)
            }
            var lo = 0f
            var hi = 1f
            s = t
            repeat(30) {
                val x = bez(x1, x2, s)
                if (abs(x - t) < 1e-5f) return bez(y1, y2, s)
                if (x < t) lo = s else hi = s
                s = (lo + hi) / 2f
            }
            return bez(y1, y2, s)
        }
    }

    /** `steps(n, jump-start|jump-end|jump-none|jump-both)`; `start` = jump-start, `end` = jump-end. */
    data class Steps(val count: Int, val position: String) : TimingFunction {
        override fun ease(t: Float): Float {
            val jumps = when (position) {
                "jump-none" -> count - 1
                "jump-both" -> count + 1
                else -> count
            }.coerceAtLeast(1)
            var step = floor(t * count)
            if (position == "jump-start" || position == "jump-both") step += 1f
            if (t >= 1f && position != "jump-start" && position != "jump-both") step = count.toFloat()
            return (step / jumps).coerceIn(0f, 1f)
        }
    }

    companion object {
        val LINEAR = CubicBezier(0f, 0f, 1f, 1f)
        val EASE = CubicBezier(0.25f, 0.1f, 0.25f, 1f)
        val EASE_IN = CubicBezier(0.42f, 0f, 1f, 1f)
        val EASE_OUT = CubicBezier(0f, 0f, 0.58f, 1f)
        val EASE_IN_OUT = CubicBezier(0.42f, 0f, 0.58f, 1f)

        fun parse(v: ComponentValue): TimingFunction? {
            if (v is FunctionValue) {
                val args = Properties.words(v.args).filter { !(it is TokenValue && it.token.type == TokenType.COMMA) }
                return when (v.name) {
                    "cubic-bezier" -> {
                        val n = args.map { Properties.number(it) ?: return null }
                        if (n.size != 4 || n[0] !in 0f..1f || n[2] !in 0f..1f) null else CubicBezier(n[0], n[1], n[2], n[3])
                    }
                    "steps" -> {
                        val count = args.firstOrNull()?.let { Properties.number(it) }?.toInt()?.takeIf { it > 0 } ?: return null
                        val pos = when ((args.getOrNull(1) as? TokenValue)?.token?.text?.lowercase()) {
                            null, "end", "jump-end" -> "jump-end"
                            "start", "jump-start" -> "jump-start"
                            "jump-none" -> "jump-none"
                            "jump-both" -> "jump-both"
                            else -> return null
                        }
                        Steps(count, pos)
                    }
                    else -> null
                }
            }
            return when {
                Properties.isIdent(v, "linear") -> LINEAR
                Properties.isIdent(v, "ease") -> EASE
                Properties.isIdent(v, "ease-in") -> EASE_IN
                Properties.isIdent(v, "ease-out") -> EASE_OUT
                Properties.isIdent(v, "ease-in-out") -> EASE_IN_OUT
                Properties.isIdent(v, "step-start") -> Steps(1, "jump-start")
                Properties.isIdent(v, "step-end") -> Steps(1, "jump-end")
                else -> null
            }
        }
    }
}

enum class AnimationDirection { NORMAL, REVERSE, ALTERNATE, ALTERNATE_REVERSE }
enum class AnimationFillMode { NONE, FORWARDS, BACKWARDS, BOTH }
enum class AnimationPlayState { RUNNING, PAUSED }

/** One entry of `transition`. [property] is a CSS property name or `all`. Times in ms. */
data class TransitionSpec(val property: String, val duration: Float, val timing: TimingFunction, val delay: Float)

/** One entry of `animation`. Times in ms; [iterations] may be infinite. */
data class AnimationSpec(
    val name: String,
    val duration: Float,
    val timing: TimingFunction,
    val delay: Float,
    val iterations: Float,
    val direction: AnimationDirection,
    val fillMode: AnimationFillMode,
    val playState: AnimationPlayState,
)

/** A `@keyframes` rule: frames sorted by offset (0..1). */
class Keyframes(val name: String, val frames: List<Keyframe>)

class Keyframe(val offset: Float, val declarations: List<Declaration>)

/** Parsing helpers for the transition/animation properties. */
internal object AnimationParser {
    fun time(v: ComponentValue): Float? {
        val t = (v as? TokenValue)?.token ?: return null
        if (t.type == TokenType.NUMBER && t.number == 0.0) return 0f
        if (t.type != TokenType.DIMENSION) return null
        return when (t.unit) {
            "s" -> t.number.toFloat() * 1000f
            "ms" -> t.number.toFloat()
            else -> null
        }?.takeIf { it >= 0f }
    }

    private fun commaList(values: List<ComponentValue>): List<List<ComponentValue>> =
        BackgroundParser.splitCommas(values).map { Properties.words(it) }

    fun timeList(values: List<ComponentValue>): List<Float>? = commaList(values).map { it.singleOrNull()?.let(::time) ?: return null }
    fun timingList(values: List<ComponentValue>): List<TimingFunction>? = commaList(values).map { it.singleOrNull()?.let(TimingFunction::parse) ?: return null }

    fun propertyList(values: List<ComponentValue>): List<String>? = commaList(values).map { w ->
        val t = (w.singleOrNull() as? TokenValue)?.token?.takeIf { it.type == TokenType.IDENT } ?: return null
        t.text.lowercase()
    }

    fun nameList(values: List<ComponentValue>): List<String>? = commaList(values).map { w ->
        val t = (w.singleOrNull() as? TokenValue)?.token ?: return null
        when (t.type) {
            TokenType.IDENT -> t.text
            TokenType.STRING -> t.text
            else -> return null
        }
    }.let { if (it.size == 1 && it[0].equals("none", true)) emptyList() else it }

    fun iterationList(values: List<ComponentValue>): List<Float>? = commaList(values).map { w ->
        val v = w.singleOrNull() ?: return null
        if (Properties.isIdent(v, "infinite")) Float.POSITIVE_INFINITY else Properties.number(v)?.takeIf { it >= 0f } ?: return null
    }

    inline fun <reified E : Enum<E>> enumList(values: List<ComponentValue>): List<E>? =
        BackgroundParser.splitCommas(values).map { w -> Properties.words(w).singleOrNull()?.let { Properties.keyword<E>(it) } ?: return null }

    /** `transition: opacity 200ms ease-in 50ms, color 1s` → longhand lists. */
    fun transitionShorthand(values: List<ComponentValue>): List<Pair<Prop, Any>>? {
        val props = ArrayList<String>()
        val durations = ArrayList<Float>()
        val timings = ArrayList<TimingFunction>()
        val delays = ArrayList<Float>()
        for (part in commaList(values)) {
            var prop: String? = null
            var duration: Float? = null
            var delay: Float? = null
            var timing: TimingFunction? = null
            for (w in part) {
                val time = time(w)
                val tf = TimingFunction.parse(w)
                when {
                    time != null && duration == null -> duration = time
                    time != null && delay == null -> delay = time
                    tf != null && timing == null -> timing = tf
                    prop == null && w is TokenValue && w.token.type == TokenType.IDENT -> prop = w.token.text.lowercase()
                    else -> return null
                }
            }
            props += prop ?: "all"; durations += duration ?: 0f; timings += timing ?: TimingFunction.EASE; delays += delay ?: 0f
        }
        return listOf(Prop.TRANSITION_PROPERTY to props, Prop.TRANSITION_DURATION to durations, Prop.TRANSITION_TIMING_FUNCTION to timings, Prop.TRANSITION_DELAY to delays)
    }

    /** `animation: pulse 1s ease-in-out infinite alternate` → longhand lists. */
    fun animationShorthand(values: List<ComponentValue>): List<Pair<Prop, Any>>? {
        val names = ArrayList<String>()
        val durations = ArrayList<Float>()
        val timings = ArrayList<TimingFunction>()
        val delays = ArrayList<Float>()
        val counts = ArrayList<Float>()
        val directions = ArrayList<AnimationDirection>()
        val fills = ArrayList<AnimationFillMode>()
        val states = ArrayList<AnimationPlayState>()
        for (part in commaList(values)) {
            var name: String? = null
            var duration: Float? = null
            var delay: Float? = null
            var timing: TimingFunction? = null
            var count: Float? = null
            var direction: AnimationDirection? = null
            var fill: AnimationFillMode? = null
            var state: AnimationPlayState? = null
            for (w in part) {
                val time = time(w)
                when {
                    time != null && duration == null -> duration = time
                    time != null && delay == null -> delay = time
                    timing == null && TimingFunction.parse(w) != null -> timing = TimingFunction.parse(w)
                    count == null && Properties.isIdent(w, "infinite") -> count = Float.POSITIVE_INFINITY
                    count == null && Properties.number(w) != null -> count = Properties.number(w)
                    direction == null && Properties.keyword<AnimationDirection>(w) != null -> direction = Properties.keyword<AnimationDirection>(w)
                    fill == null && Properties.keyword<AnimationFillMode>(w) != null && !Properties.isIdent(w, "none") -> fill = Properties.keyword<AnimationFillMode>(w)
                    state == null && Properties.keyword<AnimationPlayState>(w) != null -> state = Properties.keyword<AnimationPlayState>(w)
                    name == null && w is TokenValue && (w.token.type == TokenType.IDENT || w.token.type == TokenType.STRING) -> name = w.token.text
                    else -> return null
                }
            }
            if (name == null || name.equals("none", true)) continue
            names += name; durations += duration ?: 0f; timings += timing ?: TimingFunction.EASE; delays += delay ?: 0f
            counts += count ?: 1f; directions += direction ?: AnimationDirection.NORMAL; fills += fill ?: AnimationFillMode.NONE
            states += state ?: AnimationPlayState.RUNNING
        }
        return listOf(
            Prop.ANIMATION_NAME to names, Prop.ANIMATION_DURATION to durations, Prop.ANIMATION_TIMING_FUNCTION to timings,
            Prop.ANIMATION_DELAY to delays, Prop.ANIMATION_ITERATION_COUNT to counts, Prop.ANIMATION_DIRECTION to directions,
            Prop.ANIMATION_FILL_MODE to fills, Prop.ANIMATION_PLAY_STATE to states,
        )
    }

    /** Pairs up comma lists like CSS: shorter lists repeat. */
    fun <T> at(list: List<T>, i: Int, default: T): T = if (list.isEmpty()) default else list[i % list.size]
}
