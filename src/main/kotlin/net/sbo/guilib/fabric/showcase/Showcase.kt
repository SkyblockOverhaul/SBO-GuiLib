package net.sbo.guilib.fabric.showcase

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.sbo.guilib.core.css.Colors
import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.b
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.checkbox
import net.sbo.guilib.core.dsl.chips
import net.sbo.guilib.core.dsl.classNames
import net.sbo.guilib.core.dsl.code
import net.sbo.guilib.core.dsl.colorInput
import net.sbo.guilib.core.dsl.colorPicker
import net.sbo.guilib.core.dsl.contextMenu
import net.sbo.guilib.core.dsl.details
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.entity
import net.sbo.guilib.core.dsl.h2
import net.sbo.guilib.core.dsl.h3
import net.sbo.guilib.core.dsl.img
import net.sbo.guilib.core.dsl.input
import net.sbo.guilib.core.dsl.item
import net.sbo.guilib.core.dsl.label
import net.sbo.guilib.core.dsl.modal
import net.sbo.guilib.core.dsl.multiSelect
import net.sbo.guilib.core.dsl.nav
import net.sbo.guilib.core.dsl.numberInput
import net.sbo.guilib.core.dsl.p
import net.sbo.guilib.core.dsl.playerHead
import net.sbo.guilib.core.dsl.presence
import net.sbo.guilib.core.dsl.presenceList
import net.sbo.guilib.core.dsl.radioGroup
import net.sbo.guilib.core.dsl.rangeSlider
import net.sbo.guilib.core.dsl.scroll
import net.sbo.guilib.core.dsl.segmented
import net.sbo.guilib.core.dsl.select
import net.sbo.guilib.core.dsl.slider
import net.sbo.guilib.core.dsl.sortableList
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.core.dsl.sub
import net.sbo.guilib.core.dsl.sup
import net.sbo.guilib.core.dsl.switch
import net.sbo.guilib.core.dsl.tabs
import net.sbo.guilib.core.dsl.textarea
import net.sbo.guilib.core.dsl.tooltip
import net.sbo.guilib.core.dsl.useClipboard
import net.sbo.guilib.core.dsl.useToast
import net.sbo.guilib.fabric.GuiLib
import net.sbo.guilib.fabric.entity.FakePlayer
import net.sbo.guilib.fabric.text
import net.sbo.guilib.fabric.useTranslation

/** Demo UI showing every feature; open with `/guilib showcase`. */
object Showcase {
    val SECTIONS = listOf("Buttons", "Forms", "Pickers", "Panels", "Colors", "Boxes", "Animation", "Layout", "Grid", "Text", "Scroll", "Sortable", "Images", "Items", "Entities", "State")

    private val App = component<String>("Showcase") { initialSection ->
        var section by useState(initialSection)
        // The showcase's own GUI scale, independent of Minecraft's (null = Minecraft's). The slider only shows the
        // value while dragging and applies it on release: rescaling under a dragging mouse would move the slider itself.
        var uiScale by useState<Float?>(null)
        var dragged by useState<Float?>(null)
        useScreenScale(uiScale)
        val shown = dragged ?: uiScale ?: net.minecraft.client.Minecraft.getInstance().window.guiScale.toFloat()
        div(className = "window") {
            div(className = "titlebar") {
                span(className = "title") { +"GuiLib Showcase" }
                div(className = "titlebar-actions") {
                    span(className = "scale-label") { +"UI scale" }
                    slider(
                        value = shown, min = 1f, max = 4f, step = 0.25f, className = "scale-slider",
                        onChange = { dragged = it }, onChangeEnd = { dragged = null; uiScale = it },
                        showValue = true, format = { String.format(java.util.Locale.ROOT, "%.2f×", it) },
                    )
                    button(className = classNames("scale-reset", "active" to (uiScale == null)), title = "Use Minecraft's GUI scale", onClick = { uiScale = null }) { +"MC" }
                    button(className = "close", title = "Close", onClick = { GuiLib.close() }) { +"✕" }
                }
            }
            div(className = "body") {
                nav(className = "sidebar") {
                    for (s in SECTIONS) {
                        div(key = s, className = classNames("nav-item", "active" to (s == section)), onClick = { section = s }) { +s }
                    }
                }
                scroll(className = "content") {
                    when (section) {
                        "Buttons" -> ButtonsDemo()
                        "Forms" -> FormsDemo()
                        "Pickers" -> PickersDemo()
                        "Panels" -> PanelsDemo()
                        "Colors" -> ColorsDemo()
                        "Boxes" -> BoxesDemo()
                        "Animation" -> AnimationDemo()
                        "Grid" -> GridDemo()
                        "Images" -> ImagesDemo()
                        "Layout" -> LayoutDemo()
                        "Text" -> TextDemo()
                        "Scroll" -> ScrollDemo()
                        "Sortable" -> SortableDemo()
                        "Items" -> ItemsDemo()
                        "Entities" -> EntitiesDemo()
                        "State" -> StateDemo()
                    }
                }
            }
        }
    }

