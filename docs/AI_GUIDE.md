# GuiLib — Reference for AI models (and humans in a hurry)

GuiLib builds Minecraft (Fabric, MC 26.1.x / 26.2) screens the way you build web UIs:
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
| `useMemo(deps…) { … }`, `useRef(init)`, `useElementRef()` | `ref = myRef` on any tag sets `myRef.current` to the `Element`. |
| `createContext(default)`, `Ctx.Provider(value) { … }`, `useContext(Ctx)` | Like React context. |
| `useDocument()` | The `Document` (viewport size, `focusedElement`, `addEventListener`). |
| `useDocumentEvent("keydown") { e -> … }` | Global listener while mounted (runs before element handlers). |
| `useForceUpdate()` | Escape hatch. |

Rules of hooks apply: call hooks unconditionally at the top of the component (not inside `div { }` blocks, loops or ifs).
Hook misuse is detected and logged.

## 3. Elements (tag functions)

Every tag accepts: `className`, `id`, `style` (a **CSS string**, e.g. `style = "width: 20px; color: red"`), `key`,
`title` (native tooltip), `ref`, `tabIndex`, and event handlers:
`onClick, onDoubleClick, onContextMenu, onMouseDown, onMouseUp, onMouseMove, onMouseEnter, onMouseLeave` (MouseEvent),
`onWheel` (WheelEvent), `onKeyDown, onKeyUp` (KeyboardEvent), `onFocus, onBlur` (FocusEvent), `onScroll` (ScrollEvent).
Children go in the trailing lambda; text with `+"text"` or `text(value)`.

