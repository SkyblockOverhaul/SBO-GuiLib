package net.sbo.guilib.core.css

/** CSS-wide keywords, valid for every property. */
enum class CssWide { INHERIT, INITIAL, UNSET }

/** Sentinel for `z-index: auto`. */
const val Z_INDEX_AUTO = Int.MIN_VALUE

/**
 * Every supported longhand property. [initial] is already in computed form except for lengths ([Length]) and
 * [CurrentColor], which [ComputedStyle] resolves.
 */
enum class Prop(val css: String, val inherited: Boolean, val initial: Any?) {
    DISPLAY("display", false, Display.INLINE),
    POSITION("position", false, Position.STATIC),
    TOP("top", false, Dim.Auto),
    RIGHT("right", false, Dim.Auto),
    BOTTOM("bottom", false, Dim.Auto),
    LEFT("left", false, Dim.Auto),
    Z_INDEX("z-index", false, Z_INDEX_AUTO),

    WIDTH("width", false, Dim.Auto),
    HEIGHT("height", false, Dim.Auto),
    MIN_WIDTH("min-width", false, Dim.Auto),
    MIN_HEIGHT("min-height", false, Dim.Auto),
    MAX_WIDTH("max-width", false, Dim.None),
    MAX_HEIGHT("max-height", false, Dim.None),
    BOX_SIZING("box-sizing", false, BoxSizing.BORDER_BOX),

    MARGIN_TOP("margin-top", false, Dim.ZERO),
    MARGIN_RIGHT("margin-right", false, Dim.ZERO),
    MARGIN_BOTTOM("margin-bottom", false, Dim.ZERO),
    MARGIN_LEFT("margin-left", false, Dim.ZERO),
    PADDING_TOP("padding-top", false, Dim.ZERO),
    PADDING_RIGHT("padding-right", false, Dim.ZERO),
    PADDING_BOTTOM("padding-bottom", false, Dim.ZERO),
    PADDING_LEFT("padding-left", false, Dim.ZERO),

    BORDER_TOP_WIDTH("border-top-width", false, 1f),
    BORDER_RIGHT_WIDTH("border-right-width", false, 1f),
    BORDER_BOTTOM_WIDTH("border-bottom-width", false, 1f),
    BORDER_LEFT_WIDTH("border-left-width", false, 1f),
    BORDER_TOP_STYLE("border-top-style", false, BorderStyle.NONE),
    BORDER_RIGHT_STYLE("border-right-style", false, BorderStyle.NONE),
    BORDER_BOTTOM_STYLE("border-bottom-style", false, BorderStyle.NONE),
    BORDER_LEFT_STYLE("border-left-style", false, BorderStyle.NONE),
    BORDER_TOP_COLOR("border-top-color", false, CurrentColor),
    BORDER_RIGHT_COLOR("border-right-color", false, CurrentColor),
    BORDER_BOTTOM_COLOR("border-bottom-color", false, CurrentColor),
    BORDER_LEFT_COLOR("border-left-color", false, CurrentColor),
    BORDER_TOP_LEFT_RADIUS("border-top-left-radius", false, Dim.ZERO),
    BORDER_TOP_RIGHT_RADIUS("border-top-right-radius", false, Dim.ZERO),
    BORDER_BOTTOM_RIGHT_RADIUS("border-bottom-right-radius", false, Dim.ZERO),
    BORDER_BOTTOM_LEFT_RADIUS("border-bottom-left-radius", false, Dim.ZERO),

    BACKGROUND_COLOR("background-color", false, Colors.TRANSPARENT),
    BACKGROUND_IMAGE("background-image", false, null),
    COLOR("color", true, Colors.WHITE),
    OPACITY("opacity", false, 1f),
    VISIBILITY("visibility", true, Visibility.VISIBLE),
    OVERFLOW_X("overflow-x", false, Overflow.VISIBLE),
    OVERFLOW_Y("overflow-y", false, Overflow.VISIBLE),
    SCROLLBAR_WIDTH("scrollbar-width", false, "auto"),
    SCROLLBAR_COLOR("scrollbar-color", true, null),

    FLEX_DIRECTION("flex-direction", false, FlexDirection.ROW),
    FLEX_WRAP("flex-wrap", false, FlexWrap.NOWRAP),
    JUSTIFY_CONTENT("justify-content", false, JustifyContent.FLEX_START),
    ALIGN_ITEMS("align-items", false, AlignItems.STRETCH),
    ALIGN_SELF("align-self", false, AlignSelf.AUTO),
    FLEX_GROW("flex-grow", false, 0f),
    FLEX_SHRINK("flex-shrink", false, 1f),
    FLEX_BASIS("flex-basis", false, Dim.Auto),
    ORDER("order", false, 0),
    ROW_GAP("row-gap", false, Dim.ZERO),
    COLUMN_GAP("column-gap", false, Dim.ZERO),

    FONT_FAMILY("font-family", true, listOf("inter")),
    FONT_SIZE("font-size", true, 8f),
    FONT_WEIGHT("font-weight", true, 400),
    FONT_STYLE("font-style", true, FontStyle.NORMAL),
    LINE_HEIGHT("line-height", true, LineHeight.Normal),
    TEXT_ALIGN("text-align", true, TextAlign.LEFT),
    WHITE_SPACE("white-space", true, WhiteSpace.NORMAL),
    TEXT_OVERFLOW("text-overflow", false, TextOverflow.CLIP),
    TEXT_DECORATION("text-decoration", true, TextDecoration.NONE),
    LETTER_SPACING("letter-spacing", true, Dim.ZERO),
    TEXT_SHADOW("text-shadow", true, null),
    BOX_SHADOW("box-shadow", false, emptyList<BoxShadow>()),

