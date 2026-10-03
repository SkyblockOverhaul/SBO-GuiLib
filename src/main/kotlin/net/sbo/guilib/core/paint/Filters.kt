package net.sbo.guilib.core.paint

import net.sbo.guilib.core.css.ColorMatrix
import net.sbo.guilib.core.css.Colors
import net.sbo.guilib.core.css.FilterFn
import net.sbo.guilib.core.css.TextShadow
import net.sbo.guilib.core.dom.Rect
import net.sbo.guilib.core.dom.Transform2D
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * CSS `filter` on an element: rewrites the [PaintCommand]s its subtree emitted, one filter function after the other.
 * There is no offscreen pass, so every command is filtered on its own:
 * - color functions (brightness, contrast, grayscale, sepia, saturate, hue-rotate, invert, opacity) map the colors of
 *   boxes, borders, shadows, gradients and text, and the pixels of images (exact, apart from clamping per command);
 * - `blur()` turns boxes into blurred shapes (shadow shader), adds to the blur of shadows and blurs images; text and
 *   gradients stay sharp;
 * - `drop-shadow()` inserts a shadow copy of the commands below them: blurred box shapes, shifted text in the shadow
 *   color (without blur), image silhouettes.
 * Minecraft content (items, entities, player heads) is drawn unfiltered.
 */
internal object FilterPass {
    /**
     * Where the commands live: offsets and blur radii in the element's CSS px are multiplied by [sx]/[sy] and [k]
     * (the folded axis-aligned scale; 1 when the commands are drawn through a matrix). Clip rects are in screen
     * coordinates, so their shift is the offset mapped through [clipMap] (the element's matrix), if any.
     */
    class Space(val sx: Float, val sy: Float, val k: Float, val clipMap: Transform2D?)

    fun apply(cmds: MutableList<PaintCommand>, start: Int, fns: List<FilterFn>, space: Space, poseAtStart: Transform2D?) {
        for (fn in fns) when (fn) {
            is FilterFn.ColorFn -> {
                if (fn.amount == fn.kind.identity) continue
                val m = fn.matrix
                for (i in start until cmds.size) cmds[i] = mapColors(cmds[i], m)
            }
            is FilterFn.Blur -> {
                if (fn.radius <= 0f) continue
                val sigma = fn.radius * space.k
                val out = ArrayList<PaintCommand>(cmds.size - start)
                for (i in start until cmds.size) blur(cmds[i], sigma, out)
                while (cmds.size > start) cmds.removeAt(cmds.size - 1)
                cmds.addAll(out)
            }
            is FilterFn.DropShadow -> {
                if (Colors.alpha(fn.color) == 0) continue
                val copy = dropShadow(cmds.subList(start, cmds.size), fn, space)
                if (copy.any { it is PaintCommand.SetTransform }) copy += PaintCommand.SetTransform(poseAtStart)
                cmds.addAll(start, copy)
            }
        }
    }

    // ---- color functions -----------------------------------------------------------------------------------------

    private fun mapColors(cmd: PaintCommand, m: ColorMatrix): PaintCommand = when (cmd) {
        is PaintCommand.Box -> PaintCommand.Box(
            cmd.x, cmd.y, cmd.width, cmd.height, m.apply(cmd.background), cmd.radii, cmd.borders,
            IntArray(4) { m.apply(cmd.borderColors[it]) },
        )
        is PaintCommand.Shadow -> withShadow(cmd, color = m.apply(cmd.color))
        is PaintCommand.Text -> {
            val sh = cmd.style.shadow
            val style = if (sh != null && sh.color is Int) cmd.style.copy(shadow = TextShadow(sh.offsetX, sh.offsetY, m.apply(sh.color))) else cmd.style
            PaintCommand.Text(cmd.x, cmd.y, cmd.text, style, m.apply(cmd.color), cmd.alpha)
        }
        is PaintCommand.Image -> image(cmd, filters = cmd.filters + ImageOp.Matrix(m))
        is PaintCommand.Gradient -> PaintCommand.Gradient(cmd.x, cmd.y, cmd.width, cmd.height, mappedMesh(cmd.mesh, m), cmd.radii)
        else -> cmd
    }