    private val ButtonsDemo = component("ButtonsDemo") {
        var clicks by useState(0)
        h2 { +"Buttons" }
        p { +"Buttons are styled only via CSS (:hover, :active, :focus, :disabled)." }
        div(className = "row") {
            button(className = "primary", onClick = { clicks++ }) { +"Clicked $clicks×" }
            button(onClick = { clicks = 0 }) { +"Reset" }
            button(disabled = true) { +"Disabled" }
        }
        h3 { +"Event bubbling" }
        var log by useState(listOf<String>())
        div(className = "bubble-outer", onClick = { log = (log + "outer").takeLast(4) }) {
            +"outer "
            button(onClick = { log = (log + "inner").takeLast(4) }) { +"inner (bubbles)" }
            button(onClick = { e -> e.stopPropagation(); log = (log + "stopped").takeLast(4) }) { +"stopPropagation" }
        }
        p(className = "muted") { +"Log: ${log.joinToString(" → ")}" }
        CustomCursorDemo()
    }

    /** `cursor: none` hides the system cursor; the box draws its own crosshair at the mouse instead. */
    private val CustomCursorDemo = component("CustomCursorDemo") {
        val box = useRef<Element?>(null)
        var at by useState<Pair<Float, Float>?>(null)
        h3 { +"cursor: none" }
        div(
            className = "cursor-area", ref = box,
            onMouseMove = { e -> box.current?.getBoundingClientRect()?.let { r -> at = (e.clientX - r.x) to (e.clientY - r.y) } },
            onMouseLeave = { at = null },
        ) {
            span(className = "muted") { +"Move the mouse here" }
            at?.let { (x, y) -> div(className = "crosshair", style = "left: ${x}px; top: ${y}px") }
        }
    }

    private val FormsDemo = component("FormsDemo") {
        var name by useState("")
        var password by useState("")
        var agree by useState(false)
        var mode by useState("normal")
        var dialog by useState(false)
        var sounds by useState(true)
        var compact by useState(false)
        var volume by useState(70f)
        var range by useState(12)
        var scale by useState(1.25f)
        h2 { +"Forms" }
        label(className = "form-row") {
            span(className = "form-label") { +"Name" }
            input(value = name, onChange = { name = it.value }, placeholder = "Your IGN", maxLength = 16)
        }
        label(className = "form-row") {
            span(className = "form-label") { +"Password" }
            input(type = "password", value = password, onChange = { password = it.value })
        }
        div(className = "form-row") {
            span(className = "form-label") { +"Mode" }
            select(value = mode, onChange = { mode = it.value }) {
                option("normal", "Normal")
                option("hard", "Hard")
                option("expert", "Expert (locked)", disabled = true)
            }
        }
        div(className = "form-row") { checkbox(checked = agree, onChange = { agree = it.checked }, label = "Show me in the party finder") }
        div(className = "row") {
            switch(checked = sounds, onChange = { sounds = it.checked }, label = "Sounds")
            switch(checked = compact, onChange = { compact = it.checked }, label = "Compact mode")
            switch(checked = true, label = "Locked", disabled = true)
        }
        div(className = "form-row") {
            span(className = "form-label") { +"Volume" }
            slider(value = volume, onChange = { volume = it }, step = 5f, showValue = true, format = { "${it.toInt()}%" }, disabled = !sounds)
        }
        div(className = "form-row") {
            span(className = "form-label") { +"Range" }
            slider(value = range, onChange = { range = it }, min = 1, max = 32, showValue = true, format = { "$it chunks" })
        }
        div(className = "form-row") {
            span(className = "form-label") { +"Scale" }
            slider(value = scale, onChange = { scale = it }, min = 0.5f, max = 2f, step = 0.05f, showValue = true, style = "width: 140px")
        }
        div(className = "row") {
            tooltip("Opens a modal dialog") { button(className = "primary", onClick = { dialog = true }) { +"Open dialog" } }
            button(title = "This is a native title tooltip") { +"Hover me" }
        }
        p(className = "muted") { +"name=$name · password=${"*".repeat(password.length)} · mode=$mode · agree=$agree · sounds=$sounds · volume=$volume" }
        modal(open = dialog, onClose = { dialog = false }) {
            h3 { +"Hello ${name.ifEmpty { "there" }}!" }
            p { +"Press Escape or click outside to close." }
            div(className = "row", style = "justify-content: flex-end") {
                button(onClick = { dialog = false }) { +"Cancel" }
                button(className = "primary", onClick = { dialog = false }) { +"OK" }
            }
        }
    }

