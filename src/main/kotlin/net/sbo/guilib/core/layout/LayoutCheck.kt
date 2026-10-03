package net.sbo.guilib.core.layout

/**
 * Check for incremental layout: with `-Dguilib.layout.verify=true` (set for the unit tests) every incremental layout
 * pass is compared with a full layout of the same tree and a difference throws; `=log` logs the first differences
 * instead (for the game; `runClient -Pguilib.dev.layout.verify=log` passes it on).
 */
internal object LayoutCheck {
    private fun prop(name: String): String? = System.getProperty("guilib.layout.$name") ?: System.getProperty("guilib.dev.layout.$name")

    @Volatile var enabled = prop("verify").let { it == "true" || it == "log" }
    private val logOnly = prop("verify") == "log"
    private var logged = 0

    fun compareWithFullLayout(root: LayoutNode, fullLayout: () -> Unit) {
        val incremental = ArrayList<String>()
        dump(root, "", incremental)
        fullLayout()
        val full = ArrayList<String>()
        dump(root, "", full)
        if (incremental == full) return
        val i = incremental.indices.firstOrNull { it >= full.size || incremental[it] != full[it] } ?: full.size
        val msg = "incremental layout differs from a full layout:\n  incremental: ${incremental.getOrNull(i)}\n  full:        ${full.getOrNull(i)}"
        if (!logOnly) throw IllegalStateException(msg)
        if (logged++ < 20) net.sbo.guilib.core.Log.error("GuiLib: $msg")
    }

    private fun dump(node: LayoutNode, path: String, out: MutableList<String>) {
        val b = node.box
        val name = "$path/${node.textContent?.let { "\"${it.take(12)}\"" } ?: node}"
        out += "$name visible=${b.visible} box=${b.x},${b.y} ${b.width}x${b.height} m=${b.margin} p=${b.padding} " +
            "scroll=${b.scrollWidth}x${b.scrollHeight} baseline=${b.baseline} inParagraph=${b.inParagraph} shift=${b.baselineShift}"
        for (p in b.paragraphs) {
            out += "$name paragraph ${p.x},${p.y} w=${p.width}"
            for (l in p.lines) {
                out += "$name   line y=${l.y} w=${l.width} h=${l.height} baseline=${l.baseline} " +
                    l.fragments.joinToString { f ->
                        when (f) {
                            is Fragment.Text -> "text(${f.x},${f.width},${f.text},${f.shift})"
                            is Fragment.Box -> "box(${f.x},${f.width},${f.y})"
                            is Fragment.Edge -> "edge(${f.x},${f.width},${f.start})"
                        }
                    }
            }
        }
        if (!b.visible) return
        node.layoutChildren.forEachIndexed { i, c -> dump(c, "$name[$i]", out) }
    }
}