    private data class MeshKey(val mesh: ColorMesh, val matrix: ColorMatrix)

    /** Recolored gradient meshes; meshes are shared and cached, so animated gradients don't recolor every frame. */
    private val meshes = object : LinkedHashMap<MeshKey, ColorMesh>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MeshKey, ColorMesh>) = size > 128
    }

    private fun mappedMesh(mesh: ColorMesh, m: ColorMatrix): ColorMesh =
        meshes.getOrPut(MeshKey(mesh, m)) { ColorMesh(mesh.x, mesh.y, IntArray(mesh.color.size) { m.apply(mesh.color[it]) }) }

    // ---- blur ----------------------------------------------------------------------------------------------------

    /** Gaussian blurs add up like this (in CSS blur radii). */
    private fun combine(a: Float, b: Float) = sqrt(a * a + b * b)

    private fun blur(cmd: PaintCommand, sigma: Float, out: MutableList<PaintCommand>) {
        when (cmd) {
            is PaintCommand.Box -> boxShapes(cmd, 0f, 0f, 2f * sigma, { it }, out)
            is PaintCommand.Shadow -> out += withShadow(cmd, blur = combine(cmd.blur, 2f * sigma))
            is PaintCommand.Image -> out += image(cmd, filters = cmd.filters + ImageOp.Blur(sigma))
            else -> out += cmd // text, gradients and Minecraft content stay sharp
        }
    }

    /**
     * The blurred shape of a box, as shadow-shader commands moved by ([dx], [dy]) with CSS blur radius [blur]:
     * the background in the padding box and the border as a ring (uniform borders) or one strip per side.
     * [colorOf] maps the box's colors (the identity for `blur()`, the shadow color for `drop-shadow()`).
     */
    private fun boxShapes(b: PaintCommand.Box, dx: Float, dy: Float, blur: Float, colorOf: (Int) -> Int, out: MutableList<PaintCommand>) {
        if (b.width <= 0f || b.height <= 0f) return
        fun shape(x: Float, y: Float, w: Float, h: Float, radii: FloatArray, spread: Float, color: Int, mode: ShadowMode) {
            if (w <= 0f || h <= 0f || Colors.alpha(color) == 0) return
            out += PaintCommand.Shadow(x + dx, y + dy, w, h, radii, 0f, 0f, blur, spread, color, false, mode)
        }
        val bg = colorOf(b.background)
        val bw = b.borders
        val sides = (0 until 4).filter { bw[it] > 0f && Colors.alpha(colorOf(b.borderColors[it])) > 0 }
        // Background and border of the same color: one shape for the whole border box.
        if (Colors.alpha(bg) > 0 && sides.all { colorOf(b.borderColors[it]) == bg }) {
            shape(b.x, b.y, b.width, b.height, b.radii, 0f, bg, ShadowMode.PLAIN)
            return
        }
        if (Colors.alpha(bg) > 0) {
            val inner = floatArrayOf(
                (b.radii[0] - maxOf(bw[0], bw[3])).coerceAtLeast(0f), (b.radii[1] - maxOf(bw[0], bw[1])).coerceAtLeast(0f),
                (b.radii[2] - maxOf(bw[2], bw[1])).coerceAtLeast(0f), (b.radii[3] - maxOf(bw[2], bw[3])).coerceAtLeast(0f),
            )
            shape(b.x + bw[3], b.y + bw[0], b.width - bw[1] - bw[3], b.height - bw[0] - bw[2], inner, 0f, bg, ShadowMode.PLAIN)
        }
        if (sides.isEmpty()) return
        val c0 = colorOf(b.borderColors[sides[0]])
        if (sides.size == 4 && (0 until 4).all { bw[it] == bw[0] && colorOf(b.borderColors[it]) == c0 }) {
            shape(b.x, b.y, b.width, b.height, b.radii, -bw[0], c0, ShadowMode.RING)
            return
        }
        val none = FloatArray(4)
        for (side in sides) {
            val c = colorOf(b.borderColors[side])
            when (side) {
                0 -> shape(b.x, b.y, b.width, bw[0], none, 0f, c, ShadowMode.PLAIN)
                2 -> shape(b.x, b.y + b.height - bw[2], b.width, bw[2], none, 0f, c, ShadowMode.PLAIN)
                3 -> shape(b.x, b.y + bw[0], bw[3], b.height - bw[0] - bw[2], none, 0f, c, ShadowMode.PLAIN)
                else -> shape(b.x + b.width - bw[1], b.y + bw[0], bw[1], b.height - bw[0] - bw[2], none, 0f, c, ShadowMode.PLAIN)
            }
        }
    }

    // ---- drop-shadow ---------------------------------------------------------------------------------------------

    private fun dropShadow(range: List<PaintCommand>, fn: FilterFn.DropShadow, space: Space): MutableList<PaintCommand> {
        val dx = fn.offsetX * space.sx
        val dy = fn.offsetY * space.sy
        val blur = fn.blur * space.k
        val cdx = space.clipMap?.let { it.a * fn.offsetX + it.c * fn.offsetY } ?: dx
        val cdy = space.clipMap?.let { it.b * fn.offsetX + it.d * fn.offsetY } ?: dy
        // The shadow has the shadow color with the alpha of what casts it.
        fun tint(c: Int) = Colors.withOpacity(fn.color, Colors.alpha(c) / 255f)
        val out = ArrayList<PaintCommand>()
        for (cmd in range) when (cmd) {
            is PaintCommand.Box -> boxShapes(cmd, dx, dy, blur, ::tint, out)
            is PaintCommand.Shadow -> {
                val c = tint(cmd.color)
                if (Colors.alpha(c) > 0) {
                    out += withShadow(cmd, x = cmd.x + dx, y = cmd.y + dy, blur = combine(cmd.blur, blur), color = c)
                }
            }
            is PaintCommand.Text -> {
                val sh = cmd.style.shadow
                val style = if (sh != null) cmd.style.copy(shadow = TextShadow(sh.offsetX, sh.offsetY, tint((sh.color as? Int) ?: cmd.color))) else cmd.style
                out += PaintCommand.Text(cmd.x + dx, cmd.y + dy, cmd.text, style, tint(cmd.color), cmd.alpha)
            }
            is PaintCommand.Gradient -> {
                val colors = cmd.mesh.color
                if (colors.isNotEmpty()) {
                    val avg = colors.sumOf { Colors.alpha(it) } / colors.size
                    val c = Colors.withOpacity(fn.color, avg / 255f)
                    if (Colors.alpha(c) > 0) out += PaintCommand.Shadow(cmd.x + dx, cmd.y + dy, cmd.width, cmd.height, cmd.radii, 0f, 0f, blur, 0f, c, false, ShadowMode.PLAIN)
                }
            }
            is PaintCommand.Image -> {
                val ops = cmd.filters + ImageOp.Silhouette(fn.color) + (if (blur > 0f) listOf(ImageOp.Blur(blur / 2f)) else emptyList())
                out += image(cmd, x = cmd.x + dx, y = cmd.y + dy, filters = ops)
            }
            is PaintCommand.PushClip -> out += PaintCommand.PushClip(Rect(cmd.rect.x + cdx, cmd.rect.y + cdy, cmd.rect.width, cmd.rect.height))
            is PaintCommand.Replaced -> {} // Minecraft content casts no shadow
            else -> out += cmd // PopClip, SetTransform
        }
        return out
    }

    // ---- copies --------------------------------------------------------------------------------------------------

    private fun withShadow(
        s: PaintCommand.Shadow, x: Float = s.x, y: Float = s.y, blur: Float = s.blur, color: Int = s.color,
    ) = PaintCommand.Shadow(x, y, s.width, s.height, s.radii, s.offsetX, s.offsetY, blur, s.spread, color, s.inset, s.mode)

    private fun image(i: PaintCommand.Image, x: Float = i.x, y: Float = i.y, filters: List<ImageOp>) =
        PaintCommand.Image(x, y, i.width, i.height, i.src, i.fit, i.alpha, i.radii, i.color, filters)
}