    private val PickersDemo = component("PickersDemo") {
        var size by useState("5")
        var tier by useState("t5")
        var floor by useState("m7")
        var fishing by useState(listOf("trophy", "lava"))
        var cats by useState(listOf<String>())
        var item by useState<String?>(null)
        var kills by useState(5000 to 20000)
        var mp by useState(1200)
        var cata by useState<Int?>(null)
        var slots by useState(3)
        var price by useState(1.5)
        h2 { +"Pickers" }
        div(className = "form-row") {
            span(className = "form-label") { +"Size" }
            radioGroup(value = size, onChange = { size = it }) {
                option("1", "Solo"); option("2", "Duo"); option("3", "Trio"); option("5", "Full")
            }
        }
        div(className = "form-row") {
            span(className = "form-label") { +"Kuudra" }
            segmented(value = tier, onChange = { tier = it }) {
                option("t1", "Basic"); option("t2", "Hot"); option("t3", "Burning"); option("t4", "Fiery"); option("t5", "Infernal")
            }
        }
        div(className = "form-row") {
            span(className = "form-label") { +"Floor" }
            segmented(value = floor, onChange = { floor = it }) {
                option("f7", "F7"); option("m5", "M5"); option("m6", "M6"); option("m7", "M7"); option("m8", "M8", disabled = true)
            }
        }
        div(className = "form-row") {
            span(className = "form-label") { +"Fishing" }
            chips(values = fishing, onChange = { fishing = it }) {
                option("trophy", "Trophy"); option("lava", "Lava"); option("water", "Water"); option("ink", "Ink"); option("event", "Events")
            }
        }
        div(className = "form-row") {
            span(className = "form-label") { +"Filter" }
            multiSelect(values = cats, onChange = { cats = it }, placeholder = "All categories", searchable = true) {
                option("dungeons", "Dungeons"); option("kuudra", "Kuudra"); option("fishing", "Fishing")
                option("diana", "Diana"); option("slayer", "Slayer"); option("mining", "Mining")
            }
        }
        div(className = "form-row") {
            span(className = "form-label") { +"Item" }
            select(value = item, onChange = { item = it.value }, placeholder = "Search an item…", searchable = true) {
                for ((id, name) in listOf(
                    "hyperion" to "§6Hyperion", "terminator" to "§6Terminator", "necron_blade" to "§5Necron's Blade",
                    "aote" to "§9Aspect of the End", "aotv" to "§5Aspect of the Void", "juju" to "§5Juju Shortbow",
                    "giants_sword" to "§6Giant's Sword", "valkyrie" to "§6Valkyrie", "scylla" to "§6Scylla", "astraea" to "§6Astraea",
                )) option(id, name)
            }
        }
        div(className = "form-row") {
            span(className = "form-label") { +"Kills" }
            rangeSlider(
                low = kills.first, high = kills.second, onChange = { lo, hi -> kills = lo to hi }, min = 0, max = 50000, step = 500,
                showValue = true, format = { "%,d".format(it) }, style = "width: 120px",
            )
        }
        div(className = "form-row") {
            span(className = "form-label") { +"MP" }
            numberInput(value = mp, onChange = { mp = it }, min = 0, max = 2000, step = 10)
            span(className = "form-label", style = "margin-left: 8px") { +"Slots" }
            numberInput(value = slots, onChange = { slots = it }, min = 1, max = 5)
            numberInput(value = price, onChange = { price = it }, min = 0.0, max = 10.0, step = 0.25)
            span(className = "form-label", style = "margin-left: 8px") { +"Cata" }
            numberInput(value = cata, onChange = { cata = it }, allowEmpty = true, min = 0, max = 50, placeholder = "any")
        }
        p(className = "muted") { +"size=$size · tier=$tier · fishing=$fishing · cats=$cats · item=$item · kills=$kills · mp=$mp · cata=$cata" }
    }