    CURSOR("cursor", true, Cursor.AUTO),
    POINTER_EVENTS("pointer-events", true, PointerEvents.AUTO),
    CONTENT("content", false, Content.None),
    USER_SELECT("user-select", false, UserSelect.AUTO),
    OBJECT_FIT("object-fit", false, ObjectFit.FILL),
    TRANSFORM("transform", false, emptyList<TransformFn>()),
    TRANSFORM_ORIGIN("transform-origin", false, TransformOrigin.CENTER),

    GRID_TEMPLATE_COLUMNS("grid-template-columns", false, TrackList.NONE),
    GRID_TEMPLATE_ROWS("grid-template-rows", false, TrackList.NONE),
    GRID_TEMPLATE_AREAS("grid-template-areas", false, GridAreas(emptyList())),
    GRID_AUTO_COLUMNS("grid-auto-columns", false, TrackList(listOf(TrackSize.Auto))),
    GRID_AUTO_ROWS("grid-auto-rows", false, TrackList(listOf(TrackSize.Auto))),
    GRID_AUTO_FLOW("grid-auto-flow", false, GridAutoFlow.ROW),
    GRID_ROW_START("grid-row-start", false, GridLine.AUTO),
    GRID_ROW_END("grid-row-end", false, GridLine.AUTO),
    GRID_COLUMN_START("grid-column-start", false, GridLine.AUTO),
    GRID_COLUMN_END("grid-column-end", false, GridLine.AUTO),
    JUSTIFY_ITEMS("justify-items", false, AlignItems.STRETCH),
    JUSTIFY_SELF("justify-self", false, AlignSelf.AUTO),

    TRANSITION_PROPERTY("transition-property", false, listOf("all")),
    TRANSITION_DURATION("transition-duration", false, listOf(0f)),
    TRANSITION_TIMING_FUNCTION("transition-timing-function", false, listOf(TimingFunction.EASE)),
    TRANSITION_DELAY("transition-delay", false, listOf(0f)),
    ANIMATION_NAME("animation-name", false, emptyList<String>()),
    ANIMATION_DURATION("animation-duration", false, listOf(0f)),
    ANIMATION_TIMING_FUNCTION("animation-timing-function", false, listOf(TimingFunction.EASE)),
    ANIMATION_DELAY("animation-delay", false, listOf(0f)),
    ANIMATION_ITERATION_COUNT("animation-iteration-count", false, listOf(1f)),
    ANIMATION_DIRECTION("animation-direction", false, listOf(AnimationDirection.NORMAL)),
    ANIMATION_FILL_MODE("animation-fill-mode", false, listOf(AnimationFillMode.NONE)),
    ANIMATION_PLAY_STATE("animation-play-state", false, listOf(AnimationPlayState.RUNNING));

    companion object {
        val byName: Map<String, Prop> = entries.associateBy { it.css }
    }
}

/** Parses declaration values into longhand values and expands shorthands. */
object Properties {

    private typealias Values = List<ComponentValue>