/** The pixel side of `filter` for images: [ImageOp]s on straight-alpha ARGB pixels. */
object ImageFilters {
    /** [argb] is [width]×[height] and has [pad] extra transparent pixels on every side (room for blur). */
    class Result(val argb: IntArray, val width: Int, val height: Int, val pad: Int)

    fun needsResolution(ops: List<ImageOp>) = ops.any { it !is ImageOp.Matrix }

    /** Extra pixels per side for the blurs in [ops] at [pxPerGui] physical pixels per GUI px (3 standard deviations). */
    fun padding(ops: List<ImageOp>, pxPerGui: Float): Int {
        val v = ops.sumOf { if (it is ImageOp.Blur) (it.sigma * pxPerGui).toDouble().let { s -> s * s } else 0.0 }
        return if (v <= 0.0) 0 else ceil(3.0 * sqrt(v)).toInt()
    }

    fun apply(src: IntArray, w: Int, h: Int, ops: List<ImageOp>, pxPerGui: Float): Result {
        val pad = padding(ops, pxPerGui)
        val pw = w + 2 * pad
        val ph = h + 2 * pad
        val px = if (pad == 0) src.copyOf() else IntArray(pw * ph).also { for (y in 0 until h) src.copyInto(it, (y + pad) * pw + pad, y * w, y * w + w) }
        for (op in ops) when (op) {
            is ImageOp.Matrix -> op.matrix.applyAll(px)
            is ImageOp.Silhouette -> {
                val rgb = op.color and 0x00FFFFFF
                val a = Colors.alpha(op.color)
                for (i in px.indices) px[i] = rgb or (((px[i] ushr 24) * a + 127) / 255 shl 24)
            }
            is ImageOp.Blur -> gaussian(px, pw, ph, op.sigma * pxPerGui)
        }
        return Result(px, pw, ph, pad)
    }

