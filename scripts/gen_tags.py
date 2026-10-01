"""Generates src/main/kotlin/net/sbo/guilib/core/dsl/Tags.kt.

Every tag function shares the same common props (key, id, className, style, title, ref, tabIndex, event handlers);
tag-specific props are listed in TAGS. Run: python scripts/gen_tags.py
"""
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "src/main/kotlin/net/sbo/guilib/core/dsl/Tags.kt"

# (kotlin name, event key, event type)
EVENTS = [
    ("onClick", "click", "MouseEvent"),
    ("onDoubleClick", "dblclick", "MouseEvent"),
    ("onContextMenu", "contextmenu", "MouseEvent"),
    ("onMouseDown", "mousedown", "MouseEvent"),
    ("onMouseUp", "mouseup", "MouseEvent"),
    ("onMouseMove", "mousemove", "MouseEvent"),
    ("onMouseEnter", "mouseenter", "MouseEvent"),
    ("onMouseLeave", "mouseleave", "MouseEvent"),
    ("onWheel", "wheel", "WheelEvent"),
    ("onKeyDown", "keydown", "KeyboardEvent"),
    ("onKeyUp", "keyup", "KeyboardEvent"),
    ("onFocus", "focus", "FocusEvent"),
    ("onBlur", "blur", "FocusEvent"),
    ("onScroll", "scroll", "ScrollEvent"),
]

# Extra props: (name, kotlin type, default, attribute key or None for events, event type if handler)
BUTTON = [("disabled", "Boolean", "false", "disabled")]
INPUT = [
    ("type", "String", '"text"', "type"),
    ("value", "String?", "null", "value"),
    ("placeholder", "String?", "null", "placeholder"),
    ("checked", "Boolean", "false", "checked"),
    ("disabled", "Boolean", "false", "disabled"),
    ("maxLength", "Int?", "null", "maxlength"),
    ("autoFocus", "Boolean", "false", "autofocus"),
    ("onInput", "InputEvent", None, "input"),
    ("onChange", "InputEvent", None, "change"),
]
SELECT = [
    ("value", "String?", "null", "value"),
    ("disabled", "Boolean", "false", "disabled"),
    ("onChange", "InputEvent", None, "change"),
]
OPTION = [("value", "String", None, "value"), ("disabled", "Boolean", "false", "disabled")]
IMG = [("src", "String", None, "src"), ("alt", "String?", "null", "alt")]
ITEM = [("stack", "Any", None, "stack"), ("decorations", "Boolean", "true", "decorations")]
ENTITY = [
    ("entity", "Any", None, "entity"),
    ("followMouse", "Boolean", "false", "followmouse"),
    ("lookX", "Float", "0f", "lookx"),
    ("lookY", "Float", "0f", "looky"),
    ("scale", "Float", "1f", "scale"),
]
PLAYER_HEAD = [("player", "Any", None, "player"), ("hat", "Boolean", "true", "hat")]

# tag -> (doc, extra props, has children)
TAGS = {
    "div": ("Generic block container (`display: block`).", [], True),
    "span": ("Inline text container (`display: inline`).", [], True),
    "p": ("Paragraph (`display: block`).", [], True),
    "h1": ("Heading.", [], True),
    "h2": ("Heading.", [], True),
    "h3": ("Heading.", [], True),
    "h4": ("Heading.", [], True),
    "section": ("Block container.", [], True),
    "header": ("Block container.", [], True),
    "footer": ("Block container.", [], True),
    "nav": ("Block container.", [], True),
    "main": ("Block container.", [], True),
    "aside": ("Block container.", [], True),
    "article": ("Block container.", [], True),
    "ul": ("List (`display: block`, no bullets).", [], True),
    "ol": ("List (`display: block`, no numbers).", [], True),
    "li": ("List item.", [], True),
    "label": ("Inline label; clicking it focuses/toggles the first input inside.", [], True),
    "a": ("Inline clickable text (no navigation; use onClick).", [], True),
    "strong": ("Bold inline text.", [], True),
    "b": ("Bold inline text.", [], True),
    "em": ("Italic inline text.", [], True),
    "i": ("Italic inline text.", [], True),
    "small": ("Smaller inline text.", [], True),
    "code": ("Inline code (Minecraft font).", [], True),
    "pre": ("Preformatted block (`white-space: pre`).", [], True),
    "scroll": ("Scroll container: a div with `overflow: auto` and a styled scrollbar. (Not an HTML tag.)", [], True),
    "button": ("Button (`display: inline-flex`, centered content). Disabled buttons receive no mouse events.", BUTTON, True),
    "br": ("Line break inside text.", [], False),
    "hr": ("Horizontal rule.", [], False),
    "img": ("Image: `src` is a resource location (`\"modid:textures/x.png\"`); PNG, SVG and (animated) GIF are supported.", IMG, False),
    "item": ("Minecraft item icon (16×16 by default). `stack` is a `net.minecraft.world.item.ItemStack`. (Not an HTML tag.)", ITEM, False),
    "entity": ("Minecraft entity model (48×72 by default), scaled to fit the box. `entity` is a `net.minecraft.world.entity.LivingEntity`, e.g. `FakePlayer.of(\"Notch\")`. `followMouse` turns it towards the cursor; otherwise it looks at its center offset by `lookX`/`lookY` px. `scale` multiplies the fitted size. (Not an HTML tag.)", ENTITY, False),
    "player-head": ("Minecraft player face (16×16 by default), like in the tab list. `player` is a name (`String`), a `UUID`, a `GameProfile`, a `ResolvableProfile` or an `AbstractClientPlayer`; the skin loads in the background (default skin until then) and works without a world. `hat = false` hides the hat layer. (Not an HTML tag.)", PLAYER_HEAD, False),
    "input": ("Form input. `type`: `text`, `password`, `number`, `checkbox`. Controlled like React: pass `value`/`checked` and update them in `onInput`/`onChange`.", INPUT, False),
}