    private val longhandParsers: Map<Prop, (Values) -> Any?> = buildMap {
        fun enumParser(vararg props: Prop, parse: (Values) -> Any?) = props.forEach { put(it, parse) }

        enumParser(Prop.DISPLAY) { single(it)?.let { v -> keyword<Display>(v) } }
        enumParser(Prop.POSITION) { single(it)?.let { v -> keyword<Position>(v) } }
        enumParser(Prop.TOP, Prop.RIGHT, Prop.BOTTOM, Prop.LEFT) { single(it)?.let(::lengthOrAuto) }
        enumParser(Prop.Z_INDEX) { single(it)?.let { v -> if (isIdent(v, "auto")) Z_INDEX_AUTO else integer(v) } }
        enumParser(Prop.WIDTH, Prop.HEIGHT, Prop.MIN_WIDTH, Prop.MIN_HEIGHT, Prop.FLEX_BASIS) { single(it)?.let(::lengthOrAuto) }
        enumParser(Prop.MAX_WIDTH, Prop.MAX_HEIGHT) { single(it)?.let { v -> if (isIdent(v, "none")) Dim.None else length(v) } }
        enumParser(Prop.BOX_SIZING) { single(it)?.let { v -> keyword<BoxSizing>(v) } }
        enumParser(Prop.MARGIN_TOP, Prop.MARGIN_RIGHT, Prop.MARGIN_BOTTOM, Prop.MARGIN_LEFT) { single(it)?.let(::lengthOrAuto) }
        enumParser(Prop.PADDING_TOP, Prop.PADDING_RIGHT, Prop.PADDING_BOTTOM, Prop.PADDING_LEFT) { single(it)?.let(::nonNegativeLength) }
        enumParser(Prop.BORDER_TOP_WIDTH, Prop.BORDER_RIGHT_WIDTH, Prop.BORDER_BOTTOM_WIDTH, Prop.BORDER_LEFT_WIDTH) { single(it)?.let(::borderWidth) }
        enumParser(Prop.BORDER_TOP_STYLE, Prop.BORDER_RIGHT_STYLE, Prop.BORDER_BOTTOM_STYLE, Prop.BORDER_LEFT_STYLE) { single(it)?.let { v -> keyword<BorderStyle>(v) } }
        enumParser(Prop.BORDER_TOP_COLOR, Prop.BORDER_RIGHT_COLOR, Prop.BORDER_BOTTOM_COLOR, Prop.BORDER_LEFT_COLOR, Prop.BACKGROUND_COLOR, Prop.COLOR) {
            single(it)?.let(::color)
        }
        enumParser(Prop.BORDER_TOP_LEFT_RADIUS, Prop.BORDER_TOP_RIGHT_RADIUS, Prop.BORDER_BOTTOM_RIGHT_RADIUS, Prop.BORDER_BOTTOM_LEFT_RADIUS) {
            single(it)?.let(::nonNegativeLength)
        }
        enumParser(Prop.BACKGROUND_IMAGE) { backgroundLayers(it) }
        enumParser(Prop.OPACITY) { single(it)?.let(::numberOrPercent)?.coerceIn(0f, 1f) }
        enumParser(Prop.VISIBILITY) { single(it)?.let { v -> if (isIdent(v, "collapse")) Visibility.HIDDEN else keyword<Visibility>(v) } }
        enumParser(Prop.OVERFLOW_X, Prop.OVERFLOW_Y) { single(it)?.let { v -> if (isIdent(v, "clip")) Overflow.HIDDEN else keyword<Overflow>(v) } }
        enumParser(Prop.SCROLLBAR_WIDTH) { single(it)?.let { v -> identIn(v, "auto", "thin", "none") } }
        enumParser(Prop.SCROLLBAR_COLOR) { vs ->
            val w = words(vs)
            when {
                w.size == 1 && isIdent(w[0], "auto") -> NoImage
                w.size == 2 -> {
                    val thumb = color(w[0]); val track = color(w[1])
                    if (thumb != null && track != null) ScrollbarColor(thumb, track) else null
                }
                else -> null
            }
        }
        enumParser(Prop.FLEX_DIRECTION) { single(it)?.let { v -> keyword<FlexDirection>(v) } }
        enumParser(Prop.FLEX_WRAP) { single(it)?.let { v -> keyword<FlexWrap>(v) } }
        enumParser(Prop.JUSTIFY_CONTENT) { single(it)?.let(::justify) }
        enumParser(Prop.ALIGN_ITEMS) { single(it)?.let(::alignItems) }
        enumParser(Prop.ALIGN_SELF) { single(it)?.let { v -> alignItems(v)?.let { a -> AlignSelf.valueOf(a.name) } ?: keyword<AlignSelf>(v) } }
        enumParser(Prop.FLEX_GROW, Prop.FLEX_SHRINK) { single(it)?.let(::number)?.takeIf { n -> n >= 0f } }
        enumParser(Prop.ORDER) { single(it)?.let(::integer) }
        enumParser(Prop.ROW_GAP, Prop.COLUMN_GAP) { single(it)?.let { v -> if (isIdent(v, "normal")) Length(0f, "px") else nonNegativeLength(v) } }
        enumParser(Prop.FONT_FAMILY) { fontFamily(it) }
        enumParser(Prop.FONT_SIZE) { single(it)?.let(::fontSize) }
        enumParser(Prop.FONT_WEIGHT) { single(it)?.let(::fontWeight) }
        enumParser(Prop.FONT_STYLE) { single(it)?.let { v -> if (isIdent(v, "oblique")) FontStyle.ITALIC else keyword<FontStyle>(v) } }
        enumParser(Prop.LINE_HEIGHT) { single(it)?.let(::lineHeight) }
        enumParser(Prop.TEXT_ALIGN) { single(it)?.let(::textAlign) }
        enumParser(Prop.WHITE_SPACE) { single(it)?.let { v -> keyword<WhiteSpace>(v) } }
        enumParser(Prop.TEXT_OVERFLOW) { single(it)?.let { v -> keyword<TextOverflow>(v) } }
        enumParser(Prop.TEXT_DECORATION) { textDecoration(it) }
        enumParser(Prop.LETTER_SPACING) { single(it)?.let { v -> if (isIdent(v, "normal")) Length(0f, "px") else length(v)?.takeIf { l -> !l.isPercent } } }
        enumParser(Prop.TEXT_SHADOW) { textShadow(it) }
        enumParser(Prop.BOX_SHADOW) { boxShadow(it) }
        enumParser(Prop.CURSOR) { single(it)?.let { v -> keyword<Cursor>(v) } }
        enumParser(Prop.POINTER_EVENTS) { single(it)?.let { v -> keyword<PointerEvents>(v) } }
        enumParser(Prop.CONTENT) { content(it) }
        enumParser(Prop.USER_SELECT) { single(it)?.let { v -> keyword<UserSelect>(v) } }
        enumParser(Prop.OBJECT_FIT) { single(it)?.let { v -> keyword<ObjectFit>(v) } }
        enumParser(Prop.TRANSFORM) { TransformParser.transform(it) }
        enumParser(Prop.TRANSFORM_ORIGIN) { TransformParser.origin(it) }
        enumParser(Prop.GRID_TEMPLATE_COLUMNS, Prop.GRID_TEMPLATE_ROWS) { GridParser.trackList(it) }
        enumParser(Prop.GRID_AUTO_COLUMNS, Prop.GRID_AUTO_ROWS) { GridParser.trackList(it, allowAutoRepeat = false)?.takeIf { l -> l.tracks.isNotEmpty() } }
        enumParser(Prop.GRID_TEMPLATE_AREAS) { GridParser.areas(it) }
        enumParser(Prop.GRID_AUTO_FLOW) { GridParser.autoFlow(it) }
        enumParser(Prop.GRID_ROW_START, Prop.GRID_ROW_END, Prop.GRID_COLUMN_START, Prop.GRID_COLUMN_END) { GridParser.line(it) }
        enumParser(Prop.JUSTIFY_ITEMS) { single(it)?.let { v -> if (isIdent(v, "left")) AlignItems.FLEX_START else if (isIdent(v, "right")) AlignItems.FLEX_END else alignItems(v) } }
        enumParser(Prop.JUSTIFY_SELF) { single(it)?.let { v -> if (isIdent(v, "auto")) AlignSelf.AUTO else alignItems(v)?.let { a -> AlignSelf.valueOf(a.name) } } }
        enumParser(Prop.TRANSITION_PROPERTY) { AnimationParser.propertyList(it) }
        enumParser(Prop.TRANSITION_DURATION, Prop.TRANSITION_DELAY, Prop.ANIMATION_DURATION, Prop.ANIMATION_DELAY) { AnimationParser.timeList(it) }
        enumParser(Prop.TRANSITION_TIMING_FUNCTION, Prop.ANIMATION_TIMING_FUNCTION) { AnimationParser.timingList(it) }
        enumParser(Prop.ANIMATION_NAME) { AnimationParser.nameList(it) }
        enumParser(Prop.ANIMATION_ITERATION_COUNT) { AnimationParser.iterationList(it) }
        enumParser(Prop.ANIMATION_DIRECTION) { AnimationParser.enumList<AnimationDirection>(it) }
        enumParser(Prop.ANIMATION_FILL_MODE) { AnimationParser.enumList<AnimationFillMode>(it) }
        enumParser(Prop.ANIMATION_PLAY_STATE) { AnimationParser.enumList<AnimationPlayState>(it) }
    }

