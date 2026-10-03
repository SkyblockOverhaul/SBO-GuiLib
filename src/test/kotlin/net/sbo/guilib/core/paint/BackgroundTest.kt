package net.sbo.guilib.core.paint

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.css.BgBox
import net.sbo.guilib.core.css.BgPosition
import net.sbo.guilib.core.css.BgRepeat
import net.sbo.guilib.core.css.BgRepeatXY
import net.sbo.guilib.core.css.BgSize
import net.sbo.guilib.core.css.Dim
import net.sbo.guilib.core.css.Origin
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.Rect
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** background-size / -position / -repeat / -origin / -clip. A 40×20 box, images "m:16.png" (16×16) and "m:wide.png" (16×8). */
class BackgroundTest {
    private val ua = javaClass.getResource("/assets/guilib/css/ua.css")!!.readText()

    private fun paint(css: String): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse(ua, "ua", Origin.USER_AGENT), Stylesheet.parse(".b { width: 40px; height: 20px } $css", "t.css")), clock = { 0L })
        root.painter.imageSize = { src -> when (src) { "m:16.png" -> 16f to 16f; "m:wide.png" -> 16f to 8f; else -> null } }
        root.render(VComponent(component("T") { div(className = "b") }, Unit, null))
        root.frame(200f, 100f)
        return root
    }

    private fun UiRoot.images() = painter.commands.filterIsInstance<PaintCommand.Image>().map { Rect(it.x, it.y, it.width, it.height) }
    private fun UiRoot.gradients() = painter.commands.filterIsInstance<PaintCommand.Gradient>().map { Rect(it.x, it.y, it.width, it.height) }
    private fun UiRoot.clips() = painter.commands.filterIsInstance<PaintCommand.PushClip>().map { it.rect }

    @Test
    fun withoutSizePositionOrRepeatTheImageStillFillsTheBorderBox() {
        // GuiLib's default (documented): unchanged from before, also under the border.
        val root = paint(".b { border: 2px solid red; background-image: url('m:16.png') }")
        assertEquals(listOf(Rect(0f, 0f, 40f, 20f)), root.images())
        assertTrue(root.clips().isEmpty())
    }

    @Test
    fun centeredWithoutRepeat() {
        val root = paint(".b { background: url('m:16.png') center no-repeat }")
        assertEquals(listOf(Rect(12f, 2f, 16f, 16f)), root.images())
        assertTrue(root.clips().isEmpty())
    }

    @Test
    fun coverAndContain() {
        val cover = paint(".b { height: 40px; background: url('m:wide.png') center / cover no-repeat }")
        assertEquals(listOf(Rect(-20f, 0f, 80f, 40f)), cover.images())
        assertEquals(listOf(Rect(0f, 0f, 40f, 40f)), cover.clips()) // the overflowing copy is clipped to the box
        val contain = paint(".b { height: 40px; background: url('m:wide.png') center / contain no-repeat }")
        assertEquals(listOf(Rect(0f, 10f, 40f, 20f)), contain.images())
    }

    @Test
    fun repeatTilesTheWholeBox() {
        val root = paint(".b { background-image: url('m:16.png'); background-repeat: repeat }")
        assertEquals(6, root.images().size)
        assertEquals(listOf(0f, 16f, 32f), root.images().map { it.x }.distinct())
        assertEquals(listOf(0f, 16f), root.images().map { it.y }.distinct())
        assertEquals(listOf(Rect(0f, 0f, 40f, 20f)), root.clips())
    }

    @Test
    fun repeatStartsFromThePositionInBothDirections() {
        val root = paint(".b { background: url('m:16.png') center repeat-x }")
        assertEquals(listOf(-4f, 12f, 28f), root.images().map { it.x })
        assertTrue(root.images().all { it.y == 2f })
    }

    @Test
    fun edgeOffsetsAndPercentages() {
        assertEquals(listOf(Rect(20f, 2f, 16f, 16f)), paint(".b { background: url('m:16.png') right 4px bottom 2px no-repeat }").images())
        assertEquals(listOf(Rect(24f, 0f, 16f, 16f)), paint(".b { background: url('m:16.png') 100% 0 no-repeat }").images())
        assertEquals(listOf(Rect(24f, 4f, 16f, 16f)), paint(".b { background: url('m:16.png') bottom right no-repeat }").images())
        assertEquals(listOf(Rect(5f, 3f, 16f, 16f)), paint(".b { background: url('m:16.png') 5px 3px no-repeat }").images())
    }

    @Test
    fun explicitSizesKeepTheRatioForAuto() {
        assertEquals(listOf(Rect(0f, 0f, 8f, 4f)), paint(".b { background: url('m:wide.png') 0 0 / 8px no-repeat }").images())
        assertEquals(listOf(Rect(0f, 0f, 20f, 10f)), paint(".b { background: url('m:wide.png') 0 0 / 50% auto no-repeat }").images())
        assertEquals(listOf(Rect(0f, 0f, 40f, 20f)), paint(".b { background: url('m:wide.png') 0 0 / 100% 100% no-repeat }").images())
    }

    @Test
    fun spaceAndRound() {
        // space: two whole copies, first and last at the edges.
        assertEquals(listOf(0f, 24f), paint(".b { background: url('m:16.png') space no-repeat }").images().map { it.x })
        // round: 40 / 16 = 2.5 → 3 copies of 13.33px; the auto height follows the ratio.
        val round = paint(".b { background: url('m:16.png') round no-repeat }").images()
        assertEquals(3, round.size)
        assertEquals(40f / 3f, round[0].width, 0.001f)
        assertEquals(40f / 3f, round[0].height, 0.001f)
    }

    @Test
    fun originAndClipBoxes() {
        val css = ".b { border: 2px solid red; padding: 3px; background-repeat: no-repeat; background-image: url('m:16.png') }"
        assertEquals(2f, paint("$css .b { background-origin: padding-box }").images().single().x) // the default origin
        assertEquals(5f, paint("$css .b { background-origin: content-box }").images().single().x)
        assertEquals(0f, paint("$css .b { background-origin: border-box }").images().single().x)
        // A stretched layer fills its clip box; the color follows the clip of the bottom layer.
        val clipped = paint(".b { border: 2px solid red; padding: 3px; background: #00ff00 url('m:16.png') content-box }")
        assertEquals(listOf(Rect(5f, 5f, 30f, 10f)), clipped.images())
        val color = clipped.painter.commands.filterIsInstance<PaintCommand.Box>().first { it.background == 0xFF00FF00.toInt() }
        assertEquals(Rect(5f, 5f, 30f, 10f), Rect(color.x, color.y, color.width, color.height))
        val colorOnly = paint(".b { border: 2px solid red; background-color: #00ff00; background-clip: padding-box }")
        val c = colorOnly.painter.commands.filterIsInstance<PaintCommand.Box>().first { it.background == 0xFF00FF00.toInt() }
        assertEquals(Rect(2f, 2f, 36f, 16f), Rect(c.x, c.y, c.width, c.height))
    }

    @Test
    fun gradientsTileWithASize() {
        val root = paint(".b { background: linear-gradient(red, blue) 0 0 / 10px 10px }")
        assertEquals(8, root.gradients().size)
        assertTrue(root.gradients().all { it.width == 10f && it.height == 10f })
        assertTrue(root.clips().isEmpty()) // 4×2 copies fit exactly
    }

    @Test
    fun layersTakeTheirOwnValuesAndCycleShortLists() {
        val root = paint(".b { background-image: url('m:16.png'), url('m:wide.png'); background-repeat: no-repeat; background-position: right top, left bottom }")
        // Painted bottom layer first: wide.png at left bottom, then 16.png at right top.
        assertEquals(listOf(Rect(0f, 12f, 16f, 8f), Rect(24f, 0f, 16f, 16f)), root.images())
    }

    @Test
    fun unknownImageSizeSkipsAPositionedLayer() {
        assertFalse(paint(".b { background: url('m:missing.png') center no-repeat }").images().isNotEmpty())
        // The plain stretched layer doesn't need the size.
        assertEquals(1, paint(".b { background: url('m:missing.png') }").images().size)
    }

    @Test
    fun shorthandSetsEveryPart() {
        val root = paint(".b { background: #ff0000 url('m:16.png') right 4px top / 8px space round content-box padding-box }")
        val s = root.document.body.querySelector(".b")!!.style
        assertEquals(listOf(BgSize.Explicit(Dim.Px(8f), Dim.Auto)), s.backgroundSizes)
        assertEquals(listOf(BgPosition(Dim.Px(4f), Dim.Pct(0f), fromRight = true)), s.backgroundPositions)
        assertEquals(listOf(BgRepeatXY(BgRepeat.SPACE, BgRepeat.ROUND)), s.backgroundRepeats)
        assertEquals(listOf(BgBox.CONTENT_BOX), s.backgroundOrigins)
        assertEquals(listOf(BgBox.PADDING_BOX), s.backgroundClips)
        // A later `background: <color>` resets everything else again.
        val reset = paint(".b { background: url('m:16.png') center / cover no-repeat } .b { background: #00ff00 }")
        val r = reset.document.body.querySelector(".b")!!.style
        assertTrue(r.backgroundSizes.isEmpty() && r.backgroundPositions.isEmpty() && r.backgroundRepeats.isEmpty())
    }

    @Test
    fun invalidValuesAreDropped() {
        for (bad in listOf("background-size: -5px", "background-position: left right", "background-repeat: repeat-x repeat", "background: url('m:16.png') / 10px")) {
            val s = paint(".b { $bad }").document.body.querySelector(".b")!!.style
            assertTrue(s.backgroundSizes.isEmpty() && s.backgroundPositions.isEmpty() && s.backgroundRepeats.isEmpty(), bad)
        }
    }

    @Test
    fun tilesHelperCoversTheClipBox() {
        val tiles = BackgroundTiles.tiles(Rect(0f, 0f, 30f, 30f), Rect(-5f, -5f, 40f, 40f), 10f to 10f, null, null, null)
        assertEquals(listOf(-10f, 0f, 10f, 20f, 30f), tiles.map { it.x }.distinct())
        // Absurdly many copies draw nothing instead of flooding the renderer.
        assertTrue(BackgroundTiles.tiles(Rect(0f, 0f, 4000f, 4000f), Rect(0f, 0f, 4000f, 4000f), 1f to 1f, null, null, null).isEmpty())
    }
}