    private val PanelsDemo = component("PanelsDemo") {
        var category by useState("dungeons")
        var sub by useState("all")
        var note by useState("")
        var openParty by useState<String?>("1")
        val toast = useToast()
        val clipboard = useClipboard()
        h2 { +"Panels" }
        tabs(value = category, onChange = { category = it }) {
            tab("dungeons", "Dungeons") {
                tabs(value = sub, onChange = { sub = it }, variant = "pills") {
                    tab("all", "All"); tab("f7", "F7"); tab("m6", "M6"); tab("m7", "M7")
                }
            }
            tab("kuudra", "Kuudra") { p(className = "muted") { +"Kuudra parties would be listed here." } }
            tab("fishing", "Fishing") { p(className = "muted") { +"Fishing parties would be listed here." } }
            tab("other", "Other", disabled = true)
        }
        h3 { +"Parties (right-click a name)" }
        for ((id, leader, info) in listOf(Triple("1", "Steve", "M7 · 4/5 · cata 45+"), Triple("2", "Alex", "Kuudra T5 · 2/4"))) {
            details(
                summary = {
                    contextMenu(className = "party-row", menu = {
                        header(leader)
                        item("Invite") { toast.success("Invited $leader") }
                        item("View profile", shortcut = "P") { toast.info("Opening the profile of $leader") }
                        separator()
                        item("Kick", danger = true) { toast.error("Kicked $leader", title = "Party") }
                    }) { playerHead(leader, className = "party-head"); b { +leader }; span(className = "muted") { +"  $info" } }
                },
                open = openParty == id,
                onToggle = { openParty = if (it) id else null },
                key = id,
            ) {
                p { +"Requirements: catacombs 45, secrets 10k, magical power 1,200." }
                p(className = "muted") { +"Note: bring a healer. Starting in 5 minutes." }
            }
        }
        h3 { +"Description" }
        textarea(value = note, onChange = { note = it.value }, placeholder = "Describe your party…", rows = 3, maxLength = 256, maxLines = 4)
        div(className = "row") {
            p(className = "muted") { +"${note.length}/256 · max 4 lines" }
            button(disabled = note.isEmpty(), onClick = { clipboard.set(note); toast.success("Note copied") }) { +"Copy note" }
        }
        div(className = "row") {
            button(className = "primary", onClick = { toast.success("Party created") }) { +"Create party" }
            button(onClick = { toast.warning("Join request declined", title = "Party finder") }) { +"Warning" }
            button(onClick = { toast.error("Server not reachable") }) { +"Error" }
        }
    }

    private val ColorsDemo = component("ColorsDemo") {
        var accent by useState(0xFF5B8DEF.toInt())
        var glow by useState(0x80FFD166.toInt())
        h2 { +"Color picker" }
        div(className = "row", style = "align-items: flex-start; gap: 10px") {
            colorPicker(value = accent, onChange = { accent = it })
            div(style = "display: flex; flex-direction: column; gap: 6px") {
                div(className = "form-row") {
                    span(className = "form-label") { +"Popover" }
                    colorInput(value = glow, onChange = { glow = it }, alpha = true)
                }
                div(
                    className = "color-preview",
                    style = "background: linear-gradient(135deg, ${Colors.toHex(accent)}, ${Colors.toHex(glow, true)})",
                ) { +"Preview" }
            }
        }
    }

    private val BoxesDemo = component("BoxesDemo") {
        h2 { +"Boxes" }
        p { +"border-radius, borders and opacity are drawn by GuiLib's own anti-aliased SDF shader." }
        div(className = "box-grid") {
            div(className = "demo-box", style = "border-radius: 4px") { +"4px" }
            div(className = "demo-box", style = "border-radius: 10px") { +"10px" }
            div(className = "demo-box", style = "border-radius: 10px 10px 0 0") { +"top only" }
            div(className = "demo-box", style = "border-radius: 50%; width: 40px") { +"50%" }
            div(className = "demo-box outline", style = "border-radius: 6px") { +"border" }
            div(className = "demo-box outline", style = "border-radius: 999px; border-width: 2px; width: 60px") { +"pill" }
            div(className = "demo-box", style = "border-radius: 6px; opacity: 0.4") { +"opacity" }
            div(className = "demo-box", style = "border-radius: 6px; background-color: rgba(91, 141, 239, 0.35); border: 1px solid #5b8def") { +"rgba" }
        }
        h3 { +"Gradients" }
        div(className = "box-grid") {
            div(className = "demo-box grad", style = "background: linear-gradient(to right, #5b8def, #b16cea)") { +"linear" }
            div(className = "demo-box grad", style = "background: linear-gradient(135deg, #ff7b72 0%, #ffd166 50%, #06d6a0 100%)") { +"3 stops" }
            div(className = "demo-box grad", style = "background: linear-gradient(to right, red 50%, blue 50%)") { +"hard" }
            div(className = "demo-box grad", style = "background: radial-gradient(circle, #ffd166, #e5484d 60%, #2b2d31)") { +"radial" }
            div(className = "demo-box grad", style = "background: linear-gradient(to bottom, transparent, black), linear-gradient(to right, white, red)") { +"layers" }
            div(className = "demo-box grad", style = "background: linear-gradient(to right, red, yellow, lime, cyan, blue, magenta, red); border: 1px solid #fff") { +"hue" }
            div(className = "demo-box grad", style = "background: conic-gradient(red, yellow, lime, cyan, blue, magenta, red); border-radius: 50%") { +"conic" }
            div(className = "demo-box grad conic-spin", title = "conic-gradient(from …) animated") { +"spin" }
            div(className = "demo-box grad", style = "background: conic-gradient(#ffd166 0 25%, #e5484d 0 60%, #5b8def 0); border-radius: 50%") { +"pie" }
            div(className = "demo-box grad", style = "background: repeating-linear-gradient(45deg, #e5484d 0 6px, #2b2d31 6px 12px)") { +"stripes" }
            div(className = "demo-box grad", style = "background: repeating-radial-gradient(circle, #5b8def 0 4px, #1e1f22 4px 8px)") { +"rings" }
            div(className = "demo-box grad", style = "background: repeating-conic-gradient(#ddd 0 25%, #888 0 50%); border: 1px solid #fff") { +"checker" }
        }
        h3 { +"Shadows" }
        div(className = "box-grid shadow-grid") {
            div(className = "demo-box shadow", style = "box-shadow: 0 2px 6px #000000aa") { +"soft" }
            div(className = "demo-box shadow", style = "box-shadow: 3px 3px #000") { +"hard" }
            div(className = "demo-box shadow", style = "box-shadow: 0 0 10px 2px #5b8defcc") { +"glow" }
            div(className = "demo-box shadow glass", style = "box-shadow: 0 4px 12px #000c") { +"glass" }
            div(className = "demo-box shadow", style = "box-shadow: inset 0 2px 5px #000c") { +"inset" }
            div(className = "demo-box shadow", style = "box-shadow: inset 0 0 0 2px #ffd166, 0 0 0 2px #e5484d") { +"rings" }
            div(className = "demo-box shadow lift") { +"hover me" }
        }
        h3 { +"::before / ::after" }
        div(className = "pseudo-demo") {
            div(className = "crumbs") {
                span(className = "crumb") { +"Dungeons" }
                span(className = "crumb") { +"Catacombs" }
                span(className = "crumb") { +"Floor 7" }
            }
            div(className = "row") {
                span(className = "field-label required") { +"Party name" }
                span(className = "bell") { +"Invites" }
                span(className = "more-link") { +"Hover me" }
                span(className = "tip-chip", title = "from attr(title)") { +"attr: " }
            }
            div(className = "quote") { +"Generated content, styled like real elements." }
        }
    }

