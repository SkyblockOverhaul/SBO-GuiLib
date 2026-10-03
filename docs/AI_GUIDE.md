# GuiLib — Reference for AI models (and humans in a hurry)

GuiLib builds Minecraft (Fabric, MC 26.1.x / 26.2 / 26.3) screens the way you build web UIs:
**React-style function components in a Kotlin DSL + real CSS files**. If you know React and CSS, write what you
would write there; this page lists the exact API and **every place where GuiLib differs from the web**.

## 1. Minimal example

```kotlin
import net.sbo.guilib.core.dom.component
import net.sbo.guilib.core.dsl.*          // tag functions: div, span, button, input, …
import net.sbo.guilib.fabric.GuiLib

data class Party(val id: String, val leader: String, val size: Int)

val PartyRow = component<Party>("PartyRow") { party ->
    var expanded by useState(false)
    div(className = classNames("row", "expanded" to expanded), onClick = { expanded = !expanded }) {
        span(className = "leader") { +party.leader }
        span(className = "size") { +"${party.size}/5" }
        if (expanded) button(className = "join", disabled = party.size >= 5, onClick = { e -> e.stopPropagation(); join(party.id) }) { +"Join" }
    }
}

val App = component("App") {
    val (parties, setParties) = useState(emptyList<Party>())
    useEffect { loadParties { result -> setParties(result) } }        // runs once after mount; setters are thread-safe
    div(className = "panel") {
        h2 { +"Parties" }
        scroll(className = "list") {
            for (p in parties) PartyRow(p, key = p.id)                 // keys for lists, like React
        }
    }
}

GuiLib.open(App, stylesheets = listOf("mymod:ui/parties.css"))       // assets/mymod/ui/parties.css
```

```css
/* assets/mymod/ui/parties.css */
:root { --accent: #5b8def; }
body { display: flex; align-items: center; justify-content: center; }  /* body = the whole screen */
.panel { width: 300px; max-height: 80vh; padding: 8px; background-color: #1e1f22; border-radius: 6px; display: flex; flex-direction: column; }
.list { flex-grow: 1; min-height: 0; }
.row { display: flex; justify-content: space-between; padding: 3px 6px; border-radius: 3px; cursor: pointer; }
.row:hover { background-color: #2b2d31; }
.join { background-color: var(--accent); }
```

## 2. Components & hooks

