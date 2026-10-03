package net.sbo.guilib.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [FrameStats.uncounted] leaves out another document's work (the metrics overlay). */
class FrameStatsTest {
    @Test
    fun uncountedWorkIsLeftOutButTakingTheWorstFrameSticks() {
        FrameStats.reset()
        FrameStats.takeWorstFrameNanos()
        FrameStats.frame(1_000, 2_000)
        FrameStats.layout()
        FrameStats.commands = 10
        FrameStats.uncounted {
            FrameStats.layout(); FrameStats.paint(); FrameStats.layoutNode()
            FrameStats.commands = 500
            assertEquals(3_000L, FrameStats.takeWorstFrameNanos()) // the overlay reads (and resets) the worst frame
        }
        assertEquals(1L, FrameStats.frames)
        assertEquals(1L, FrameStats.layouts)
        assertEquals(0L, FrameStats.paints)
        assertEquals(0L, FrameStats.nodeLayouts)
        assertEquals(10, FrameStats.commands)
        assertEquals(0L, FrameStats.takeWorstFrameNanos())
    }
}
