package net.sbo.guilib.core.css

/** Context needed to resolve viewport- and root-relative units. */
data class StyleContext(
    val viewportWidth: Float,
    val viewportHeight: Float,
    /** Font size of the root element, used for `rem`. */
    val rootFontSize: Float = Prop.FONT_SIZE.initial as Float,
    /** Screen pixels per GUI pixel (Minecraft's GUI scale); `@media (resolution)` tests it. */
    val resolution: Float = 1f,
)

/**
 * The final value of every supported property for one element, after cascade, inheritance and unit resolution.
 * Percentages stay as [Dim.Pct] until layout. Instances are immutable.
 */
class ComputedStyle internal constructor(
    private val values: Array<Any?>,
    /** Custom properties (`--name`) visible on this element, already inherited from ancestors. */
    val customProperties: Map<String, List<ComponentValue>>,
) {
    operator fun get(p: Prop): Any? = values[p.ordinal]

    val display get() = values[Prop.DISPLAY.ordinal] as Display
    val position get() = values[Prop.POSITION.ordinal] as Position
    val top get() = dim(Prop.TOP)
    val right get() = dim(Prop.RIGHT)
    val bottom get() = dim(Prop.BOTTOM)
    val left get() = dim(Prop.LEFT)
    /** `null` means `z-index: auto`. */
    val zIndex: Int? get() = (values[Prop.Z_INDEX.ordinal] as Int).takeIf { it != Z_INDEX_AUTO }

    val width get() = dim(Prop.WIDTH)
    val height get() = dim(Prop.HEIGHT)
    val minWidth get() = dim(Prop.MIN_WIDTH)
    val minHeight get() = dim(Prop.MIN_HEIGHT)
    val maxWidth get() = dim(Prop.MAX_WIDTH)
    val maxHeight get() = dim(Prop.MAX_HEIGHT)
    val boxSizing get() = values[Prop.BOX_SIZING.ordinal] as BoxSizing

    val marginTop get() = dim(Prop.MARGIN_TOP)
    val marginRight get() = dim(Prop.MARGIN_RIGHT)
    val marginBottom get() = dim(Prop.MARGIN_BOTTOM)
    val marginLeft get() = dim(Prop.MARGIN_LEFT)
    val paddingTop get() = dim(Prop.PADDING_TOP)
    val paddingRight get() = dim(Prop.PADDING_RIGHT)
    val paddingBottom get() = dim(Prop.PADDING_BOTTOM)
    val paddingLeft get() = dim(Prop.PADDING_LEFT)

    /** Used border widths: 0 when the border style is `none`/`hidden`, like the web. */
    val borderTopWidth get() = borderWidth(Prop.BORDER_TOP_WIDTH, Prop.BORDER_TOP_STYLE)
    val borderRightWidth get() = borderWidth(Prop.BORDER_RIGHT_WIDTH, Prop.BORDER_RIGHT_STYLE)
    val borderBottomWidth get() = borderWidth(Prop.BORDER_BOTTOM_WIDTH, Prop.BORDER_BOTTOM_STYLE)
    val borderLeftWidth get() = borderWidth(Prop.BORDER_LEFT_WIDTH, Prop.BORDER_LEFT_STYLE)
    val borderTopColor get() = values[Prop.BORDER_TOP_COLOR.ordinal] as Int
    val borderRightColor get() = values[Prop.BORDER_RIGHT_COLOR.ordinal] as Int
    val borderBottomColor get() = values[Prop.BORDER_BOTTOM_COLOR.ordinal] as Int
    val borderLeftColor get() = values[Prop.BORDER_LEFT_COLOR.ordinal] as Int
    val borderTopStyle get() = values[Prop.BORDER_TOP_STYLE.ordinal] as BorderStyle
    val borderRightStyle get() = values[Prop.BORDER_RIGHT_STYLE.ordinal] as BorderStyle
    val borderBottomStyle get() = values[Prop.BORDER_BOTTOM_STYLE.ordinal] as BorderStyle
    val borderLeftStyle get() = values[Prop.BORDER_LEFT_STYLE.ordinal] as BorderStyle
    val borderTopLeftRadius get() = dim(Prop.BORDER_TOP_LEFT_RADIUS)
    val borderTopRightRadius get() = dim(Prop.BORDER_TOP_RIGHT_RADIUS)
    val borderBottomRightRadius get() = dim(Prop.BORDER_BOTTOM_RIGHT_RADIUS)
    val borderBottomLeftRadius get() = dim(Prop.BORDER_BOTTOM_LEFT_RADIUS)

    val backgroundColor get() = values[Prop.BACKGROUND_COLOR.ordinal] as Int
    /** `background-image` layers, first = top-most. Gradient colors are resolved ARGB ints. */
    @Suppress("UNCHECKED_CAST")
    val backgroundLayers: List<BackgroundLayer> get() = values[Prop.BACKGROUND_IMAGE.ordinal] as List<BackgroundLayer>? ?: emptyList()
    val color get() = values[Prop.COLOR.ordinal] as Int
    val opacity get() = values[Prop.OPACITY.ordinal] as Float
    val visibility get() = values[Prop.VISIBILITY.ordinal] as Visibility
    val overflowX get() = values[Prop.OVERFLOW_X.ordinal] as Overflow
    val overflowY get() = values[Prop.OVERFLOW_Y.ordinal] as Overflow
    /** `auto`, `thin` or `none`. */
    val scrollbarWidth get() = values[Prop.SCROLLBAR_WIDTH.ordinal] as String
    /** `(thumb, track)` colors, or `null` for the default. */
    @Suppress("UNCHECKED_CAST")
    val scrollbarColor get() = values[Prop.SCROLLBAR_COLOR.ordinal] as Pair<Int, Int>?

    val flexDirection get() = values[Prop.FLEX_DIRECTION.ordinal] as FlexDirection
    val flexWrap get() = values[Prop.FLEX_WRAP.ordinal] as FlexWrap
    val justifyContent get() = values[Prop.JUSTIFY_CONTENT.ordinal] as JustifyContent
    val alignItems get() = values[Prop.ALIGN_ITEMS.ordinal] as AlignItems
    val alignContent get() = values[Prop.ALIGN_CONTENT.ordinal] as AlignContent
    val alignSelf get() = values[Prop.ALIGN_SELF.ordinal] as AlignSelf
    val flexGrow get() = values[Prop.FLEX_GROW.ordinal] as Float
    val flexShrink get() = values[Prop.FLEX_SHRINK.ordinal] as Float
    val flexBasis get() = dim(Prop.FLEX_BASIS)
    val order get() = values[Prop.ORDER.ordinal] as Int
    val rowGap get() = dim(Prop.ROW_GAP)
    val columnGap get() = dim(Prop.COLUMN_GAP)

    @Suppress("UNCHECKED_CAST")
    val fontFamily get() = values[Prop.FONT_FAMILY.ordinal] as List<String>
    val fontSize get() = values[Prop.FONT_SIZE.ordinal] as Float
    val fontWeight get() = values[Prop.FONT_WEIGHT.ordinal] as Int
    val fontStyle get() = values[Prop.FONT_STYLE.ordinal] as FontStyle
    val lineHeight get() = values[Prop.LINE_HEIGHT.ordinal] as LineHeight
    val textAlign get() = values[Prop.TEXT_ALIGN.ordinal] as TextAlign
    val whiteSpace get() = values[Prop.WHITE_SPACE.ordinal] as WhiteSpace
    val textOverflow get() = values[Prop.TEXT_OVERFLOW.ordinal] as TextOverflow
    val textDecoration get() = values[Prop.TEXT_DECORATION.ordinal] as TextDecoration
    val textShadow get() = values[Prop.TEXT_SHADOW.ordinal] as TextShadow?
    /** A [VerticalAlign] keyword or a [Dim] (length / percentage of the line height). */
    val verticalAlign: Any get() = values[Prop.VERTICAL_ALIGN.ordinal]!!
    val letterSpacing get() = (values[Prop.LETTER_SPACING.ordinal] as? Dim.Px)?.px ?: 0f
    @Suppress("UNCHECKED_CAST")
    val boxShadow get() = values[Prop.BOX_SHADOW.ordinal] as List<BoxShadow>

    val cursor get() = values[Prop.CURSOR.ordinal] as Cursor
    val pointerEvents get() = values[Prop.POINTER_EVENTS.ordinal] as PointerEvents
    val content get() = values[Prop.CONTENT.ordinal] as Content
    val userSelect get() = values[Prop.USER_SELECT.ordinal] as UserSelect
    val objectFit get() = values[Prop.OBJECT_FIT.ordinal] as ObjectFit
    /** Empty for `transform: none`. */
    @Suppress("UNCHECKED_CAST")
    val transform get() = values[Prop.TRANSFORM.ordinal] as List<TransformFn>
    val transformOrigin get() = values[Prop.TRANSFORM_ORIGIN.ordinal] as TransformOrigin

    /** `transition` entries, with comma lists paired up like CSS (shorter lists repeat). */
    @Suppress("UNCHECKED_CAST")
    val transitions: List<TransitionSpec>
        get() {
            val props = values[Prop.TRANSITION_PROPERTY.ordinal] as List<String>
            val durations = values[Prop.TRANSITION_DURATION.ordinal] as List<Float>
            val timings = values[Prop.TRANSITION_TIMING_FUNCTION.ordinal] as List<TimingFunction>
            val delays = values[Prop.TRANSITION_DELAY.ordinal] as List<Float>
            return props.mapIndexed { i, p ->
                TransitionSpec(p, AnimationParser.at(durations, i, 0f), AnimationParser.at(timings, i, TimingFunction.EASE), AnimationParser.at(delays, i, 0f))
            }
        }

    /** `animation` entries, one per `animation-name`. */
    @Suppress("UNCHECKED_CAST")
    val animations: List<AnimationSpec>
        get() {
            val names = values[Prop.ANIMATION_NAME.ordinal] as List<String>
            if (names.isEmpty()) return emptyList()
            fun <T> list(p: Prop) = values[p.ordinal] as List<T>
            return names.mapIndexed { i, n ->
                AnimationSpec(
                    n,
                    AnimationParser.at(list(Prop.ANIMATION_DURATION), i, 0f),
                    AnimationParser.at(list(Prop.ANIMATION_TIMING_FUNCTION), i, TimingFunction.EASE),
                    AnimationParser.at(list(Prop.ANIMATION_DELAY), i, 0f),
                    AnimationParser.at(list(Prop.ANIMATION_ITERATION_COUNT), i, 1f),
                    AnimationParser.at(list(Prop.ANIMATION_DIRECTION), i, AnimationDirection.NORMAL),
                    AnimationParser.at(list(Prop.ANIMATION_FILL_MODE), i, AnimationFillMode.NONE),
                    AnimationParser.at(list(Prop.ANIMATION_PLAY_STATE), i, AnimationPlayState.RUNNING),
                )
            }
        }

    /** A copy with some values replaced (used for running transitions/animations). */
    fun withOverrides(overrides: Map<Prop, Any?>): ComputedStyle {
        val copy = values.copyOf()
        for ((p, v) in overrides) copy[p.ordinal] = v
        return ComputedStyle(copy, customProperties)
    }

    val gridTemplateColumns get() = values[Prop.GRID_TEMPLATE_COLUMNS.ordinal] as TrackList
    val gridTemplateRows get() = values[Prop.GRID_TEMPLATE_ROWS.ordinal] as TrackList
    val gridTemplateAreas get() = values[Prop.GRID_TEMPLATE_AREAS.ordinal] as GridAreas
    val gridAutoColumns get() = values[Prop.GRID_AUTO_COLUMNS.ordinal] as TrackList
    val gridAutoRows get() = values[Prop.GRID_AUTO_ROWS.ordinal] as TrackList
    val gridAutoFlow get() = values[Prop.GRID_AUTO_FLOW.ordinal] as GridAutoFlow
    val gridRowStart get() = values[Prop.GRID_ROW_START.ordinal] as GridLine
    val gridRowEnd get() = values[Prop.GRID_ROW_END.ordinal] as GridLine
    val gridColumnStart get() = values[Prop.GRID_COLUMN_START.ordinal] as GridLine
    val gridColumnEnd get() = values[Prop.GRID_COLUMN_END.ordinal] as GridLine
    val justifyItems get() = values[Prop.JUSTIFY_ITEMS.ordinal] as AlignItems
    val justifySelf get() = values[Prop.JUSTIFY_SELF.ordinal] as AlignSelf

    val isBold get() = fontWeight >= 600
    val isItalic get() = fontStyle == FontStyle.ITALIC

    private fun dim(p: Prop) = values[p.ordinal] as Dim

    private fun borderWidth(width: Prop, style: Prop): Float {
        val s = values[style.ordinal] as BorderStyle
        return if (s == BorderStyle.NONE || s == BorderStyle.HIDDEN) 0f else values[width.ordinal] as Float
    }

    /** True if every property value equals [other]'s (used to skip relayout/repaint). */
    fun sameAs(other: ComputedStyle?) = other != null && values.contentEquals(other.values) && customProperties == other.customProperties

    /** Returns true if properties that affect layout differ between the two styles. */
    fun layoutDiffers(other: ComputedStyle?): Boolean {
        if (other == null) return true
        for (p in LAYOUT_PROPS) if (values[p.ordinal] != other.values[p.ordinal]) return true
        return false
    }

    override fun toString() = Prop.entries.joinToString("; ", "{", "}") { "${it.css}: ${values[it.ordinal]}" }

    companion object {
        /** Properties whose change requires a new layout (all others only need a repaint). */
        private val PAINT_ONLY = setOf(
            Prop.BACKGROUND_COLOR, Prop.BACKGROUND_IMAGE, Prop.COLOR, Prop.OPACITY, Prop.VISIBILITY, Prop.CURSOR,
            Prop.POINTER_EVENTS, Prop.USER_SELECT, Prop.OBJECT_FIT, Prop.TEXT_DECORATION, Prop.TEXT_SHADOW, Prop.BOX_SHADOW, Prop.Z_INDEX,
            Prop.BORDER_TOP_COLOR, Prop.BORDER_RIGHT_COLOR, Prop.BORDER_BOTTOM_COLOR, Prop.BORDER_LEFT_COLOR,
            Prop.BORDER_TOP_LEFT_RADIUS, Prop.BORDER_TOP_RIGHT_RADIUS, Prop.BORDER_BOTTOM_RIGHT_RADIUS, Prop.BORDER_BOTTOM_LEFT_RADIUS,
            Prop.SCROLLBAR_COLOR, Prop.TRANSFORM, Prop.TRANSFORM_ORIGIN,
            Prop.TRANSITION_PROPERTY, Prop.TRANSITION_DURATION, Prop.TRANSITION_TIMING_FUNCTION, Prop.TRANSITION_DELAY,
            Prop.ANIMATION_NAME, Prop.ANIMATION_DURATION, Prop.ANIMATION_TIMING_FUNCTION, Prop.ANIMATION_DELAY,
            Prop.ANIMATION_ITERATION_COUNT, Prop.ANIMATION_DIRECTION, Prop.ANIMATION_FILL_MODE, Prop.ANIMATION_PLAY_STATE,
        )

        /** Properties whose change only needs a repaint (no relayout). */
        fun isPaintOnly(p: Prop) = p in PAINT_ONLY
        private val LAYOUT_PROPS = Prop.entries.filter { it !in PAINT_ONLY }

        /** Style with every property at its initial value (the implicit parent of the root). */
        val INITIAL: ComputedStyle by lazy {
            StyleEngine.computeFrom(emptyMap(), emptyMap(), null, StyleContext(0f, 0f), "<initial>")
        }

        internal fun create(values: Array<Any?>, custom: Map<String, List<ComponentValue>>) = ComputedStyle(values, custom)
    }
}