    private val sides = listOf("top", "right", "bottom", "left")
    private val corners = listOf("top-left", "top-right", "bottom-right", "bottom-left")

    private fun side(prefix: String, suffix: String = "") = sides.map { Prop.byName.getValue("$prefix-$it$suffix") }

    private val shorthands: Map<String, (Values) -> List<Pair<Prop, Any>>?> = buildMap {
        put("margin") { v -> fourSides(v, side("margin")) { lengthOrAuto(it) } }
        put("padding") { v -> fourSides(v, side("padding")) { nonNegativeLength(it) } }
        put("inset") { v -> fourSides(v, listOf(Prop.TOP, Prop.RIGHT, Prop.BOTTOM, Prop.LEFT)) { lengthOrAuto(it) } }
        put("border-width") { v -> fourSides(v, side("border", "-width")) { borderWidth(it) } }
        put("border-style") { v -> fourSides(v, side("border", "-style")) { keyword<BorderStyle>(it) } }
        put("border-color") { v -> fourSides(v, side("border", "-color")) { color(it) } }
        put("border-radius") { v -> fourSides(v, corners.map { Prop.byName.getValue("border-$it-radius") }) { nonNegativeLength(it) } }
        put("border") { v -> border(v, sides) }
        sides.forEach { s -> put("border-$s") { v -> border(v, listOf(s)) } }
        put("overflow") { v ->
            val w = words(v)
            val parser = longhandParsers.getValue(Prop.OVERFLOW_X)
            when (w.size) {
                1 -> parser(w)?.let { listOf(Prop.OVERFLOW_X to it, Prop.OVERFLOW_Y to it) }
                2 -> {
                    val x = parser(listOf(w[0])); val y = parser(listOf(w[1]))
                    if (x != null && y != null) listOf(Prop.OVERFLOW_X to x, Prop.OVERFLOW_Y to y) else null
                }
                else -> null
            }
        }
        put("gap") { v ->
            val w = words(v)
            val parser = longhandParsers.getValue(Prop.ROW_GAP)
            when (w.size) {
                1 -> parser(w)?.let { listOf(Prop.ROW_GAP to it, Prop.COLUMN_GAP to it) }
                2 -> {
                    val r = parser(listOf(w[0])); val c = parser(listOf(w[1]))
                    if (r != null && c != null) listOf(Prop.ROW_GAP to r, Prop.COLUMN_GAP to c) else null
                }
                else -> null
            }
        }
        put("flex") { v -> flex(v) }
        put("flex-flow") { v ->
            var dir: Any? = null; var wrap: Any? = null
            for (w in words(v)) {
                keyword<FlexDirection>(w)?.let { dir = it } ?: keyword<FlexWrap>(w)?.let { wrap = it } ?: return@put null
            }
            listOfNotNull(dir?.let { Prop.FLEX_DIRECTION to it }, wrap?.let { Prop.FLEX_WRAP to it }).ifEmpty { null }
        }
        put("background") { v ->
            // Comma-separated layers; only the last layer may contain the background color (like CSS).
            var col: Any? = null
            val layers = ArrayList<BackgroundLayer>()
            val parts = BackgroundParser.splitCommas(v)
            for ((i, part) in parts.withIndex()) {
                for (w in words(part)) {
                    if (isIdent(w, "none")) continue
                    val layer = BackgroundParser.layer(w, ::url)
                    when {
                        layer != null -> layers += layer
                        i == parts.lastIndex && col == null && color(w) != null -> col = color(w)
                        else -> return@put null
                    }
                }
            }
            listOf(Prop.BACKGROUND_COLOR to (col ?: Colors.TRANSPARENT), Prop.BACKGROUND_IMAGE to (if (layers.isEmpty()) NoImage else layers))
        }
        put("place-items") { v ->
            // place-items: <align-items> [<justify-items>]
            val w = words(v)
            val a = w.getOrNull(0)?.let(::alignItems) ?: return@put null
            val j = (if (w.size == 2) alignItems(w[1]) else a) ?: return@put null
            if (w.size > 2) null else listOf(Prop.ALIGN_ITEMS to a, Prop.JUSTIFY_ITEMS to j)
        }
        put("grid-row") { v -> GridParser.lineShorthand(v, Prop.GRID_ROW_START, Prop.GRID_ROW_END) }
        put("grid-column") { v -> GridParser.lineShorthand(v, Prop.GRID_COLUMN_START, Prop.GRID_COLUMN_END) }
        put("grid-area") { v -> GridParser.areaShorthand(v) }
        put("grid-gap") { v -> shorthands.getValue("gap")(v) }
        put("grid-row-gap") { v -> longhandParsers.getValue(Prop.ROW_GAP)(v)?.let { listOf(Prop.ROW_GAP to it) } }
        put("grid-column-gap") { v -> longhandParsers.getValue(Prop.COLUMN_GAP)(v)?.let { listOf(Prop.COLUMN_GAP to it) } }
        put("place-self") { v ->
            val w = words(v)
            val a = w.getOrNull(0)?.let(::alignItems) ?: return@put null
            val j = (if (w.size == 2) alignItems(w[1]) else a) ?: return@put null
            listOf(Prop.ALIGN_SELF to AlignSelf.valueOf(a.name), Prop.JUSTIFY_SELF to AlignSelf.valueOf(j.name))
        }
        put("transition") { v -> AnimationParser.transitionShorthand(v) }
        put("animation") { v -> AnimationParser.animationShorthand(v) }
    }