    private val ImagesDemo = component("ImagesDemo") {
        h2 { +"Images" }
        p { +"img(src) takes a resource location. PNGs use Minecraft's texture manager, SVGs are rasterized per size, GIFs animate." }
        div(className = "row") {
            img("guilib:showcase/logo.svg", className = "svg-small")
            img("guilib:showcase/logo.svg", className = "svg-big")
            img("minecraft:textures/item/diamond.png", style = "width: 32px; height: 32px")
            img("minecraft:textures/block/oak_planks.png", style = "width: 48px; height: 24px; object-fit: cover")
            img("minecraft:textures/block/oak_planks.png", style = "width: 48px; height: 24px; object-fit: contain; background-color: #0006")
            img("guilib:showcase/spinner.gif")
            img("guilib:showcase/spinner.gif", style = "width: 16px; height: 16px")
        }
        p(className = "muted") { +"background-image: url(...)" }
        div(className = "bg-demo") { +"Text over a background image" }
    }

    private val AnimationDemo = component("AnimationDemo") {
        var open by useState(false)
        h2 { +"Animation" }
        p { +"CSS transitions and @keyframes animations." }
        div(className = "row") {
            div(className = "pulse-dot")
            span { +"animation: pulse 1.2s ease-in-out infinite alternate" }
        }
        div(className = "loader") { div(className = "loader-bar") }
        div(className = "row") {
            div(className = "hover-card") { +"Hover me (transition)" }
            button(className = "primary", onClick = { open = !open }) { +if (open) "Collapse" else "Expand" }
        }
        div(className = classNames("drawer", "open" to open)) { +"This panel animates its height and background." }
        div(className = "fade-in") { +"I faded in when this section opened." }

        var panel by useState(true)
        h3 { +"Transforms & presence" }
        div(className = "row") {
            for ((i, word) in listOf("Slide", "in", "one", "by", "one").withIndex()) {
                span(key = i, className = "chip slide-in", style = "animation-delay: ${i * 80}ms") { +word }
            }
        }
        div(className = "row") {
            button(className = "grow", onClick = { panel = !panel }) { +if (panel) "Hide panel" else "Show panel" }
        }
        presence(visible = panel, exitMs = 250) { leaving ->
            div(className = classNames("slide-panel", "leaving" to leaving)) { +"I slide in and out (transform + presence)." }
        }

        var rows by useState(listOf(1, 2, 3))
        var nextRow by useState(4)
        div(className = "row") {
            button(className = "grow add-row", onClick = { rows = listOf(nextRow) + rows; nextRow++ }) { +"Add row" }
            span(style = "color: var(--muted)") { +"presenceList: removed rows animate out" }
        }
        div(className = "exit-list") {
            presenceList(rows, key = { it }, exitMs = 220) { n, leaving ->
                div(className = classNames("exit-row", "leaving" to leaving)) {
                    span { +"Party #$n" }
                    button(className = "exit-x", onClick = { rows = rows - n }) { +"✕" }
                }
            }
        }

        var turns by useState(0)
        h3 { +"Rotate & skew" }
        div(className = "row rotate-row") {
            div(className = "spinner")
            div(className = "tilt-card") { +"Hover: tilt" }
            span(className = "chip skewed") { +"skewX(-12deg)" }
            button(className = "turn-btn", style = "transform: rotate(${turns * 90}deg)", onClick = { turns++ }) { +"Click: +90°" }
        }
    }

