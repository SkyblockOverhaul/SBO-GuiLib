package net.sbo.guilib.core.paint

import net.sbo.guilib.core.UiRoot
import net.sbo.guilib.core.anim.Interpolation
import net.sbo.guilib.core.css.ColorMatrix
import net.sbo.guilib.core.css.Colors
import net.sbo.guilib.core.css.FilterFn
import net.sbo.guilib.core.css.FilterFn.ColorFn.Kind
import net.sbo.guilib.core.css.Prop
import net.sbo.guilib.core.css.Stylesheet
import net.sbo.guilib.core.dom.VComponent
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.layout.FakeMeasurer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FilterTest {
    private fun ui(css: String, content: net.sbo.guilib.core.dsl.ComponentScope.() -> Unit = { div(className = "a") {} }): UiRoot {
        val root = UiRoot(FakeMeasurer, listOf(Stylesheet.parse("div { display: block }\n$css", "t.css")), clock = { 0L })
        root.render(VComponent(component("T") { content() }, Unit, null))
        root.frame(200f, 100f)
        return root
    }

    private fun filterOf(value: String) = ui(".a { filter: $value }").document.body.querySelector(".a")!!.style.filter

    private fun UiRoot.boxes() = painter.commands.filterIsInstance<PaintCommand.Box>()
    private fun UiRoot.shadows() = painter.commands.filterIsInstance<PaintCommand.Shadow>()

    @Test
    fun parsesFunctionLists() {
        assertEquals(
            listOf(
                FilterFn.ColorFn(Kind.GRAYSCALE, 1f), FilterFn.ColorFn(Kind.BRIGHTNESS, 0.5f), FilterFn.ColorFn(Kind.HUE_ROTATE, 90f),
                FilterFn.Blur(3f), FilterFn.DropShadow(1f, 2f, 4f, 0xFFFF0000.toInt()),
            ),
            filterOf("grayscale() brightness(50%) hue-rotate(0.25turn) blur(3px) drop-shadow(1px 2px 4px #ff0000)"),
        )
        // drop-shadow without a color uses currentColor; the color may come first.
        assertEquals(listOf(FilterFn.DropShadow(1f, 1f, 0f, Colors.WHITE)), filterOf("drop-shadow(1px 1px)"))
        assertEquals(listOf(FilterFn.DropShadow(0f, 2f, 0f, 0xFF00FF00.toInt())), filterOf("drop-shadow(#00ff00 0 2px)"))
        assertTrue(filterOf("none").isEmpty())
        // Invalid values drop the declaration.
        for (bad in listOf("blur(-1px)", "brightness(-1)", "grayscale(1, 2)", "url(#f)", "sharpen(2)", "blur(10%)", "drop-shadow(1px)", "hue-rotate(10)")) {
            val root = ui(".a { filter: invert(1); filter: $bad }")
            assertEquals(listOf(FilterFn.ColorFn(Kind.INVERT, 1f)), root.document.body.querySelector(".a")!!.style.filter, bad)
        }
    }

    @Test
    fun colorMatricesFollowTheSpec() {
        val red = 0xFFFF0000.toInt()
        val gray = ColorMatrix.of(Kind.GRAYSCALE, 1f).apply(red)
        assertEquals(Colors.red(gray), Colors.green(gray))
        assertEquals(54, Colors.red(gray)) // 0.2126 * 255
        assertEquals(Colors.BLACK, ColorMatrix.of(Kind.INVERT, 1f).apply(Colors.WHITE))
        assertEquals(0xFF808080.toInt(), ColorMatrix.of(Kind.BRIGHTNESS, 0.5f).apply(Colors.WHITE))
        assertEquals(0x80FF0000.toInt(), ColorMatrix.of(Kind.OPACITY, 0.5f).apply(red))
        assertEquals(0xFF808080.toInt(), ColorMatrix.of(Kind.CONTRAST, 0f).apply(0xFF123456.toInt()))
        // Amount 0 (or hue-rotate 0) changes nothing.
        for (k in listOf(Kind.GRAYSCALE, Kind.SEPIA, Kind.INVERT, Kind.HUE_ROTATE)) assertEquals(0xFF336699.toInt(), ColorMatrix.of(k, 0f).apply(0xFF336699.toInt()), k.css)
    }

    @Test
    fun colorFunctionsRecolorTheWholeSubtree() {
        val root = ui(
            """
            .a { filter: grayscale(1); background-color: #ff0000; border: 1px solid #00ff00; padding: 2px }
            .b { background-color: #0000ff; height: 4px }
            .p { position: absolute; background-color: #ffff00; width: 4px; height: 4px }
            """.trimIndent(),
        ) { div(className = "a") { div(className = "b") {}; div(className = "p") {} } }
        val colors = root.boxes().flatMap { listOf(it.background) + it.borderColors.toList() }.filter { Colors.alpha(it) > 0 }
        assertTrue(colors.size >= 4)
        for (c in colors) assertTrue(Colors.red(c) == Colors.green(c) && Colors.green(c) == Colors.blue(c), Integer.toHexString(c))
    }

    @Test
    fun nestedFiltersApplyInsideOut() {
        // invert(1) of the inner box, then brightness(0) of everything: black.
        val root = ui(".a { filter: brightness(0) } .b { filter: invert(1); background-color: #ff8800; height: 4px }") {
            div(className = "a") { div(className = "b") {} }
        }
        assertEquals(Colors.BLACK, root.boxes().single().background)
    }

    @Test
    fun blurTurnsBoxesIntoBlurredShapes() {
        val root = ui(
            """
            .a { filter: blur(3px); width: 40px; height: 20px; background-color: #ff0000; border: 2px solid #ffffff; border-radius: 4px;
                 box-shadow: 0 0 4px #000000 }
            """.trimIndent(),
        )
        assertTrue(root.boxes().isEmpty())
        val s = root.shadows()
        // The box-shadow gets the filter's blur on top (CSS radius 2 × 3px combined with 4px), then background and border ring.
        assertEquals(ShadowMode.OUTER, s[0].mode)
        assertEquals(kotlin.math.sqrt(16f + 36f), s[0].blur, 0.001f)
        val bg = s.single { it.mode == ShadowMode.PLAIN }
        assertEquals(listOf(2f, 2f, 36f, 16f), listOf(bg.x, bg.y, bg.width, bg.height))
        assertEquals(6f, bg.blur)
        assertEquals(2f, bg.radii[0])
        val ring = s.single { it.mode == ShadowMode.RING }
        assertEquals(-2f, ring.spread)
        assertEquals(Colors.WHITE, ring.color)
        assertEquals(4f, ring.radii[0])
    }

    @Test
    fun blurAndColorFiltersReachImages() {
        val root = ui(".a { width: 8px; height: 8px; background-image: url(mymod:a.png); filter: sepia(1) blur(2px) }")
        val img = root.painter.commands.filterIsInstance<PaintCommand.Image>().single()
        assertEquals(listOf(ImageOp.Matrix(ColorMatrix.of(Kind.SEPIA, 1f)), ImageOp.Blur(2f)), img.filters)
    }

    @Test
    fun dropShadowPaintsUnderTheWholeElementIncludingPositionedChildren() {
        val root = ui(
            """
            .a { filter: drop-shadow(2px 3px 4px rgba(0, 0, 0, 0.5)); width: 20px; height: 10px; background-color: #ffffff; overflow: hidden }
            .p { position: absolute; left: 30px; width: 5px; height: 5px; background-color: #ff0000 }
            """.trimIndent(),
        ) { div(className = "a") { div(className = "p") {} } }
        val cmds = root.painter.commands
        // Shadows first: the box, then (inside the copied clip, shifted by the offset) the positioned child.
        val firstBox = cmds.indexOfFirst { it is PaintCommand.Box }
        val shadows = cmds.subList(0, firstBox).filterIsInstance<PaintCommand.Shadow>()
        assertEquals(2, shadows.size)
        assertEquals(listOf(2f, 3f, 20f, 10f), listOf(shadows[0].x, shadows[0].y, shadows[0].width, shadows[0].height))
        assertEquals(4f, shadows[0].blur)
        assertEquals(ShadowMode.PLAIN, shadows[0].mode)
        assertTrue(Colors.alpha(shadows[0].color) in 127..128)
        assertEquals(32f, shadows[1].x)
        // The positioned child is painted inside the filtered element (after its box), not on a later layer.
        assertEquals(2, cmds.count { it is PaintCommand.Box })
        val clips = cmds.filterIsInstance<PaintCommand.PushClip>()
        assertEquals(clips[clips.size / 2].rect.x + 2f, clips[0].rect.x) // the copied clip moves with the shadow
    }

    @Test
    fun dropShadowCopiesTextAndImageSilhouettes() {
        val root = ui(".a { color: #ffffff; filter: drop-shadow(1px 1px #ff0000) } .i { width: 8px; height: 8px; background-image: url(mymod:a.png) }") {
            div(className = "a") { +"hi"; div(className = "i") {} }
        }
        val texts = root.painter.commands.filterIsInstance<PaintCommand.Text>()
        assertEquals(2, texts.size)
        assertEquals(0xFFFF0000.toInt(), texts[0].color)
        assertEquals(texts[1].x + 1f, texts[0].x)
        val images = root.painter.commands.filterIsInstance<PaintCommand.Image>()
        assertEquals(listOf<ImageOp>(ImageOp.Silhouette(0xFFFF0000.toInt())), images[0].filters)
        assertTrue(images[1].filters.isEmpty())
    }

    @Test
    fun offsetsAndBlurScaleWithTheElement() {
        val root = ui(".a { transform: scale(2); transform-origin: 0 0; width: 10px; height: 10px; background-color: #fff; filter: drop-shadow(1px 2px 3px #000) }")
        val s = root.shadows().single()
        assertEquals(2f, s.x)
        assertEquals(4f, s.y)
        assertEquals(6f, s.blur)
    }

    @Test
    fun filtersAnimate() {
        val none = emptyList<FilterFn>()
        assertEquals(listOf(FilterFn.Blur(2f)), Interpolation.interpolate(Prop.FILTER, none, listOf(FilterFn.Blur(4f)), 0.5f))
        assertEquals(
            listOf(FilterFn.ColorFn(Kind.GRAYSCALE, 0.5f), FilterFn.ColorFn(Kind.BRIGHTNESS, 1.5f)),
            Interpolation.interpolate(Prop.FILTER, listOf(FilterFn.ColorFn(Kind.GRAYSCALE, 1f)), listOf(FilterFn.ColorFn(Kind.GRAYSCALE, 0f), FilterFn.ColorFn(Kind.BRIGHTNESS, 2f)), 0.5f),
        )
        assertNull(Interpolation.interpolate(Prop.FILTER, listOf(FilterFn.Blur(1f)), listOf(FilterFn.ColorFn(Kind.INVERT, 1f)), 0.5f))
    }

    @Test
    fun imagePixelOpsBlurIntoThePaddingAndKeepTheirMass() {
        val w = 5
        val px = IntArray(w * w).also { it[12] = Colors.WHITE }
        val r = ImageFilters.apply(px, w, w, listOf(ImageOp.Blur(1f)), 2f)
        assertEquals(6, r.pad)
        assertEquals(w + 12, r.width)
        val mass = r.argb.sumOf { Colors.alpha(it) }
        assertTrue(mass in 230..280, "alpha sum $mass")
        // Blurred white stays white where visible (premultiplied blur: no dark fringe).
        for (c in r.argb) if (Colors.alpha(c) > 8) assertEquals(0xFFFFFF, c and 0xFFFFFF)
        val sil = ImageFilters.apply(intArrayOf(0x80FFFFFF.toInt()), 1, 1, listOf(ImageOp.Silhouette(0xFF00FF00.toInt())), 1f)
        assertEquals(0x8000FF00.toInt(), sil.argb[0])
    }
}