    /** Longhands a shorthand expands to, used for CSS-wide keywords like `margin: inherit`. */
    private val shorthandLonghands: Map<String, List<Prop>> = buildMap {
        put("margin", side("margin")); put("padding", side("padding")); put("inset", listOf(Prop.TOP, Prop.RIGHT, Prop.BOTTOM, Prop.LEFT))
        put("border-width", side("border", "-width")); put("border-style", side("border", "-style")); put("border-color", side("border", "-color"))
        put("border-radius", corners.map { Prop.byName.getValue("border-$it-radius") })
        put("border", side("border", "-width") + side("border", "-style") + side("border", "-color"))
        sides.forEach { s -> put("border-$s", listOf("width", "style", "color").map { Prop.byName.getValue("border-$s-$it") }) }
        put("overflow", listOf(Prop.OVERFLOW_X, Prop.OVERFLOW_Y)); put("gap", listOf(Prop.ROW_GAP, Prop.COLUMN_GAP))
        put("flex", listOf(Prop.FLEX_GROW, Prop.FLEX_SHRINK, Prop.FLEX_BASIS)); put("flex-flow", listOf(Prop.FLEX_DIRECTION, Prop.FLEX_WRAP))
        put("background", listOf(Prop.BACKGROUND_COLOR, Prop.BACKGROUND_IMAGE)); put("place-items", listOf(Prop.ALIGN_ITEMS, Prop.JUSTIFY_ITEMS))
        put("grid-row", listOf(Prop.GRID_ROW_START, Prop.GRID_ROW_END)); put("grid-column", listOf(Prop.GRID_COLUMN_START, Prop.GRID_COLUMN_END))
        put("grid-area", listOf(Prop.GRID_ROW_START, Prop.GRID_COLUMN_START, Prop.GRID_ROW_END, Prop.GRID_COLUMN_END))
        put("grid-gap", listOf(Prop.ROW_GAP, Prop.COLUMN_GAP)); put("grid-row-gap", listOf(Prop.ROW_GAP)); put("grid-column-gap", listOf(Prop.COLUMN_GAP))
        put("place-self", listOf(Prop.ALIGN_SELF, Prop.JUSTIFY_SELF))
        put("transition", listOf(Prop.TRANSITION_PROPERTY, Prop.TRANSITION_DURATION, Prop.TRANSITION_TIMING_FUNCTION, Prop.TRANSITION_DELAY))
        put("animation", listOf(Prop.ANIMATION_NAME, Prop.ANIMATION_DURATION, Prop.ANIMATION_TIMING_FUNCTION, Prop.ANIMATION_DELAY,
            Prop.ANIMATION_ITERATION_COUNT, Prop.ANIMATION_DIRECTION, Prop.ANIMATION_FILL_MODE, Prop.ANIMATION_PLAY_STATE))
    }

    val knownNames: Set<String> = Prop.byName.keys + shorthands.keys

    fun isKnown(name: String) = name in knownNames

    /** Closest known property name, for "did you mean" hints. */
    fun suggest(name: String): String? = knownNames.minByOrNull { levenshtein(it, name) }?.takeIf { levenshtein(it, name) <= 2 }