| API | Notes |
|---|---|
| `component<P>("Name") { props -> … }` | Function component with props (use a `data class` so unchanged props skip re-rendering). |
| `component("Name") { … }` | Component without props. |
| `Name(props, key = …)` / `Name(key = …)` | Render a component inside a DSL block. |
| `var x by useState(init)` · `val (x, setX) = useState(init)` · `s.value` · `s.update { it + 1 }` | Setting a different value re-renders. Setters may be called from any thread. |
| `useStateLazy { expensive() }` | Lazy initial value. |
| `useEffect { … }` | **Runs once after mount** (= React `useEffect(fn, [])`). |
| `useEffect(a, b) { … }` | Runs after mount and whenever `a`/`b` change (`==`). There is **no** "every render" form. |
| inside `useEffect`: `onCleanup { }`, `setInterval(ms) { }`, `setTimeout(ms) { }` | Timers are cancelled automatically on cleanup/unmount. |
| `useInterval(ms) { … }` | Interval that always calls the latest lambda. |
| `val r = useAsync(keys) { slowCall() }` · `useFuture(keys) { completableFuture }` · `usePromise(keys) { resolve, reject -> api.load(resolve, reject) }` | Async data: runs after mount and when a key changes (`useAsync` on a GuiLib background thread), re-renders with the result. `r.loading`, `r.value` (last successful result, kept while reloading), `r.error`, `r.isSuccess`, `r.reload()`. Outdated results (keys changed, unmounted) are dropped. Never touch the UI inside the loader. |
| `useMemo(deps…) { … }`, `useRef(init)`, `useElementRef()` | `ref = myRef` on any tag sets `myRef.current` to the `Element`. |
| `createContext(default)`, `Ctx.Provider(value) { … }`, `useContext(Ctx)` | Like React context. |
| `useDocument()` | The `Document` (viewport size, `focusedElement`, `addEventListener`). |
| `useDocumentEvent("keydown") { e -> … }` | Global listener while mounted (runs before element handlers). |
| `useScreenScale(2.5f)` | The screen's own GUI scale while mounted, independent of Minecraft's (`null` = Minecraft's; fractions work). Everything incl. portals is laid out and drawn with it: viewport = window / scale, `vw`/`vh` and `@media (resolution)` follow, text and SVGs are re-rasterized sharp. Changes apply live. Also `GuiLib.open(App, scale = 2.5f)`, or `document.scale` from outside. |
| `useBodyClass("font-mc", enabled)` | Puts a class on the body while mounted and `enabled` (theme / font switch without reopening; reaches portals too: modals, tooltips, toasts live under the body). |
| `useBodyStyle("--accent", value)` | Sets an inline property or variable on the body while mounted (`null` = none). |

Elements: `el.classList.add/remove/toggle(name, force?)/replace(old, new)`, `el.inlineStyle = "…"`, `el.setStyleProperty("font-family", "minecraft")`,
`removeStyleProperty`, `getStyleProperty` (like the DOM). Use them on `useDocument().body` (or `GuiLib.currentDocument()?.body` from
outside the UI, on the render thread); on an element you render with `className`/`style` the next render sets them back, like React.
| `useForceUpdate()` | Escape hatch. |

Rules of hooks apply: call hooks unconditionally at the top of the component (not inside `div { }` blocks, loops or ifs).
Hook misuse is detected and logged.

**Helper functions:** every `{ }` block is a `NodeBuilder` and all tags are extensions on it, so a reusable piece of UI is
`fun NodeBuilder.renderGraph(values: List<Int>) { div(className = "graph") { … } }`, called inside any block (`div { renderGraph(data) }`).
A plain `fun renderGraph() = div { }` does not compile. Helpers can't use hooks; use a `component` when the part needs its own state.

## 3. Elements (tag functions)

Every tag accepts: `className`, `id`, `style` (a **CSS string**, e.g. `style = "width: 20px; color: red"`), `key`,
`title` (native tooltip), `ref`, `tabIndex`, and event handlers:
`onClick, onDoubleClick, onContextMenu, onMouseDown, onMouseUp, onMouseMove, onMouseEnter, onMouseLeave` (MouseEvent),
`onWheel` (WheelEvent), `onKeyDown, onKeyUp` (KeyboardEvent), `onFocus, onBlur` (FocusEvent), `onScroll` (ScrollEvent).
Children go in the trailing lambda; text with `+"text"` or `text(value)`.
Minecraft text: `text(component)` renders a `net.minecraft.network.chat.Component` (colors incl. RGB, bold/italic/
underline/strikethrough, `show_text` hover → tooltip, `show_item` hover → Minecraft's item tooltip (e.g. `text(stack.displayName)`), click events like in chat; `span.guilib-text`, clickable parts
`.guilib-text-link`). Translations: `val t = useTranslation()` (`net.sbo.guilib.fabric`), then `+t("mymod.key", arg)`;
the component re-renders when the game language or resource packs change. `t.language`, `t.has(key)`.

| Tag | Default display | Extra props / notes |
|---|---|---|
| `div, section, header, footer, nav, main, aside, article, p, h1–h4, ul, ol, li, pre, hr` | block | `p`, `h*` have small bottom margins; `pre` is `white-space: pre` + Minecraft font. |
| `span, a, strong, b, em, i, small, sub, sup, code, label` | inline | `sub`/`sup`: subscript/superscript (`H` `sub { +"2" }` `O`). `label` forwards clicks to the first input/select/button inside. |
| `button` | inline-flex (centered) | `disabled`. Disabled elements get no mouse events. Enter/Space activate a focused button. |
| `scroll` *(GuiLib tag)* | block + `overflow: auto` | Scroll container with a thin scrollbar. Any element with `overflow: auto/scroll` scrolls too. |
| `img("modid:path.png")` | inline (replaced) | `src` = resource location (PNG, SVG or GIF; GIFs animate), `alt`. Natural size = image size. `object-fit` supported. `currentColor` in an SVG = the element's CSS `color` (tint icons from CSS). |
| `item(stack)` *(GuiLib tag)* | inline (replaced, 16×16) | `stack: ItemStack`, `decorations = true` (count/durability), `tooltip = false` (`true` = Minecraft's item tooltip while the icon is hovered). Needs a loaded world. |
| `entity(entity)` *(GuiLib tag)* | inline (replaced, 48×72) | `entity: LivingEntity`, scaled to fit the box like the inventory player model. `followMouse = false`, `lookX`/`lookY` (look offset in px when not following), `scale = 1f`. Player models: `FakePlayer.ofLocalPlayer()`, `FakePlayer.of("name")`, `.of(uuid)`, `.of(gameProfile)`, `.of(player)` (`net.sbo.guilib.fabric.entity`; `null` without a world – create once with `useMemo`). Works in scroll containers, `overflow: hidden` clips, `scale()` and own screen scales; inside `rotate()`/`skew()` the model stays upright in the rotated box's bounding box. |
| `playerHead(player)` *(GuiLib tag)* | inline (replaced, 16×16) | Player face like in the tab list, scaled to the box (`style = "width: 8px; height: 8px"`). `player`: name `String`, `UUID`, `GameProfile`, `ResolvableProfile` or `AbstractClientPlayer`. Skin loads in the background (default skin until then); no world needed. `hat = false` hides the hat layer. CSS tag selector: `player-head`. |
| `input(...)` | inline-block | `type = "text" | "password" | "number" | "checkbox"`, `value`, `placeholder`, `checked`, `disabled`, `maxLength`, `autoFocus`, `onInput`, `onChange` (InputEvent: `.value`, `.checked`). `text-align: center | right` aligns the text, placeholder, caret and selection. |
| `br` | – | Line break inside text. |
| `select(value, onChange) { option("v") { +"Label" }; option("v2", "Label 2", disabled = true, title = "Hover text") }` | inline-flex | Component; menu opens in a portal (never clipped). `placeholder`, `disabled`, `searchable = true` (search field filtering the options – for long lists). `title` on an option = tooltip for that entry; `className` / `style` on an option go on its menu entry and on its label in the box while chosen (`span.guilib-select-chosen`, one per chosen entry in `multiSelect`), e.g. `option("hyp", "Hyperion", className = "legendary")` with `.legendary { color: #ffaa00 }` (labels also take `§` codes). All of this works for `multiSelect`, `radioGroup`, `segmented`, `chips` too. |
| `checkbox(checked, onChange, label = "…")` | inline-flex | Convenience: `label` + `input(type = "checkbox")`. |
| `switch(checked, onChange, label = "…", disabled = false)` | inline-flex | Toggle switch (sliding pill), same `onChange` as `checkbox` (`it.checked`). Focusable; click, Space or Enter toggles. Classes: `.guilib-switch` (`.checked`), `.guilib-switch-track`, `.guilib-switch-thumb`, `.guilib-switch-label`. |
| `slider(value, onChange, min = 0f, max = 100f, step = 1f, onChangeEnd, showValue = false, format, disabled)` | inline-block (100px wide) | Range slider like `<input type="range">`. `Float` and `Int` overloads (pick by the type of `value`). `onChange` fires for every new value while dragging, `onChangeEnd` once on release / after a key press (save settings there). Snaps to `step` (`0f` = continuous). Focusable: arrows ±1 step, PageUp/PageDown ±10 %, Home/End. `showValue = true` adds a label, `format = { "${it.toInt()}%" }` customizes it. Set the width with `style`/CSS on `.guilib-slider`. Classes: `.guilib-slider` (`.dragging`), `-track`, `-rail`, `-fill`, `-thumb`, `.guilib-slider-field`, `.guilib-slider-value`. |
| `textarea(value, onChange, placeholder, rows = 3, maxLength, maxLines)` | block | Multi-line text field: word wrap, Enter = line break, arrows/Home/End/PageUp/PageDown, mouse selection, Ctrl+A/C/X/V, scrolls vertically. Without a CSS `height` it is exactly `rows` lines tall plus its padding and border (like a browser); longer text scrolls inside it. `height` / `min-height` / `max-height` in CSS override it. `text-align: center | right` aligns every line (caret, selection and clicks follow). `maxLines` caps the line breaks (Enter does nothing at the limit, extra pasted breaks become spaces; wrapped lines don't count). `onChange` like `input` (`it.value`). |
| `numberInput(value, onChange, min, max, step = 1)` | inline-flex | Number field with − / + buttons (hold to repeat). `Int` and `Double` overloads (decimals follow `step`). Values stay in `min..max`: in-range values are reported while typing, others are clamped on blur/Enter (a cleared field restores the value). ArrowUp/Down and the mouse wheel (while hovered; `wheel = false` turns it off) step; the −/+ buttons, arrows and wheel move Shift × 10, Ctrl (Cmd) × 100, Ctrl + Shift × 1000 steps (holding a button repeats with that multiplier); own factors: `stepMultiplier = { m -> if (m.alt) 5 else 1 }` (`Modifiers` → steps). **Empty field:** `numberInput(value = level, onChange = { level = it }, allowEmpty = true, placeholder = "any")` with `level: Int?`/`Double?` – `null` shows the placeholder, clearing reports `null`, + on an empty field starts at `step` (at least `min`), − on an empty field does nothing and − at `min` empties the field. Classes: `.guilib-number`, `-input`, `-dec`, `-inc`. |
| `rangeSlider(low, high, onChange = { lo, hi -> }, min, max, step, onChangeEnd, showValue, format)` | inline-block | Two-thumb range slider (Float/Int). A press moves the nearer thumb; thumbs can't cross; each thumb is focusable (keys like `slider`). `showValue` shows "low – high". Classes: like `slider` plus `.guilib-range-slider`, `.guilib-slider-thumb.low/.high`. |
| `radioGroup(value, onChange = { v -> }, vertical = false) { option("2", "Duo") }` | flex | Radio buttons; `onChange` gets the value string. Arrow keys move the selection; the selected radio is the Tab stop. Classes: `.guilib-radio-group`, `.guilib-radio` (`.checked`), `.guilib-radio-dot`. |
| `segmented(value, onChange) { option("t5", "Infernal") }` | inline-flex | Segmented buttons with a highlight that slides to the selected one (same API as `radioGroup`). Classes: `.guilib-segmented`, `.guilib-segment` (`.selected`), `.guilib-segment-indicator`. |
| `chips(values, onChange = { list -> }) { option(…) }` | flex (wraps) | Toggleable chips for picking several filters; `onChange` gets the selected values in option order. Classes: `.guilib-chips`, `.guilib-chip` (`.selected`). |
| `multiSelect(values, onChange = { list -> }, placeholder, searchable = false) { option(…) }` | inline-flex | Dropdown with check marks; stays open while toggling; the box shows the chosen labels. Classes: like `select` plus `select.multiple`, `.guilib-option-check`. |
| `tabs(value, onChange, variant = "underline" \| "pills") { tab("a", "A") { content }; tab("b", "B", disabled = true) }` | flex column | Tab bar whose indicator slides to the active tab. With content lambdas the active tab's content renders below (`.guilib-tab-panel`); without, render it yourself from `value`. Left/Right switch tabs. Classes: `.guilib-tabs` (`.underline`, `.pills`), `.guilib-tab-list`, `.guilib-tab` (`.active`), `.guilib-tab-indicator`. |
| `details("Summary", open = null, onToggle, defaultOpen = false) { … }` / `details(summary = { … }) { … }` | block | Accordion item like `<details>`: summary row with a chevron, body height animates (also when its content changes). Uncontrolled by default; pass `open` + `onToggle` to control it (e.g. only one open). Classes: `.guilib-details` (`.open`), `-summary`, `-chevron`, `-title`, `-content`. |
| `collapse(open, durationMs = 200, keepMounted = false) { … }` | block | The animated-height building block of `details`. Closed content unmounts after the animation unless `keepMounted`. |
| `contextMenu(menu = { header("Steve"); item("Invite") { … }; separator(); item("Kick", danger = true) { … } }) { children }` | block (`.guilib-context-anchor`) | Right-click menu opened at the mouse, kept inside the screen. `item(label, disabled, danger, shortcut) { onClick }`. Arrow keys + Enter, Escape / outside click / wheel / a screen resize close it. Classes: `.guilib-menu`, `.guilib-menu-item` (`.highlighted`, `.danger`, `.disabled`), `.guilib-menu-header`, `.guilib-menu-separator`. |
| `val clipboard = useClipboard()` → `clipboard.set(text)`, `clipboard.get()` | – | System clipboard (e.g. a "Copy note" button: `button(onClick = { clipboard.set(note) }) { +"Copy" }`). `set` is safe from any thread, call `get` on the UI thread. Same object as `useDocument().clipboard`. |
| `val toast = useToast()` → `toast.success("Party created")`, `.info/.warning/.error(msg, title)`, `.show(msg, kind, title, durationMs)` | – | Toasts stacked bottom right, disappear after 3.5 s (`durationMs <= 0` = until clicked), click dismisses, max 5. Safe from any thread. Returns a `Toast` with `dismiss()`. Classes: `.guilib-toasts`, `.guilib-toast` (`.success`…, `.leaving`), `.guilib-toast-accent`, `-title`, `-message`, `-close`. |
| `tooltip("text", placement = "top|bottom|left|right") { anchor }` / `tooltip(content = { … }) { anchor }` | – | Hover tooltip (300 ms delay). For simple cases use the `title` prop. |
| `modal(open, onClose) { … }` | – | Dialog in a portal with backdrop; Escape and backdrop click call `onClose`. |
| `presence(visible, exitMs) { leaving -> … }` | – | Exit animations (like Framer Motion's `AnimatePresence`): when `visible` turns false, the children render with `leaving = true` and are removed after `exitMs`. Use `leaving` to switch to an exit `animation`/`transition`. |
| `presenceList(items, key, exitMs) { item, leaving -> … }` | – | `presence` per list item: items removed from `items` stay at their old position with `leaving = true` for `exitMs`, then disappear; new items mount normally (their `animation` plays). Rendered straight into the parent (no wrapper). An item that comes back during its exit is normal again. Animate `height`/`margin` to 0 in the exit keyframes (with `overflow: hidden` and a fixed `height`) so the rows below slide up. For `sortableList` use its `exitMs`. |
| `sortableList(items, key = { it.id }, onReorder = { items = it }, horizontal = false, handle = false) { item, dragging -> … }` | flex column (row if `horizontal`) | Drag-to-reorder list. Items move with `transform` while dragging; dropping calls `onReorder` with the reordered list. A drag starts after 3 px, so clicks inside items still work, and the release after a drag clicks nothing. `handle = true`: only elements with class `guilib-drag-handle` start a drag (use it when items contain inputs). Escape cancels. Dragging near the edge of a scroll container scrolls it. Items are focusable: Alt + arrow keys (Alt + Home/End) move the focused item. `group = "board"`: lists with the same group exchange items (kanban) — outside its list the item follows the mouse as a ghost in a portal, the list under the mouse opens a gap, and the drop calls the source's `onReorder` (without the item) and the target's (with it); an item can also be dropped anywhere in the element around a list that holds no other list of the group (e.g. its kanban column, below a short or empty list), so empty lists need no `min-height` when they sit in such a wrapper; items may have their own margins (they travel with the item). `exitMs = 200`: removed items stay at their old position for that long with the item class `.leaving` (not clickable, no drag starts meanwhile) — give `.my-list .guilib-sortable-item.leaving` an exit animation (`forwards`; collapse with `overflow: hidden` + `max-height` to 0); items dragged into another list of the group move without an exit. The ghost repeats `className`/`itemClassName`, so style dragged items through those. Classes: `.guilib-sortable` (`.horizontal`, `.handle`, `.sorting`, `.receiving`), `.guilib-sortable-item` (`.dragging`, `.away`, `.guilib-sortable-ghost`, `.leaving`). |
| `portal { … }` | – | Like `createPortal(children, document.body)`: renders above everything. Position content with `position: fixed`. |
| `fragment(key) { … }` | – | `<>…</>` with a key. |
| `colorPicker(value, onChange, alpha = false)` | – | Inline picker (saturation/value area, hue slider, optional alpha slider, hex input). Colors are ARGB `Int`s; `onChange: (Int) -> Unit`. |
| `colorInput(value, onChange, alpha = false)` | inline-flex | Swatch button that opens a `colorPicker` popover (portal). |
| `classNames("a", "b" to cond, null)` | – | Like `clsx`. |

**Forms are controlled like React:** pass `value`/`checked` and update your state in `onChange`
(`input(value = name, onChange = { name = it.value })`). Without `value` the input manages its own text.
`onChange` fires on **every edit** (React semantics, not DOM `change`); `onInput` is identical.

Minecraft `§` color/format codes work in every text (`+"§6Gold §lbold"`). Text inputs are the exception: what the user types is shown
literally (`§` included); if you display that value elsewhere, the codes apply there.
Inputs support mouse/Shift+arrow selection, double-click word selection and Ctrl/Cmd+A/C/X/V with the system clipboard.

## 4. Events

`e.target`, `e.currentTarget`, `e.stopPropagation()`, `e.preventDefault()` work like the DOM; events bubble from the
deepest element to `body`. `mouseenter/mouseleave`, `focus/blur`, `scroll` don't bubble.
- `MouseEvent`: `clientX/clientY` (GUI px), `offsetX/offsetY`, `button` (0 left, 1 middle, 2 right), `shiftKey/ctrlKey/altKey`.
- `WheelEvent`: `deltaX/deltaY` (px; positive = down). Default action scrolls the nearest scroll container. Shift + wheel
  scrolls sideways. Unlike the web, a container that can only scroll horizontally (`overflow-x: auto; overflow-y: hidden`)
  also scrolls sideways with the plain wheel; once it reaches its end, the wheel scrolls the next container up.
- `KeyboardEvent`: `key` uses DOM names (`"a"`, `"Enter"`, `"Escape"`, `"ArrowUp"`, `"Tab"`, `"Backspace"`, `" "`), `keyCode` = Minecraft's raw key code (GLFW key code up to 26.2, SDL scancode from 26.3 on) - prefer `key`.
- `preventDefault()` on `mousedown` stops focusing, on `wheel` stops scrolling, on `keydown` `Escape` keeps the screen open.
- Focus: inputs/buttons/selects and elements with `tabIndex` are focusable; Tab / Shift+Tab move focus.
- The first Escape blurs a focused input, the next closes the screen (unless something called `preventDefault()`).

Element API (via `ref.current`): `tagName`, `id`, `classList`, `children`, `parent`, `getBoundingClientRect()`,
`querySelector(css)`, `querySelectorAll(css)`, `contains(node)`, `focus()`, `blur()`, `isFocused`,
`scrollTop` / `scrollLeft` (read & write), `maxScrollTop`, `style` (computed style).

## 5. CSS support

**Where:** `.css` files in resources (`GuiLib.open(..., stylesheets = listOf("modid:path.css"))`), inline `style` strings,
and the built-in user-agent stylesheet (`assets/guilib/css/ua.css`, lowest priority).
Cascade, specificity, `!important`, inheritance, `inherit` / `initial` / `unset` work like the web.
Invalid or unsupported CSS is **skipped with a warning** (`file:line:col`, "did you mean …") — never a crash.

**Selectors:** `*`, `tag`, `.class`, `#id`, `[attr]`, `[attr=value]`, `[attr^=v]`, `[attr$=v]`, `[attr*=v]`, compounds
(`button.primary:hover`), descendant (`a b`), child (`a > b`), `a + b`, `a ~ b`, lists (`a, b`);
pseudo-classes `:hover :active :focus :focus-visible :focus-within :disabled :enabled :checked :scrolling :first-child :last-child :only-child :root :not(…)`,
`:nth-child() :nth-last-child() :nth-of-type() :nth-last-of-type()` (`odd`, `even`, `3`, `2n+1`, `-n+3`); `:nth-child(An+B of S)` / `:nth-last-child(… of S)` count only siblings matching the selector list S (e.g. zebra rows that skip hidden ones: `.row:nth-child(even of :not(.hidden))`).
**Pseudo-elements:** `::before` and `::after` (also the old `:before`/`:after`), at the end of a selector (`.crumb + .crumb::before`, `.btn:hover::after`). They need `content`: strings and `attr(name)` (e.g. `attr(title)`), `content: ""` for decorative boxes, `none`/`normal` removes the box. The box is the first/last child of the element, inline by default, inherits from it, can be positioned, sized, transitioned and animated like any element. Clicks and hover on it go to the element. Inputs, textareas, images, items and `<br>` get none. No other pseudo-elements, no `url()`/counters/quotes in `content`.
At-rules: `@keyframes` and `@media` (nestable): comma lists, `not`/`only`, `screen`/`all`/`print`, `and`/`or`; features `width` `height` `aspect-ratio` `orientation` in GUI px, `resolution` = Minecraft's GUI scale (`min-resolution: 3dppx` or `3x`), `hover` (hover), `pointer` (fine), `prefers-reduced-motion` (no-preference), `prefers-color-scheme` (dark); `min-`/`max-` prefixes and range syntax `(400px <= width < 640px)`. Styles update when the window size or GUI scale changes.
`@supports` (nestable with `@media`): `(property: value)` is true when GuiLib knows the property and can parse the value (custom properties and `var()` values count), `selector(…)` when it can parse the selector, plus `not` / `and` / `or` and parentheses; decided once when the sheet loads. Use it for fallbacks: `@supports not (display: contents) { … }`. `@font-face` (see fonts below).
Not supported: other pseudo-elements (`::placeholder`, `::selection`, …), `@import`, `@container`.

**Values:** `px`, `%`, `em`, `rem`, `vw`, `vh`, `vmin`, `vmax`, unitless `0`; colors `#rgb #rgba #rrggbb #rrggbbaa`,
`rgb()/rgba()` (comma or space syntax), `hsl()/hsla()`, all CSS named colors, `transparent`, `currentColor`;
custom properties `--name` with `var(--name, fallback)`; math functions `calc()`, `min()`, `max()`, `clamp()`
(e.g. `width: calc(100% - 2em)`, `max-width: clamp(100px, 50vw, 300px)`; percentages resolve during layout).

**Properties:**
- Box: `width height min-width min-height max-width max-height box-sizing margin(-*) padding(-*)`
- Border: `border border-(top|right|bottom|left) border-width border-style border-color border-*-width/-style/-color border-radius border-*-radius` (styles `none` `hidden` `solid` `dashed` `dotted`; dashes are 3× the width with stretched gaps and a dash in each square corner like Chrome, dots are round from 2px; with `border-radius` a uniform dashed border gets solid corner arcs, a dotted one dots along the arc; the background shows through the gaps)
- Layout: `display` (`block inline inline-block flex inline-flex grid inline-grid none`), `position` (`static relative absolute fixed`), `top right bottom left inset z-index overflow overflow-x overflow-y`
- Inline: `vertical-align` (`baseline sub super text-top text-bottom middle top bottom`, a length, or a % of the line height) for `inline-block` / `inline-flex` / `img` / `item` boxes, and on `display: inline` elements (`span`, `sub`, `sup`, …) to raise or lower their text (shifts add up when nested; the line grows to fit)
- Flexbox: `flex flex-direction flex-wrap flex-flow flex-grow flex-shrink flex-basis justify-content align-items align-self align-content place-items place-content gap row-gap column-gap order` (`align-content` moves the lines of a wrapping container with a fixed cross size; default `normal` stretches them, like the web)
- Grid: `grid-template-columns grid-template-rows` (`px % fr auto min-content max-content minmax() repeat(n | auto-fill | auto-fit, …)`),
  `grid-template-areas grid-area grid-row grid-column grid-row-start/-end grid-column-start/-end` (line numbers, negative lines, `span n`, area names),
  `grid-auto-rows grid-auto-columns grid-auto-flow` (`row column dense`), `justify-items justify-self place-items place-self`, `justify-content`/`align-content` (distribute columns/rows), `gap`/`grid-gap`
- Animation: `transition` (+ `-property -duration -timing-function -delay`), `animation` (+ `-name -duration -timing-function -delay
  -iteration-count -direction -fill-mode -play-state`) with `@keyframes`; easing `linear ease ease-in ease-out ease-in-out cubic-bezier() steps()`. Like in browsers an animation plays once per element while it keeps the name (restyles such as hover don't replay it); remove the name and add it back to play it again.
  Animatable: colors, lengths (also px ↔ % via calc), numbers (`opacity`, `flex-grow`, `font-size` …), radii, `line-height`,
  `text-shadow`, `letter-spacing`, `box-shadow`, scrollbar colors, gradient stop colors, `visibility`, `transform`, `transform-origin`; other values switch at 50%
  in keyframes and don't transition.
- Transform: `transform` with `translate() translateX() translateY() scale() scaleX() scaleY() rotate() skew() skewX() skewY()
  matrix(a, b, c, d, tx, ty)` (`none` to reset; angles in `deg rad grad turn`) and
  `transform-origin` (lengths, %, `left center right top bottom`; default `50% 50%`). Like CSS it doesn't affect layout; clicks
  and `getBoundingClientRect()` follow the transformed box (hit-testing uses the exact rotated shape). Translate/scale stay
  pixel-exact and scaled text is re-rendered at the new size (stays sharp); rotated/skewed content is drawn through a matrix
  (text is rasterized at 2× and filtered, so rotated text stays smooth). Transforms interpolate when both lists have the same functions in the same order
  (`none` counts as matching anything), e.g. `@keyframes spin { to { transform: rotate(360deg) } }`.
- Text: `color font-family font-size font-weight font-style line-height text-align white-space text-overflow text-decoration text-shadow letter-spacing` (`letter-spacing`: `normal` or a length, also negative; added after every character, animatable)
- Visual: `background background-color background-image opacity visibility object-fit`. `background-image` takes a comma list of layers
  (first = top): `url("modid:path.png")` (stretched to the box), `linear-gradient(…)` (angles, `to right`, `to top left`, stops with
  positions, hard stops) and `radial-gradient(…)` (`circle`/`ellipse`, size keywords, `at <position>`), `conic-gradient(…)` (`from <angle>`, `at <position>`, stops in angles or %; `from` is animatable) and the `repeating-linear/radial/conic-gradient(…)` forms. Gradients respect `border-radius`.
  `box-shadow`: `none` or a comma list of `[inset] <x> <y> [<blur> [<spread>]] [<color>]` (first = top; color defaults to `currentColor`).
  Real Gaussian blur, follows `border-radius`; outer shadows are never drawn under the box (fine with translucent backgrounds),
  inset shadows sit inside the padding box. Transitions between shadow lists work (`inset` must match per position).
- Interaction: `cursor` (`auto default pointer text not-allowed crosshair move ns-resize ew-resize row-resize col-resize grab grabbing none`; `none` hides the system cursor, e.g. to draw your own at the mouse;
  while the left button is held on an element with `grab`/`grabbing`/`move`/a resize cursor, that cursor stays even when the mouse leaves it), `pointer-events`, `user-select` (parsed only)
- Generated content: `content` (strings, `attr(name)`, `none`/`normal`; only on `::before` / `::after`)
- Scrollbars: `scrollbar-width` (`auto thin none`), `scrollbar-color: <thumb> <track>` (animatable). Auto-hiding scrollbar:
  add class `guilib-autohide` to the scroll container (fades out 600 ms after scrolling stops, back on scroll or hover);
  set its colors with `--guilib-scrollbar-thumb` / `--guilib-scrollbar-track` (a plain `scrollbar-color` keeps it visible)

`font-family`: `inter` (default, bundled), `minecraft` (vanilla font; alias `monospace`), or fonts registered with
`FontManager.register("name", weight, italic, "modid:fonts/x.ttf")` or with `@font-face` in a stylesheet:
`@font-face { font-family: "My Font"; src: url("modid:fonts/my.ttf") format("truetype"); font-weight: 700; font-style: italic }`
(`src` = resource locations, the first that exists is used; `local()` and web URLs are not supported; `font-weight` may be a
range like `100 900` for a variable font, drawn at its default instance). Fonts are global like resources: a family declared
in one stylesheet works in every screen. Weights map to the nearest face (400/500/600/700).

## 6. Differences from the web (read this!)

1. **`px` means GUI pixels** (scaled by Minecraft's GUI scale). Default `font-size` is **8px** and `1rem = 8px`,
   so a web value like `font-size: 14px` is large here; typical UI text is 7–10px.
2. `body` **is the screen**: always exactly viewport-sized; there is no `html`; `:root` matches `body`.
3. `box-sizing: border-box` is the **default** for everything.
4. **No margin collapsing** (vertical margins add up).
5. Default text color is light (`#f2f3f5`), default font Inter.
6. `style` is a CSS **string**, not an object. Event handlers are Kotlin lambdas (`onClick = { e -> … }`).
7. `useEffect { }` without deps runs **once** (like `[]`); there is no "run after every render" variant.
8. `onChange` on inputs/selects/checkboxes/switches/sliders fires on every change (React behaviour); sliders also have `onChangeEnd`.
9. `overflow: hidden` clips **rectangularly**. With `border-radius` on the clipping element, child backgrounds that sit
   exactly in one of its corners (headers, footers, sidebars) are rounded to match; other content (text, images,
   children that only partly overlap a corner) is not cut to the curve.
10. Per-side borders on a box with `border-radius` are drawn as straight strips that stop at rounded corners
    (the border doesn't bend around the curve); uniform borders and sides between square corners are exact.
11. Inline elements (`span`, `code`, …) paint background, border, `border-radius` and `box-shadow` per line like the web
    (`box-decoration-break: slice`); horizontal margin/padding/border take space, vertical ones don't change the line height.
    Use `display: inline-block` when the box must not wrap or needs a width/height.
12. `border-width` default (`medium`) is 1px; like the web, a border without `border-style` draws nothing.
13. Every positioned element (`relative/absolute/fixed`) is its own paint layer; `z-index` orders layers among siblings in the same layer. Use `portal { }` for things that must be on top of everything. `position: fixed` elements ignore their ancestors' `transform`.
14. Not supported (yet): 3D transforms (a `transform` containing them is ignored),
    pseudo-elements other than `::before`/`::after`, float, subgrid, named grid lines (`[name]` is ignored).
    Grid: items can't be placed before line 1.
15. Images: `src` is a resource location (`"modid:textures/x.png"`), PNG, SVG or GIF only; no URLs yet. Animated GIFs loop like in a browser (all images with the same `src` play in sync); they are decoded in the background, so a big GIF stays empty for a moment instead of freezing the game (its size is known right away, layout doesn't jump); very long GIFs are cut off after ~32M pixels of frames. SVGs are rasterized at their exact on-screen pixel size (a few ms each, cached per size; JSVG is warmed up in the background at startup). Unlike a browser `<img>`, `currentColor` inside an SVG image (also `background-image`) is the element's CSS `color`, like inline SVG: write icons with `fill="currentColor"` / `stroke="currentColor"` and tint them with `color` (follows `:hover`, themes; a `color` attribute on the root `<svg>` wins). Each color is rasterized once and cached, so avoid animating `color` on big SVGs.
16. Text has no kerning; `text-align: justify` behaves like `left`. No right-to-left or complex-script shaping: Arabic/Hebrew
    render as unconnected letters in left-to-right order. Characters missing from the font (CJK, emoji, …) fall back to
    Minecraft's font.
17. `vertical-align: top` / `bottom` on `display: inline` elements act like `text-top` / `text-bottom` (aligned to the parent's text, not to the line box).
18. Flex items have `min-width: auto` (content size) like the web — for ellipsis inside flex, set `min-width: 0`.
19. **`:scrolling` is GuiLib-only** (browsers don't have it): it matches a scroll container while its scroll position changes
    (wheel, dragging, `scrollTop = …`) and for 150 ms after the last change. Use it with `transition-delay` for
    "fade out after X ms" effects; the opt-in class `guilib-autohide` is built on it.

## 7. Recipes

- **Full-screen centered panel:** `body { display: flex; align-items: center; justify-content: center }` + a sized child.
- **Scrollable list filling the rest:** parent `display: flex; flex-direction: column; height: …`, list `flex-grow: 1; min-height: 0; overflow: auto`.
- **Ellipsis:** `white-space: nowrap; overflow: hidden; text-overflow: ellipsis` (+ `min-width: 0` in flex rows).
- **Theme:** define `--vars` on `:root` in one CSS file, use `var(--x)` everywhere.
- **Recolor the built-in controls:** every color in `ua.css` is a `--guilib-*` variable, so override variables instead of rules:
  `:root { --guilib-accent: #e67e22; --guilib-surface-2: #fff; --guilib-text: #1f2328 }`. Groups: text (`--guilib-text`, `-strong`, `-secondary`, `-muted`, `-subtle`, `--guilib-link`),
  accent (`--guilib-accent`, `-soft`, `--guilib-on-accent`, `-muted`, `--guilib-selection`), surfaces (`--guilib-surface`, `-2`, `-3`, `--guilib-highlight`, `--guilib-tint`, `-strong`, `--guilib-shade`, `--guilib-backdrop`),
  borders (`--guilib-border`, `-hover`, `-strong`, `-subtle`, `--guilib-divider`, `--guilib-focus`), buttons (`--guilib-button`, `-hover`, `-active`, `-border`),
  `--guilib-track`, `-border`, `--guilib-thumb`, status (`--guilib-success`, `--guilib-warning`, `--guilib-danger`, `--guilib-danger-text`), `--guilib-scrollbar-thumb` / `-track`,
  `--guilib-picker-handle`, `--guilib-swatch-border` (defaults + comments at the top of `ua.css`). On a subtree, also set `color: var(--guilib-text)` there
  (body's color is resolved on body). Menus, tooltips, select dropdowns, modals and toasts are portals under body: they follow `:root` / body classes, not the subtree.
- **Close button:** `button(onClick = { GuiLib.close() })`.
- **Async data:** `val commit = useAsync { fetchLatestCommit() }` → `+when { commit.error != null -> "unavailable"; commit.loading -> "loading…"; else -> commit.value!! }`; `button(onClick = { commit.reload() })`. Callback APIs: `usePromise { resolve, reject -> api.load(resolve, reject) }`. State setters are thread-safe too. Other work: `GuiLib.runOnUi { }`.
- **Hover fade:** `.card { transition: background-color 150ms } .card:hover { background-color: #333 }`.
- **Fade in on open:** `@keyframes fade-in { from { opacity: 0 } }` + `.panel { animation: fade-in 200ms ease-out }`.
- **Staggered slide-in:** `@keyframes slide-in { from { opacity: 0; transform: translateX(-20px) } }` +
  `.item { animation: slide-in 300ms ease-out backwards }` and `style = "animation-delay: ${i * 50}ms"` per item.
- **Slide out before removal:** `presence(visible = open, exitMs = 200) { leaving -> div(className = classNames("panel", "leaving" to leaving)) { … } }`
- **Animate removed list rows:** `presenceList(rows, key = { it.id }, exitMs = 200) { row, leaving -> div(className = classNames("row", "leaving" to leaving)) { … } }` + `.row.leaving { animation: row-out 200ms forwards; pointer-events: none }`
  with `.panel.leaving { animation: slide-out 200ms ease-in forwards }`.
- **Grow on hover:** `.card { transition: transform 150ms ease-out } .card:hover { transform: scale(1.05) }`.
- **Spinner:** `@keyframes spin { to { transform: rotate(360deg) } }` + `.spinner { animation: spin 1s linear infinite }`.
- **Scrollbar that hides when idle:** `scroll(className = "list guilib-autohide")`; custom color: `.list { --guilib-scrollbar-thumb: #5b8def }`.
- **Zebra rows:** `.row:nth-child(even) { background-color: #2b2d31 }`; with filtered-out rows still in the DOM: `.row:nth-child(even of :not(.hidden))`.
- **Horizontal scroll row:** `display: flex; overflow-x: auto; overflow-y: hidden` with `flex-shrink: 0` on the children.
  The plain wheel scrolls it sideways (as does Shift + wheel or a trackpad).
- **Translated UI:** `val t = useTranslation(); h1 { +t("mymod.gui.title") }` with `assets/mymod/lang/en_us.json`.
- **Show a chat message / item name:** `text(stack.hoverName)` or `text(Component.translatable("mymod.msg").withStyle(ChatFormatting.GOLD))`.
- **Copy button:** `val clipboard = useClipboard(); val toast = useToast()` → `button(onClick = { clipboard.set(note); toast.success("Copied") }) { +"Copy note" }`.
- **Player in a list:** `div(className = "row") { playerHead(name, style = "margin-right: 4px"); +name }`.
- **Reorderable list:** `sortableList(tasks, key = { it.id }, onReorder = { tasks = it }) { task, _ -> div { +task.name } }`.
- **Responsive tiles:** `display: grid; grid-template-columns: repeat(auto-fill, minmax(60px, 1fr)); gap: 4px`
  (`auto-fit` instead collapses the empty columns, so a few tiles stretch over the whole row).
- **Sidebar + content:** `display: grid; grid-template-columns: 80px 1fr` (or `grid-template-areas`).
- **Keyboard shortcut for the whole screen:** `useDocumentEvent("keydown") { e -> if ((e as KeyboardEvent).key == "r") refresh() }`.

## 8. Development

- `/guilib showcase` opens a demo of every feature; `/guilib reload` reloads CSS.
- In a dev environment CSS files are **hot-reloaded** from `src/main/resources` on save (no rebuild).
- Warnings (unknown properties, invalid values, duplicate keys, hook misuse) go to the log with file/line.