| Tag | Default display | Extra props / notes |
|---|---|---|
| `div, section, header, footer, nav, main, aside, article, p, h1–h4, ul, ol, li, pre, hr` | block | `p`, `h*` have small bottom margins; `pre` is `white-space: pre` + Minecraft font. |
| `span, a, strong, b, em, i, small, code, label` | inline | `label` forwards clicks to the first input/select/button inside. |
| `button` | inline-flex (centered) | `disabled`. Disabled elements get no mouse events. Enter/Space activate a focused button. |
| `scroll` *(GuiLib tag)* | block + `overflow: auto` | Scroll container with a thin scrollbar. Any element with `overflow: auto/scroll` scrolls too. |
| `img("modid:path.png")` | inline (replaced) | `src` = resource location (PNG or SVG), `alt`. Natural size = image size. `object-fit` supported. |
| `item(stack)` *(GuiLib tag)* | inline (replaced, 16×16) | `stack: ItemStack`, `decorations = true` (count/durability). Needs a loaded world. |
| `entity(entity)` *(GuiLib tag)* | inline (replaced, 48×72) | `entity: LivingEntity`, scaled to fit the box like the inventory player model. `followMouse = false`, `lookX`/`lookY` (look offset in px when not following), `scale = 1f`. Player models: `FakePlayer.ofLocalPlayer()`, `FakePlayer.of("name")`, `.of(uuid)`, `.of(gameProfile)`, `.of(player)` (`net.sbo.guilib.fabric.entity`; `null` without a world – create once with `useMemo`). |
| `input(...)` | inline-block | `type = "text" | "password" | "number" | "checkbox"`, `value`, `placeholder`, `checked`, `disabled`, `maxLength`, `autoFocus`, `onInput`, `onChange` (InputEvent: `.value`, `.checked`). |
| `br` | – | Line break inside text. |
| `select(value, onChange) { option("v") { +"Label" }; option("v2", "Label 2", disabled = true) }` | inline-flex | Component; menu opens in a portal (never clipped). `placeholder`, `disabled`. |
| `checkbox(checked, onChange, label = "…")` | inline-flex | Convenience: `label` + `input(type = "checkbox")`. |
| `tooltip("text", placement = "top|bottom|left|right") { anchor }` / `tooltip(content = { … }) { anchor }` | – | Hover tooltip (300 ms delay). For simple cases use the `title` prop. |
| `modal(open, onClose) { … }` | – | Dialog in a portal with backdrop; Escape and backdrop click call `onClose`. |
| `presence(visible, exitMs) { leaving -> … }` | – | Exit animations (like Framer Motion's `AnimatePresence`): when `visible` turns false, the children render with `leaving = true` and are removed after `exitMs`. Use `leaving` to switch to an exit `animation`/`transition`. |
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
- `WheelEvent`: `deltaX/deltaY` (px; positive = down). Default action scrolls the nearest scroll container.
- `KeyboardEvent`: `key` uses DOM names (`"a"`, `"Enter"`, `"Escape"`, `"ArrowUp"`, `"Tab"`, `"Backspace"`, `" "`), `keyCode` = GLFW code.
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
pseudo-classes `:hover :active :focus :focus-within :disabled :enabled :checked :first-child :last-child :only-child :root :not(…)`.
At-rules: `@keyframes` is supported. Not supported: pseudo-elements (`::before`), `:nth-child`, `@media`, `@import`, `@font-face`.

**Values:** `px`, `%`, `em`, `rem`, `vw`, `vh`, `vmin`, `vmax`, unitless `0`; colors `#rgb #rgba #rrggbb #rrggbbaa`,
`rgb()/rgba()` (comma or space syntax), `hsl()/hsla()`, all CSS named colors, `transparent`, `currentColor`;
custom properties `--name` with `var(--name, fallback)`; math functions `calc()`, `min()`, `max()`, `clamp()`
(e.g. `width: calc(100% - 2em)`, `max-width: clamp(100px, 50vw, 300px)`; percentages resolve during layout).

**Properties:**
- Box: `width height min-width min-height max-width max-height box-sizing margin(-*) padding(-*)`
- Border: `border border-(top|right|bottom|left) border-width border-style border-color border-*-width/-style/-color border-radius border-*-radius` (styles `solid`; `dashed/dotted` draw solid)
- Layout: `display` (`block inline inline-block flex inline-flex grid inline-grid none`), `position` (`static relative absolute fixed`), `top right bottom left inset z-index overflow overflow-x overflow-y`
- Flexbox: `flex flex-direction flex-wrap flex-flow flex-grow flex-shrink flex-basis justify-content align-items align-self place-items gap row-gap column-gap order`
- Grid: `grid-template-columns grid-template-rows` (`px % fr auto min-content max-content minmax() repeat(n | auto-fill | auto-fit, …)`),
  `grid-template-areas grid-area grid-row grid-column grid-row-start/-end grid-column-start/-end` (line numbers, negative lines, `span n`, area names),
  `grid-auto-rows grid-auto-columns grid-auto-flow` (`row column dense`), `justify-items justify-self place-items place-self`, `gap`/`grid-gap`
- Animation: `transition` (+ `-property -duration -timing-function -delay`), `animation` (+ `-name -duration -timing-function -delay
  -iteration-count -direction -fill-mode -play-state`) with `@keyframes`; easing `linear ease ease-in ease-out ease-in-out cubic-bezier() steps()`.
  Animatable: colors, lengths (also px ↔ % via calc), numbers (`opacity`, `flex-grow`, `font-size` …), radii, `line-height`,
  `text-shadow`, scrollbar colors, gradient stop colors, `visibility`, `transform`, `transform-origin`; other values switch at 50%
  in keyframes and don't transition.
- Transform: `transform` with `translate() translateX() translateY() scale() scaleX() scaleY()` (`none` to reset) and
  `transform-origin` (lengths, %, `left center right top bottom`; default `50% 50%`). Like CSS it doesn't affect layout; clicks,
  clipping and `getBoundingClientRect()` follow the transformed box. Scaled text is re-rendered at the new size (stays sharp).
  Transforms interpolate when both lists have the same functions in the same order (`none` counts as matching anything).
- Text: `color font-family font-size font-weight font-style line-height text-align white-space text-overflow text-decoration text-shadow`
- Visual: `background background-color background-image opacity visibility object-fit`. `background-image` takes a comma list of layers
  (first = top): `url("modid:path.png")` (stretched to the box), `linear-gradient(…)` (angles, `to right`, `to top left`, stops with
  positions, hard stops) and `radial-gradient(…)` (`circle`/`ellipse`, size keywords, `at <position>`). Gradients respect `border-radius`.
- Interaction: `cursor` (`auto default pointer text not-allowed crosshair move ns-resize ew-resize grab`), `pointer-events`, `user-select` (parsed only)
- Scrollbars: `scrollbar-width` (`auto thin none`), `scrollbar-color: <thumb> <track>`

`font-family`: `inter` (default, bundled), `minecraft` (vanilla font; alias `monospace`), or fonts registered with
`FontManager.register("name", weight, italic, "modid:fonts/x.ttf")`. Weights map to the nearest face (400/500/600/700).

## 6. Differences from the web (read this!)

1. **`px` means GUI pixels** (scaled by Minecraft's GUI scale). Default `font-size` is **8px** and `1rem = 8px`,
   so a web value like `font-size: 14px` is large here; typical UI text is 7–10px.
2. `body` **is the screen**: always exactly viewport-sized; there is no `html`; `:root` matches `body`.
3. `box-sizing: border-box` is the **default** for everything.
4. **No margin collapsing** (vertical margins add up).
5. Default text color is light (`#f2f3f5`), default font Inter.
6. `style` is a CSS **string**, not an object. Event handlers are Kotlin lambdas (`onClick = { e -> … }`).
7. `useEffect { }` without deps runs **once** (like `[]`); there is no "run after every render" variant.
8. `onChange` on inputs/selects/checkboxes fires on every change (React behaviour).
9. `overflow: hidden` clips **rectangularly**. With `border-radius` on the clipping element, child backgrounds that sit
   exactly in one of its corners (headers, footers, sidebars) are rounded to match; other content (text, images,
   children that only partly overlap a corner) is not cut to the curve.
10. Per-side borders on a box with `border-radius` are drawn as straight strips that stop at rounded corners
    (the border doesn't bend around the curve); uniform borders and sides between square corners are exact.
11. Inline elements (`span`, …) ignore padding/border/background; use `display: inline-block` for boxes inside text.
12. `border-width` default (`medium`) is 1px; like the web, a border without `border-style` draws nothing.
13. Every positioned element (`relative/absolute/fixed`) is its own paint layer; `z-index` orders layers among siblings in the same layer. Use `portal { }` for things that must be on top of everything. `position: fixed` elements ignore their ancestors' `transform`.
14. Not supported (yet): `rotate`/`skew`/`matrix` transforms (a `transform` containing them is ignored), box-shadow, `repeating-*-gradient`, `conic-gradient`, `@media`,
    `:nth-child`, pseudo-elements, float, `align-content`, `vertical-align`, letter-spacing, subgrid, named grid lines (`[name]` is ignored).
    Grid: `auto-fit` behaves like `auto-fill` (empty tracks aren't collapsed); items can't be placed before line 1.
15. Images: `src` is a resource location (`"modid:textures/x.png"`), PNG or SVG only; no URLs yet.
16. Text has no kerning; `text-align: justify` behaves like `left`. No right-to-left or complex-script shaping: Arabic/Hebrew
    render as unconnected letters in left-to-right order. Characters missing from the font (CJK, emoji, …) fall back to
    Minecraft's font.
17. `display: inline-block` / `img` / `item` sit on the text baseline; `vertical-align` is not supported.
18. Flex items have `min-width: auto` (content size) like the web — for ellipsis inside flex, set `min-width: 0`.

## 7. Recipes

- **Full-screen centered panel:** `body { display: flex; align-items: center; justify-content: center }` + a sized child.
- **Scrollable list filling the rest:** parent `display: flex; flex-direction: column; height: …`, list `flex-grow: 1; min-height: 0; overflow: auto`.
- **Ellipsis:** `white-space: nowrap; overflow: hidden; text-overflow: ellipsis` (+ `min-width: 0` in flex rows).
- **Theme:** define `--vars` on `:root` in one CSS file, use `var(--x)` everywhere.
- **Close button:** `button(onClick = { GuiLib.close() })`.
- **Async data:** fetch in `useEffect`, call the state setter from the callback (thread-safe). Other work: `GuiLib.runOnUi { }`.
- **Hover fade:** `.card { transition: background-color 150ms } .card:hover { background-color: #333 }`.
- **Fade in on open:** `@keyframes fade-in { from { opacity: 0 } }` + `.panel { animation: fade-in 200ms ease-out }`.
- **Staggered slide-in:** `@keyframes slide-in { from { opacity: 0; transform: translateX(-20px) } }` +
  `.item { animation: slide-in 300ms ease-out backwards }` and `style = "animation-delay: ${i * 50}ms"` per item.
- **Slide out before removal:** `presence(visible = open, exitMs = 200) { leaving -> div(className = classNames("panel", "leaving" to leaving)) { … } }`
  with `.panel.leaving { animation: slide-out 200ms ease-in forwards }`.
- **Grow on hover:** `.card { transition: transform 150ms ease-out } .card:hover { transform: scale(1.05) }`.
- **Responsive tiles:** `display: grid; grid-template-columns: repeat(auto-fill, minmax(60px, 1fr)); gap: 4px`.
- **Sidebar + content:** `display: grid; grid-template-columns: 80px 1fr` (or `grid-template-areas`).
- **Keyboard shortcut for the whole screen:** `useDocumentEvent("keydown") { e -> if ((e as KeyboardEvent).key == "r") refresh() }`.

## 8. Development

- `/guilib showcase` opens a demo of every feature; `/guilib reload` reloads CSS.
- In a dev environment CSS files are **hot-reloaded** from `src/main/resources` on save (no rebuild).
- Warnings (unknown properties, invalid values, duplicate keys, hook misuse) go to the log with file/line.