    /**
     * Parses [value] for property [name] into longhand values. Returns `null` if the value is invalid.
     * CSS-wide keywords yield [CssWide] values.
     */
    /** The longhand properties behind a property or shorthand name (e.g. `padding` → 4 sides). */
    fun longhandsOf(name: String): List<Prop> = Prop.byName[name]?.let { listOf(it) } ?: shorthandLonghands[name] ?: emptyList()

    fun parse(name: String, value: Values): List<Pair<Prop, Any>>? {
        val words = words(value)
        if (words.size == 1) {
            val wide = cssWide(words[0])
            if (wide != null) return (Prop.byName[name]?.let { listOf(it) } ?: shorthandLonghands[name] ?: return null).map { it to wide }
        }
        Prop.byName[name]?.let { prop -> return longhandParsers.getValue(prop)(value)?.let { listOf(prop to it) } }
        return shorthands[name]?.invoke(value)
    }

    // ---- value helpers -------------------------------------------------------------------------------------------

    /** Placeholder for `none` in image-like properties (null can't be stored in the parsed list). */
    data object NoImage

    data class ScrollbarColor(val thumb: Any, val track: Any)

    /** Splits a value into whitespace separated words (commas are kept as their own words). */
    fun words(values: Values): List<ComponentValue> = values.filter { !(it is TokenValue && it.token.type == TokenType.WHITESPACE) }

    private fun single(values: Values): ComponentValue? = words(values).singleOrNull()

    private fun tok(v: ComponentValue): Token? = (v as? TokenValue)?.token

    fun isIdent(v: ComponentValue, name: String) = tok(v)?.isIdent(name) == true

    private fun cssWide(v: ComponentValue): CssWide? = tok(v)?.takeIf { it.type == TokenType.IDENT }?.let {
        when (it.text.lowercase()) { "inherit" -> CssWide.INHERIT; "initial" -> CssWide.INITIAL; "unset" -> CssWide.UNSET; else -> null }
    }

    private fun identIn(v: ComponentValue, vararg names: String): String? =
        tok(v)?.takeIf { it.type == TokenType.IDENT }?.text?.lowercase()?.takeIf { it in names }

    /** Matches an ident against enum constants, with `-` ⇄ `_` (e.g. `space-between` → SPACE_BETWEEN). */
    inline fun <reified E : Enum<E>> keyword(v: ComponentValue): E? {
        val t = (v as? TokenValue)?.token?.takeIf { it.type == TokenType.IDENT } ?: return null
        val name = t.text.uppercase().replace('-', '_')
        return enumValues<E>().firstOrNull { it.name == name }
    }

    private val LENGTH_UNITS = setOf("px", "em", "rem", "vw", "vh", "vmin", "vmax")

    fun length(v: ComponentValue): Length? {
        if (v is FunctionValue && v.name in CalcNode.FUNCTIONS) return CalcNode.parse(v)?.let { Length(0f, "calc", it) }
        val t = tok(v) ?: return null
        return when (t.type) {
            TokenType.DIMENSION -> if (t.unit in LENGTH_UNITS) Length(t.number.toFloat(), t.unit) else null
            TokenType.PERCENTAGE -> Length(t.number.toFloat(), "%")
            TokenType.NUMBER -> if (t.number == 0.0) Length(0f, "px") else null
            else -> null
        }
    }

    private fun lengthOrAuto(v: ComponentValue): Any? = if (isIdent(v, "auto")) Dim.Auto else length(v)

    private fun nonNegativeLength(v: ComponentValue): Length? = length(v)?.takeIf { it.isCalc || it.value >= 0f }

    private fun borderWidth(v: ComponentValue): Any? = when {
        isIdent(v, "thin") -> 1f
        isIdent(v, "medium") -> 1f
        isIdent(v, "thick") -> 2f
        else -> nonNegativeLength(v)?.takeIf { !it.isPercent }
    }

    fun number(v: ComponentValue): Float? = tok(v)?.takeIf { it.type == TokenType.NUMBER }?.number?.toFloat()

    private fun integer(v: ComponentValue): Int? = tok(v)?.takeIf { it.type == TokenType.NUMBER && it.number == Math.floor(it.number) }?.number?.toInt()

    private fun numberOrPercent(v: ComponentValue): Float? {
        val t = tok(v) ?: return null
        return when (t.type) {
            TokenType.NUMBER -> t.number.toFloat()
            TokenType.PERCENTAGE -> t.number.toFloat() / 100f
            else -> null
        }
    }

    fun color(v: ComponentValue): Any? {
        if (v is FunctionValue) return colorFunction(v)
        val t = tok(v) ?: return null
        return when (t.type) {
            TokenType.HASH -> Colors.parseHex(t.text)
            TokenType.IDENT -> if (t.text.equals("currentcolor", ignoreCase = true)) CurrentColor else Colors.NAMED[t.text.lowercase()]
            else -> null
        }
    }

