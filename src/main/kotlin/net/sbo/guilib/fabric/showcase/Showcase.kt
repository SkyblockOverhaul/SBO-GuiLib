package net.sbo.guilib.fabric.showcase

import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.button
import net.sbo.guilib.core.dsl.classNames
import net.sbo.guilib.core.dsl.div
import net.sbo.guilib.core.dsl.h2
import net.sbo.guilib.core.dsl.h3
import net.sbo.guilib.core.dsl.checkbox
import net.sbo.guilib.core.dsl.img
import net.sbo.guilib.core.dsl.input
import net.sbo.guilib.core.dsl.label
import net.sbo.guilib.core.dsl.modal
import net.sbo.guilib.core.dsl.select
import net.sbo.guilib.core.dsl.tooltip
import net.sbo.guilib.core.dsl.item
import net.sbo.guilib.core.dsl.nav
import net.sbo.guilib.core.dsl.p
import net.sbo.guilib.core.dsl.scroll
import net.sbo.guilib.core.dsl.span
import net.sbo.guilib.fabric.GuiLib

/** Demo UI showing every feature; open with `/guilib showcase`. */
object Showcase {
    val SECTIONS = listOf("Buttons", "Forms", "Boxes", "Layout", "Text", "Scroll", "Images", "Items", "State")

    private val App = component<String>("Showcase") { initialSection ->
        var section by useState(initialSection)
        div(className = "window") {
            div(className = "titlebar") {
                span(className = "title") { +"GuiLib Showcase" }
                button(className = "close", title = "Close", onClick = { GuiLib.close() }) { +"✕" }
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
                        "Boxes" -> BoxesDemo()
                        "Images" -> ImagesDemo()
                        "Layout" -> LayoutDemo()
                        "Text" -> TextDemo()
                        "Scroll" -> ScrollDemo()
                        "Items" -> ItemsDemo()
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
    }

    private val FormsDemo = component("FormsDemo") {
        var name by useState("")
        var password by useState("")
        var agree by useState(false)
        var mode by useState("normal")
        var dialog by useState(false)
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
            tooltip("Opens a modal dialog") { button(className = "primary", onClick = { dialog = true }) { +"Open dialog" } }
            button(title = "This is a native title tooltip") { +"Hover me" }
        }
        p(className = "muted") { +"name=$name · password=${"*".repeat(password.length)} · mode=$mode · agree=$agree" }
        modal(open = dialog, onClose = { dialog = false }) {
            h3 { +"Hello ${name.ifEmpty { "there" }}!" }
            p { +"Press Escape or click outside to close." }
            div(className = "row", style = "justify-content: flex-end") {
                button(onClick = { dialog = false }) { +"Cancel" }
                button(className = "primary", onClick = { dialog = false }) { +"OK" }
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
        }
    }

    private val ImagesDemo = component("ImagesDemo") {
        h2 { +"Images" }
        p { +"img(src) takes a resource location. PNGs use Minecraft's texture manager, SVGs are rasterized per size." }
        div(className = "row") {
            img("guilib:showcase/logo.svg", className = "svg-small")
            img("guilib:showcase/logo.svg", className = "svg-big")
            img("minecraft:textures/item/diamond.png", style = "width: 32px; height: 32px")
            img("minecraft:textures/block/oak_planks.png", style = "width: 48px; height: 24px; object-fit: cover")
            img("minecraft:textures/block/oak_planks.png", style = "width: 48px; height: 24px; object-fit: contain; background-color: #0006")
        }
        p(className = "muted") { +"background-image: url(...)" }
        div(className = "bg-demo") { +"Text over a background image" }
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
    }

    private val TextDemo = component("TextDemo") {
        h2 { +"Text" }
        p {
            +"Inline "
            span(style = "color: #ff7b72") { +"spans " }
            span(style = "font-weight: bold") { +"bold " }
            span(style = "font-style: italic; text-decoration: underline") { +"italic underlined " }
            +"and §6Minecraft §lcolor §r§bcodes§r work in any text."
        }
        p {
            +"Long text wraps at word boundaries inside its container. Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua."
        }
        div(className = "ellipsis") { +"This line is far too long for its box and ends with an ellipsis instead of overflowing" }
        p(style = "font-family: minecraft") { +"font-family: minecraft uses the vanilla font." }
        p(style = "font-size: 12px") { +"font-size: 12px" }
        p(style = "font-size: 6px") { +"font-size: 6px" }
        p(style = "text-align: center") { +"text-align: center" }
        p(style = "text-align: right") { +"text-align: right" }
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
                div(className = "item-slot", title = stack.hoverName.string) { item(stack) }
            }
            div(className = "item-slot big") { item(ItemStack(Items.ENDER_EYE), style = "width: 32px; height: 32px") }
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
