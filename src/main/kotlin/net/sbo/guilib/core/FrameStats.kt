package net.sbo.guilib.core

/**
 * Cheap counters for profiling GuiLib screens: how often styles, layout and the display list are recomputed and how
 * long a frame takes. Summed over all screens since the last [reset]; read them to show a debug overlay or log them.
 */
object FrameStats {
    /** Frames drawn. */
    @JvmStatic var frames = 0L
        internal set
    /** Nanoseconds spent updating the document (styles, layout, display list). */
    @JvmStatic var updateNanos = 0L
        internal set
    /** Nanoseconds spent turning the display list into Minecraft draw calls. */
    @JvmStatic var drawNanos = 0L
        internal set
    /** Style recalculations (any number of elements each). */
    @JvmStatic var styles = 0L
        internal set
    /** Layout passes. */
    @JvmStatic var layouts = 0L
        internal set
    /** Display list rebuilds. */
    @JvmStatic var paints = 0L
        internal set

    /** Nodes laid out (incremental layout skips unchanged subtrees). */
    @JvmStatic var nodeLayouts = 0L
        internal set
    /** Nodes whose cached layout was reused (with their whole subtree). */
    @JvmStatic var layoutHits = 0L
        internal set

    @JvmStatic
    fun reset() {
        frames = 0; updateNanos = 0; drawNanos = 0; styles = 0; layouts = 0; paints = 0; nodeLayouts = 0; layoutHits = 0
        styleNanos = 0; layoutNanos = 0; paintNanos = 0
    }

    internal fun layoutNode() { nodeLayouts++ }
    internal fun layoutHit() { layoutHits++ }

    /** Nanoseconds in style passes (incl. animation ticks), layout passes and display list builds. */
    @JvmStatic var styleNanos = 0L
        internal set
    @JvmStatic var layoutNanos = 0L
        internal set
    @JvmStatic var paintNanos = 0L
        internal set

    /** Paint commands in the last display list. */
    @JvmStatic var commands = 0
        internal set

    private var worstFrameNanos = 0L

    /**
     * Runs [block] without counting it: everything it does (another document's update and drawing, e.g. the metrics
     * overlay) is taken out of the statistics again. The worst frame is not restored: [block] may read it.
     */
    inline fun <T> uncounted(block: () -> T): T {
        val saved = save()
        try {
            return block()
        } finally {
            restore(saved)
        }
    }

    @PublishedApi internal fun save() = longArrayOf(
        frames, updateNanos, drawNanos, styles, layouts, paints, nodeLayouts, layoutHits, styleNanos, layoutNanos, paintNanos,
        commands.toLong(),
    )

    @PublishedApi internal fun restore(v: LongArray) {
        frames = v[0]; updateNanos = v[1]; drawNanos = v[2]; styles = v[3]; layouts = v[4]; paints = v[5]
        nodeLayouts = v[6]; layoutHits = v[7]; styleNanos = v[8]; layoutNanos = v[9]; paintNanos = v[10]
        commands = v[11].toInt()
    }

    /** The slowest frame (update + draw) since the last call, then starts over. */
    @JvmStatic
    fun takeWorstFrameNanos(): Long = worstFrameNanos.also { worstFrameNanos = 0L }

    internal fun frame(updateNanos: Long, drawNanos: Long) {
        frames++
        this.updateNanos += updateNanos
        this.drawNanos += drawNanos
        worstFrameNanos = maxOf(worstFrameNanos, updateNanos + drawNanos)
    }

    internal fun style() { styles++ }
    internal fun layout() { layouts++ }
    internal fun paint() { paints++ }
}