    private fun colorFunction(f: FunctionValue): Int? {
        // Accept both `rgb(1, 2, 3, 0.5)` and `rgb(1 2 3 / 50%)`.
        val parts = words(f.args).filter { !(tok(it)?.type == TokenType.COMMA) && !(tok(it)?.isDelim('/') == true) }
        fun channel(v: ComponentValue): Float? {
            val t = tok(v) ?: return null
            return when (t.type) {
                TokenType.NUMBER -> t.number.toFloat()
                TokenType.PERCENTAGE -> t.number.toFloat() * 2.55f
                else -> null
            }
        }
        fun alpha(v: ComponentValue?): Float? = if (v == null) 1f else numberOrPercent(v)?.coerceIn(0f, 1f)
        return when (f.name) {
            "rgb", "rgba" -> {
                if (parts.size !in 3..4) return null
                val r = channel(parts[0]) ?: return null
                val g = channel(parts[1]) ?: return null
                val b = channel(parts[2]) ?: return null
                val a = alpha(parts.getOrNull(3)) ?: return null
                Colors.argb(Math.round(a * 255), Math.round(r), Math.round(g), Math.round(b))
            }
            "hsl", "hsla" -> {
                if (parts.size !in 3..4) return null
                val h = tok(parts[0])?.let { if (it.type == TokenType.NUMBER || (it.type == TokenType.DIMENSION && it.unit == "deg")) it.number.toFloat() else null } ?: return null
                val s = tok(parts[1])?.takeIf { it.type == TokenType.PERCENTAGE || it.type == TokenType.NUMBER }?.number?.toFloat() ?: return null
                val l = tok(parts[2])?.takeIf { it.type == TokenType.PERCENTAGE || it.type == TokenType.NUMBER }?.number?.toFloat() ?: return null
                val a = alpha(parts.getOrNull(3)) ?: return null
                Colors.hslToArgb(h, s / 100f, l / 100f, a)
            }
            else -> null
        }
    }

    /** `none` or a comma-separated list of `url()` / gradient layers. */
    private fun backgroundLayers(values: Values): Any? {
        val w = words(values)
        if (w.size == 1 && isIdent(w[0], "none")) return NoImage
        val layers = BackgroundParser.splitCommas(values).map { part ->
            val single = words(part).singleOrNull() ?: return null
            BackgroundParser.layer(single, ::url) ?: return null
        }
        return layers
    }

    private fun url(v: ComponentValue): String? = when {
        tok(v)?.type == TokenType.URL -> tok(v)!!.text
        v is FunctionValue && v.name == "url" -> words(v.args).singleOrNull()?.let { tok(it) }?.takeIf { it.type == TokenType.STRING }?.text
        else -> null
    }

    private fun justify(v: ComponentValue): JustifyContent? = when {
        isIdent(v, "start") || isIdent(v, "left") || isIdent(v, "normal") -> JustifyContent.FLEX_START
        isIdent(v, "end") || isIdent(v, "right") -> JustifyContent.FLEX_END
        else -> keyword<JustifyContent>(v)
    }

    private fun alignItems(v: ComponentValue): AlignItems? = when {
        isIdent(v, "start") || isIdent(v, "self-start") -> AlignItems.FLEX_START
        isIdent(v, "end") || isIdent(v, "self-end") -> AlignItems.FLEX_END
        isIdent(v, "normal") -> AlignItems.STRETCH
        else -> keyword<AlignItems>(v)
    }

    private fun textAlign(v: ComponentValue): TextAlign? = when {
        isIdent(v, "start") || isIdent(v, "justify") -> TextAlign.LEFT
        isIdent(v, "end") -> TextAlign.RIGHT
        else -> keyword<TextAlign>(v)
    }

    private fun content(values: Values): Content? {
        val w = words(values)
        if (w.isEmpty()) return null
        if (w.size == 1 && (isIdent(w[0], "none") || isIdent(w[0], "normal"))) return Content.None
        val parts = w.map { v ->
            val t = tok(v)
            when {
                t?.type == TokenType.STRING -> ContentPart.Text(t.text)
                v is FunctionValue && v.name.equals("attr", ignoreCase = true) -> {
                    val name = words(v.args).singleOrNull()?.let(::tok)?.takeIf { it.type == TokenType.IDENT } ?: return null
                    ContentPart.Attr(name.text.lowercase())
                }
                else -> return null
            }
        }
        return Content.Items(parts)
    }

    private fun fontFamily(values: Values): List<String>? {
        val families = ArrayList<String>()
        val current = StringBuilder()
        for (w in words(values)) {
            val t = tok(w) ?: return null
            when (t.type) {
                TokenType.COMMA -> {
                    if (current.isEmpty()) return null
                    families += current.toString(); current.clear()
                }
                TokenType.STRING -> {
                    if (current.isNotEmpty()) return null
                    current.append(t.text.lowercase())
                }
                TokenType.IDENT -> {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(t.text.lowercase())
                }
                else -> return null
            }
        }
        if (current.isEmpty()) return null
        families += current.toString()
        return families
    }

    private fun fontSize(v: ComponentValue): Any? = when {
        isIdent(v, "small") -> Length(7f, "px")
        isIdent(v, "medium") -> Length(8f, "px")
        isIdent(v, "large") -> Length(10f, "px")
        isIdent(v, "x-large") -> Length(12f, "px")
        isIdent(v, "xx-large") -> Length(16f, "px")
        isIdent(v, "smaller") -> Length(0.85f, "em")
        isIdent(v, "larger") -> Length(1.2f, "em")
        else -> nonNegativeLength(v)
    }

    private fun fontWeight(v: ComponentValue): Int? = when {
        isIdent(v, "normal") -> 400
        isIdent(v, "bold") -> 700
        isIdent(v, "lighter") -> 300
        isIdent(v, "bolder") -> 700
        else -> tok(v)?.takeIf { it.type == TokenType.NUMBER }?.number?.toInt()?.takeIf { it in 1..1000 }
    }

