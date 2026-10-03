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
    }

    internal fun layoutNode() { nodeLayouts++ }
    internal fun layoutHit() { layoutHits++ }

    internal fun frame(updateNanos: Long, drawNanos: Long) {
        frames++
        this.updateNanos += updateNanos
        this.drawNanos += drawNanos
    }

    internal fun style() { styles++ }
    internal fun layout() { layouts++ }
    internal fun paint() { paints++ }
}