HEADER = '''// GENERATED by scripts/gen_tags.py - do not edit by hand.
@file:Suppress("FunctionName", "unused")

package net.sbo.guilib.core.dsl

import net.sbo.guilib.core.dom.Element
import net.sbo.guilib.core.dom.Ref
import net.sbo.guilib.core.event.FocusEvent
import net.sbo.guilib.core.event.InputEvent
import net.sbo.guilib.core.event.KeyboardEvent
import net.sbo.guilib.core.event.MouseEvent
import net.sbo.guilib.core.event.ScrollEvent
import net.sbo.guilib.core.event.UIEvent
import net.sbo.guilib.core.event.WheelEvent

@Suppress("UNCHECKED_CAST")
private fun <E : UIEvent> MutableMap<String, (UIEvent) -> Unit>.on(key: String, handler: ((E) -> Unit)?) {
    if (handler != null) this[key] = handler as (UIEvent) -> Unit
}
'''


def gen_tag(tag, doc, extra, children):
    lines = [f"/** {doc} */", f"fun NodeBuilder.{fn_name(tag)}("]
    params = []
    # Required tag-specific props first so they can be passed positionally: img("mod:x.png").
    for name, typ, default, key in extra:
        if default is None and typ[0].isupper() and not typ.endswith("Event"):
            params.append(f"    {name}: {typ},")
    params += [
        "    className: String? = null,",
        "    id: String? = null,",
        "    style: String? = null,",
        "    key: Any? = null,",
        "    title: String? = null,",
        "    ref: Ref<Element?>? = null,",
        "    tabIndex: Int? = null,",
    ]
    for name, typ, default, key in extra:
        if typ.endswith("Event"):
            params.append(f"    {name}: (({typ}) -> Unit)? = null,")
        elif default is not None:
            params.append(f"    {name}: {typ} = {default},")
    for name, key, typ in EVENTS:
        params.append(f"    {name}: (({typ}) -> Unit)? = null,")
    if children:
        params.append("    children: (NodeBuilder.() -> Unit)? = null,")
    lines += params
    lines.append(") {")
    lines.append("    val attrs = HashMap<String, Any?>()")
    lines.append('    if (title != null) attrs["title"] = title')
    lines.append('    if (tabIndex != null) attrs["tabindex"] = tabIndex')
    for name, typ, default, key in extra:
        if typ.endswith("Event"):
            continue
        if typ == "Boolean":
            lines.append(f'    if ({name}) attrs["{key}"] = true')
        elif typ.endswith("?"):
            lines.append(f'    if ({name} != null) attrs["{key}"] = {name}')
        else:
            lines.append(f'    attrs["{key}"] = {name}')
    lines.append("    val handlers = HashMap<String, (UIEvent) -> Unit>()")
    for name, typ, default, key in extra:
        if typ.endswith("Event"):
            lines.append(f'    handlers.on("{key}", {name})')
    for name, key, typ in EVENTS:
        lines.append(f'    handlers.on("{key}", {name})')
    kids = "children" if children else "null"
    lines.append(f'    element("{tag}", key, id, className, style, ref, attrs, handlers, {kids})')
    lines.append("}")
    return "\n".join(lines)


def fn_name(tag):
    # Kotlin keywords / clashes; GuiLib tags with a hyphen become camelCase (`player-head` → `playerHead`).
    head, *rest = tag.split("-")
    return head + "".join(p.capitalize() for p in rest)


def main():
    parts = [HEADER]
    for tag, (doc, extra, children) in TAGS.items():
        parts.append(gen_tag(tag, doc, extra, children))
    OUT.write_text("\n\n".join(parts) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