    private fun lineHeight(v: ComponentValue): Any? {
        if (isIdent(v, "normal")) return LineHeight.Normal
        number(v)?.let { return if (it >= 0f) LineHeight.Multiplier(it) else null }
        val len = nonNegativeLength(v) ?: return null
        return if (len.isPercent) LineHeight.Multiplier(len.value / 100f) else LineHeightLength(len)
    }

    /** Line height given as a length; converted to [LineHeight.Px] once the font size is known. */
    data class LineHeightLength(val length: Length)

    private fun textDecoration(values: Values): TextDecoration? {
        var underline = false; var through = false
        for (w in words(values)) {
            when {
                isIdent(w, "none") -> {}
                isIdent(w, "underline") -> underline = true
                isIdent(w, "line-through") -> through = true
                color(w) != null || keyword<BorderStyle>(w) != null -> {} // decoration color/style are accepted but ignored
                else -> return null
            }
        }
        return TextDecoration(underline, through)
    }

    private fun textShadow(values: Values): Any? {
        val w = words(values)
        if (w.size == 1 && isIdent(w[0], "none")) return NoImage
        var col: Any = CurrentColor
        val lengths = ArrayList<Length>()
        for (v in w) {
            length(v)?.let { lengths += it } ?: color(v)?.let { col = it } ?: return null
        }
        if (lengths.size !in 2..3) return null
        return TextShadowValue(lengths[0], lengths[1], col)
    }

    data class TextShadowValue(val x: Length, val y: Length, val color: Any)

    /** `box-shadow`: `none` or a comma-separated list of `[inset] <x> <y> [<blur> [<spread>]] [<color>]`. */
    private fun boxShadow(values: Values): Any? {
        val w = words(values)
        if (w.size == 1 && isIdent(w[0], "none")) return emptyList<BoxShadow>()
        val out = ArrayList<BoxShadowValue>()
        for (part in BackgroundParser.splitCommas(values)) {
            var col: Any = CurrentColor
            var colorSeen = false
            var inset = false
            val lengths = ArrayList<Length>()
            for (v in words(part)) {
                when {
                    isIdent(v, "inset") && !inset -> inset = true
                    !colorSeen && color(v) != null -> { col = color(v)!!; colorSeen = true }
                    else -> lengths += length(v)?.takeIf { !it.isPercent } ?: return null
                }
            }
            if (lengths.size !in 2..4) return null
            if (lengths.size >= 3 && lengths[2].value < 0f) return null // blur can't be negative
            out += BoxShadowValue(lengths[0], lengths[1], lengths.getOrNull(2), lengths.getOrNull(3), col, inset)
        }
        return BoxShadowList(out)
    }

    data class BoxShadowValue(val x: Length, val y: Length, val blur: Length?, val spread: Length?, val color: Any, val inset: Boolean)
    data class BoxShadowList(val shadows: List<BoxShadowValue>)

    private fun fourSides(values: Values, props: List<Prop>, parse: (ComponentValue) -> Any?): List<Pair<Prop, Any>>? {
        val parsed = words(values).map { parse(it) ?: return null }
        val (t, r, b, l) = when (parsed.size) {
            1 -> listOf(parsed[0], parsed[0], parsed[0], parsed[0])
            2 -> listOf(parsed[0], parsed[1], parsed[0], parsed[1])
            3 -> listOf(parsed[0], parsed[1], parsed[2], parsed[1])
            4 -> parsed
            else -> return null
        }
        return listOf(props[0] to t, props[1] to r, props[2] to b, props[3] to l)
    }

    private fun border(values: Values, forSides: List<String>): List<Pair<Prop, Any>>? {
        var width: Any? = null; var style: Any? = null; var col: Any? = null
        for (w in words(values)) {
            when {
                style == null && keyword<BorderStyle>(w) != null -> style = keyword<BorderStyle>(w)
                width == null && borderWidth(w) != null -> width = borderWidth(w)
                col == null && color(w) != null -> col = color(w)
                else -> return null
            }
        }
        // Like the web, omitted parts reset to their initial values.
        return forSides.flatMap { s ->
            listOf(
                Prop.byName.getValue("border-$s-width") to (width ?: 1f),
                Prop.byName.getValue("border-$s-style") to (style ?: BorderStyle.NONE),
                Prop.byName.getValue("border-$s-color") to (col ?: CurrentColor),
            )
        }
    }

    private fun flex(values: Values): List<Pair<Prop, Any>>? {
        val w = words(values)
        fun result(g: Float, s: Float, basis: Any) = listOf(Prop.FLEX_GROW to g, Prop.FLEX_SHRINK to s, Prop.FLEX_BASIS to basis)
        if (w.size == 1) {
            when {
                isIdent(w[0], "none") -> return result(0f, 0f, Dim.Auto)
                isIdent(w[0], "auto") -> return result(1f, 1f, Dim.Auto)
            }
        }
        val numbers = ArrayList<Float>()
        var basis: Any? = null
        for (v in w) {
            val n = number(v)
            when {
                n != null && basis == null && numbers.size < 2 -> numbers += n
                n != null && numbers.size < 2 -> numbers += n
                basis == null -> basis = lengthOrAuto(v) ?: return null
                else -> return null
            }
        }
        if (numbers.isEmpty() && basis == null) return null
        return result(numbers.getOrElse(0) { 1f }, numbers.getOrElse(1) { 1f }, basis ?: Length(0f, "%"))
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