    private val GridDemo = component("GridDemo") {
        h2 { +"Grid" }
        p(className = "muted") { +"grid-template-areas" }
        div(className = "grid-areas") {
            div(className = "g-header") { +"header" }
            div(className = "g-side") { +"side" }
            div(className = "g-main") { +"main (1fr)" }
            div(className = "g-footer") { +"footer" }
        }
        p(className = "muted") { +"repeat(auto-fill, minmax(50px, 1fr))" }
        div(className = "grid-tiles") {
            for (i in 1..7) div(key = i, className = "tile") { +"#$i" }
            div(key = "wide", className = "tile wide") { +"span 2" }
        }
    }

    /** align-content moves the lines of a wrapping flex container that is taller than its lines. */
    private val AlignContentDemo = component("AlignContentDemo") {
        var align by useState("normal")
        p(className = "muted") { +"align-content (flex-wrap: wrap, height: 70px)" }
        segmented(value = align, onChange = { align = it }) {
            for (v in listOf("normal", "flex-start", "center", "flex-end", "space-between", "space-evenly")) option(v, v)
        }
        div(className = "flex-demo wrap-demo", style = "align-content: $align") {
            for (i in 1..9) div(key = i, className = "chip") { +"Item $i" }
        }
    }

    private val LayoutDemo = component("LayoutDemo") {
        h2 { +"Flexbox" }
        for (justify in listOf("flex-start", "center", "space-between", "space-evenly")) {
            p(className = "muted", key = "l-$justify") { +"justify-content: $justify" }
            div(key = justify, className = "flex-demo", style = "justify-content: $justify") {
                repeat(3) { div(className = "chip") { +"${it + 1}" } }
            }
        }
        p(className = "muted") { +"flex-grow 1 / 2 / 1 with gap" }
        div(className = "flex-demo") {
            div(className = "chip", style = "flex-grow: 1") { +"1" }
            div(className = "chip", style = "flex-grow: 2") { +"2" }
            div(className = "chip", style = "flex-grow: 1") { +"1" }
        }
        p(className = "muted") { +"align-items: center, column + absolute badge" }
        div(className = "flex-demo", style = "align-items: center; height: 40px; position: relative") {
            div(className = "chip", style = "height: 30px") { +"tall" }
            div(className = "chip") { +"short" }
            div(className = "badge") { +"abs" }
        }
        AlignContentDemo()
        h3 { +"@media" }
        p(className = "muted") { +"Resize the window or change the GUI scale: these react to the screen size and resolution (= GUI scale)." }
        div(className = "media-demo") {
            div(className = "media-chip size") {
                span(className = "bp-s") { +"narrow screen (< 400px)" }
                span(className = "bp-m") { +"medium screen (400-639px)" }
                span(className = "bp-l") { +"wide screen (>= 640px)" }
            }
            div(className = "media-chip scale") {
                span(className = "sc-lo") { +"GUI scale < 3" }
                span(className = "sc-hi") { +"GUI scale >= 3" }
            }
        }
    }

    private val TextDemo = component("TextDemo") {
        // A whole-window font switch without reopening: a class on the body also reaches portals (modals, tooltips).
        var mcFont by useState(false)
        useBodyClass("showcase-mc-font", mcFont)
        h2 { +"Text" }
        switch(checked = mcFont, onChange = { mcFont = it.checked }, label = "Minecraft font everywhere (useBodyClass)")
        p {
            +"Inline "
            span(style = "color: #ff7b72") { +"spans " }
            span(style = "font-weight: bold") { +"bold " }
            span(style = "font-style: italic; text-decoration: underline") { +"italic underlined " }
            +"and §6Minecraft §lcolor §r§bcodes§r work in any text."
        }
        p {
            +"Inline boxes: press "
            span(className = "kbd") { +"Ctrl" }
            +" + "
            span(className = "kbd") { +"K" }
            +", call "
            code(className = "inline-code") { +"useState()" }
            +", or "
            span(className = "highlight") { +"highlight a long phrase that wraps onto the next line" }
            +" with "
            span(className = "tag-pill") { +"pills" }
            +"."
        }
        p {
            +"Long text wraps at word boundaries inside its container. Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
        }
        div(className = "ellipsis") { +"This line is far too long for its box and ends with an ellipsis instead of overflowing" }
        p(style = "font-family: minecraft") { +"font-family: minecraft uses the vanilla font." }
        p(className = "supports-demo") { +"@supports (display: grid) and (not (display: contents)):" }
        p(className = "font-face-demo") { +"@font-face: \"Showcase Display\" is a font declared in showcase.css" }
        p(style = "font-size: 12px") { +"font-size: 12px" }
        p(style = "font-size: 6px") { +"font-size: 6px" }
        p(style = "text-align: center") { +"text-align: center" }
        p(style = "text-align: right") { +"text-align: right" }
        p(className = "spaced-title") { +"LETTER-SPACING: 3PX" }
        p(style = "letter-spacing: 0.1em") { +"letter-spacing: 0.1em (hover the title above, it animates)" }
        p(style = "font-family: minecraft; letter-spacing: 1px") { +"Minecraft font, letter-spacing: 1px" }
        p(className = "va-demo") {
            span(className = "va-tall") {}
            for (align in listOf("baseline", "middle", "top", "bottom", "text-top", "super", "sub", "4px")) {
                +"x"
                span(key = align, className = "va-box", style = "vertical-align: $align", title = "vertical-align: $align") {}
            }
            +" vertical-align"
        }
        p(className = "va-text") {
            +"H"; sub { +"2" }; +"O, E = mc"; sup { +"2" }; +", 10"; sup { +"-3" }; +", footnote"; sup { +"[1]" }
            +" · "; span(className = "va-chip", style = "vertical-align: 3px") { +"3px" }
            +" "; span(className = "va-chip", style = "vertical-align: -3px") { +"-3px" }
            +" text"
        }
        TranslationDemo()
    }