    /** Gaussian blur approximated by three box blurs per axis, on premultiplied colors (no dark fringes). */
    fun gaussian(px: IntArray, w: Int, h: Int, sigma: Float) {
        if (sigma < 0.3f || px.isEmpty()) return
        val n = px.size
        val ch = Array(4) { FloatArray(n) }
        for (i in 0 until n) {
            val c = px[i]
            val a = (c ushr 24) / 255f
            ch[0][i] = a
            ch[1][i] = ((c shr 16) and 0xFF) / 255f * a
            ch[2][i] = ((c shr 8) and 0xFF) / 255f * a
            ch[3][i] = (c and 0xFF) / 255f * a
        }
        val tmp = FloatArray(n)
        for (size in boxSizes(sigma, 3)) {
            val r = (size - 1) / 2
            for (c in ch) {
                boxPass(c, tmp, w, h, r, horizontal = true)
                boxPass(tmp, c, w, h, r, horizontal = false)
            }
        }
        for (i in 0 until n) {
            val a = ch[0][i]
            if (a <= 0.5f / 255f) {
                px[i] = 0; continue
            }
            fun v(x: Float) = (x / a * 255f).roundToInt().coerceIn(0, 255)
            px[i] = ((a * 255f).roundToInt().coerceIn(0, 255) shl 24) or (v(ch[1][i]) shl 16) or (v(ch[2][i]) shl 8) or v(ch[3][i])
        }
    }

    /** Odd box widths whose repeated application approximates a Gaussian (Kovesi). */
    private fun boxSizes(sigma: Float, n: Int): IntArray {
        val ideal = sqrt(12.0 * sigma * sigma / n + 1)
        var wl = floor(ideal).toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val m = ((12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4.0)).roundToInt()
        return IntArray(n) { if (it < m) wl else wu }
    }

    /** Box blur of radius [r] along one axis; outside the image is transparent. */
    private fun boxPass(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val step = if (horizontal) 1 else w
        val inv = 1f / (2 * r + 1)
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var sum = 0f
            for (i in 0..minOf(r, len - 1)) sum += src[base + i * step]
            for (i in 0 until len) {
                dst[base + i * step] = sum * inv
                val add = i + r + 1
                val sub = i - r
                if (add < len) sum += src[base + add * step]
                if (sub >= 0) sum -= src[base + sub * step]
            }
        }
    }
}