    private val TranslationDemo = component("TranslationDemo") {
        val t = useTranslation()
        h3 { +"Translations & Minecraft text" }
        p {
            +"useTranslation() (${t.language}): "
            span(className = "inline-code") { +t("menu.singleplayer") }
            +" "
            span(className = "inline-code") { +t("gui.done") }
            +" "
            span(className = "inline-code") { +t("options.chunks", 12) }
        }
        p {
            text(
                Component.literal("text(Component): ")
                    .append(Component.literal("RGB colors").withColor(0xFF8A3D))
                    .append(", ")
                    .append(Component.translatable("gui.yes").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                    .append(", ")
                    .append(Component.literal("strike").withStyle(ChatFormatting.STRIKETHROUGH))
                    .append(" and a ")
                    .append(
                        Component.literal("clickable link").withStyle { s ->
                            s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                                .withClickEvent(ClickEvent.CopyToClipboard("GuiLib"))
                                .withHoverEvent(HoverEvent.ShowText(Component.literal("Copies \"GuiLib\" to the clipboard")))
                        },
                    ),
            )
        }
        // displayName carries a show_item hover event (like item links in chat); stacks need a loaded world.
        val sword = try {
            ItemStack(Items.DIAMOND_SWORD)
        } catch (_: Exception) {
            null
        }
        if (sword != null) p(className = "item-link") { text(Component.literal("Item link (hover it): ").append(sword.displayName)) }
    }

    private val ScrollDemo = component("ScrollDemo") {
        var selected by useState(-1)
        h2 { +"Scroll container" }
        scroll(className = "list") {
            for (i in 1..50) {
                div(key = i, className = classNames("list-row", "selected" to (i == selected)), onClick = { selected = i }) {
                    span { +"Row #$i" }
                    span(className = "muted") { +if (i == selected) "selected" else "click me" }
                }
            }
        }
    }

    private val HorizontalScrollDemo = component("HorizontalScrollDemo") {
        var picked by useState<String?>(null)
        h3 { +"Horizontal scroll" }
        p(className = "muted") { +"The mouse wheel scrolls these rows sideways (Shift + wheel too); at the end it scrolls the page again." }
        scroll(className = "h-scroll") {
            for (stackItem in HOTBAR) {
                val name = stackItem.descriptionId.substringAfterLast('.')
                div(key = name, className = classNames("h-card", "selected" to (picked == name)), onClick = { picked = name }) {
                    div(className = "item-slot big") { item(ItemStack(stackItem)) }
                    span { +name.replace('_', ' ') }
                }
            }
        }
        p(className = "muted") { +if (picked == null) "Click a card." else "Picked: $picked" }
        scroll(className = "h-scroll timeline") {
            for (h in 0..23) span(key = h, className = "hour") { +"%02d:00".format(h) }
        }
    }

    private val SortableDemo = component("SortableDemo") {
        var tasks by useState(listOf("Kill Diana", "Dig burrows", "Sell loot", "Craft a hyperion", "Touch grass"))
        var tabs by useState(listOf("Overview", "Party", "Bazaar", "Auction", "Collections", "Skills", "Garden", "Rift", "Museum", "Settings"))
        h2 { +"Sortable lists" }
        p { +"Drag items to reorder them; Escape cancels a drag. Focus an item and press Alt + arrow keys to move it." }
        h3 { +"Vertical (drag anywhere)" }
        sortableList(tasks, key = { it }, onReorder = { tasks = it }, className = "sort-list") { task, dragging ->
            div(className = classNames("sort-row", "dragging" to dragging)) {
                span(className = "muted") { +"${tasks.indexOf(task) + 1}." }
                span(className = "task") { +task }
            }
        }
        h3 { +"With handle (removed rows animate out)" }
        sortableList(tasks, key = { it }, onReorder = { tasks = it }, handle = true, className = "sort-list", exitMs = 220) { task, _ ->
            div(className = "sort-row") {
                span(className = "guilib-drag-handle grip") { +"⠿" }
                span(className = "task") { +task }
                button(className = "small", onClick = { tasks = tasks - task }) { +"✕" }
            }
        }
        if (tasks.size < 5) button(onClick = { tasks = tasks + "Task ${tasks.size + 1}" }) { +"Add task" }
        h3 { +"Horizontal, inside a horizontal scroll" }
        scroll(className = "h-scroll") {
            sortableList(tabs, key = { it }, onReorder = { tabs = it }, horizontal = true, className = "tab-strip") { tab, dragging ->
                div(className = classNames("chip", "dragging" to dragging)) { +tab }
            }
        }
        HorizontalScrollDemo()
        KanbanDemo()
    }

    /** Lists sharing a group exchange items (anywhere in a column, even below a short or empty list); the long column scrolls while dragging near its edges. */
    private val KanbanDemo = component("KanbanDemo") {
        var todo by useState((1..12).map { "Burrow #$it" })
        var doing by useState(listOf("Inquisitor", "Minos Champion"))
        var done by useState(listOf<String>())
        h3 { +"Between lists (group = \"board\")" }
        div(className = "kanban") {
            for ((title, items, set) in listOf(
                Triple("To do", todo) { l: List<String> -> todo = l },
                Triple("Doing", doing) { l: List<String> -> doing = l },
                Triple("Done", done) { l: List<String> -> done = l },
            )) {
                div(className = "kanban-col", key = title) {
                    div(className = "kanban-title") { +"$title (${items.size})" }
                    scroll(className = "kanban-scroll") {
                        sortableList(items, key = { it }, onReorder = set, group = "board", className = "kanban-list") { item, _ ->
                            div(className = "kanban-card") { +item }
                        }
                    }
                }
            }
        }
    }

    private val HOTBAR = listOf(
        Items.DIAMOND_SWORD, Items.BOW, Items.GOLDEN_APPLE, Items.ENDER_PEARL, Items.COMPASS, Items.CLOCK, Items.MAP,
        Items.FISHING_ROD, Items.SHEARS, Items.TNT, Items.CAKE, Items.ELYTRA,
    )

    private val ItemsDemo = component("ItemsDemo") {
        h2 { +"Items" }
        // Item stacks need bound registries, which only exist once a world is loaded.
        val stacks = try {
            listOf(ItemStack(Items.DIAMOND), ItemStack(Items.GOLDEN_APPLE, 12), ItemStack(Items.NETHERITE_SWORD))
        } catch (_: Exception) {
            null
        }
        if (stacks == null) {
            p(className = "muted") { +"Join a world to see item icons." }
            return@component
        }
        div(className = "row") {
            for (stack in stacks) {
                div(className = "item-slot") { item(stack, tooltip = true) } // Minecraft's own item tooltip
            }
            div(className = "item-slot big") { item(ItemStack(Items.ENDER_EYE), style = "width: 32px; height: 32px") }
        }
    }

    private val EntitiesDemo = component("EntitiesDemo") {
        // Created once per mount; fake players need a loaded world.
        val players = useMemo { listOfNotNull(FakePlayer.ofLocalPlayer(), FakePlayer.of("Notch")) }
        h2 { +"Entities" }
        if (players.isEmpty()) {
            p(className = "muted") { +"Join a world to see player models." }
            return@component
        }
        p(className = "muted") { +"entity(FakePlayer.ofLocalPlayer(), followMouse = true), FakePlayer.of(\"Notch\")" }
        div(className = "row") {
            entity(players[0], className = "entity-box", followMouse = true, title = "Follows the mouse")
            players.getOrNull(1)?.let { entity(it, className = "entity-box", lookX = 30f, title = "Notch") }
            entity(players[0], className = "entity-box small", title = "Fits any box size")
        }
    }

    private val StateDemo = component("StateDemo") {
        var seconds by useState(0)
        useInterval(1000) { seconds++ }
        var todos by useState(listOf("Write CSS", "Build UI"))
        h2 { +"State & effects" }
        p { +"Mounted for ${seconds}s (useInterval)." }
        div(className = "row") {
            button(onClick = { todos = todos + "Todo #${todos.size + 1}" }) { +"Add" }
            button(onClick = { todos = todos.drop(1) }, disabled = todos.isEmpty()) { +"Remove first" }
        }
        for (t in todos) div(key = t, className = "todo") { +t }
    }

    fun open(section: String = SECTIONS.first()) =
        GuiLib.open(component("GuiLib Showcase") { App(section) }, stylesheets = listOf("guilib:showcase/showcase.css"), title = "GuiLib Showcase")
}
