package net.sbo.guilib.core.dsl

import net.sbo.guilib.core.controls.ChipsComponent
import net.sbo.guilib.core.controls.ChipsProps
import net.sbo.guilib.core.controls.ChoiceComponent
import net.sbo.guilib.core.controls.ChoiceProps
import net.sbo.guilib.core.controls.TabDef
import net.sbo.guilib.core.controls.TabsComponent
import net.sbo.guilib.core.controls.TabsProps
import net.sbo.guilib.core.controls.CollapseComponent
import net.sbo.guilib.core.controls.CollapseProps
import net.sbo.guilib.core.controls.ContextMenuComponent
import net.sbo.guilib.core.controls.ContextMenuProps
import net.sbo.guilib.core.controls.DetailsComponent
import net.sbo.guilib.core.controls.DetailsProps
import net.sbo.guilib.core.controls.MenuEntry
import net.sbo.guilib.core.controls.Toaster
import net.sbo.guilib.core.dom.Clipboard

/**
 * Radio buttons, controlled like React: `radioGroup(value = size, onChange = { size = it }) { option("2", "Duo"); option("3", "Trio") }`.
 * Arrow keys move the selection. Horizontal by default, [vertical] stacks them.
 * Styled with `.guilib-radio-group`, `.guilib-radio` (`.checked`, `.disabled`), `.guilib-radio-dot`, `.guilib-radio-label`.
 */
fun NodeBuilder.radioGroup(
    value: String?,
    onChange: ((String) -> Unit)? = null,
    vertical: Boolean = false,
    disabled: Boolean = false,
    className: String? = null,
    id: String? = null,
    key: Any? = null,
    options: SelectBuilder.() -> Unit,
) = ChoiceComponent(ChoiceProps(value, onChange, SelectBuilder().apply(options).options, false, vertical, disabled, className, id), key)

/**
 * Segmented buttons (one of several, joined into a bar with a sliding highlight), controlled like [radioGroup]:
 * `segmented(value = tier, onChange = { tier = it }) { option("t1", "Basic"); option("t5", "Infernal") }`.
 * Styled with `.guilib-segmented`, `.guilib-segment` (`.selected`, `.disabled`), `.guilib-segment-indicator`.
 */
fun NodeBuilder.segmented(
    value: String?,
    onChange: ((String) -> Unit)? = null,
    vertical: Boolean = false,
    disabled: Boolean = false,
    className: String? = null,
    id: String? = null,
    key: Any? = null,
    options: SelectBuilder.() -> Unit,
) = ChoiceComponent(ChoiceProps(value, onChange, SelectBuilder().apply(options).options, true, vertical, disabled, className, id), key)

/**
 * Toggleable chips for choosing several options (filters), controlled:
 * `chips(values = picked, onChange = { picked = it }) { option("trophy", "Trophy"); option("lava", "Lava") }`.
 * [onChange] gets the selected values in option order. Styled with `.guilib-chips`, `.guilib-chip` (`.selected`).
 */
fun NodeBuilder.chips(
    values: List<String>,
    onChange: ((List<String>) -> Unit)? = null,
    disabled: Boolean = false,
    className: String? = null,
    key: Any? = null,
    options: SelectBuilder.() -> Unit,
) = ChipsComponent(ChipsProps(values, onChange, SelectBuilder().apply(options).options, disabled, className), key)

/** Collects the `tab(...)` entries of [tabs]. */
@GuiDsl
class TabsBuilder {
    internal val tabs = ArrayList<TabDef>()

    /** A tab. With [content] the tab bar also renders the active tab's content below itself. */
    fun tab(value: String, label: String, disabled: Boolean = false, content: (NodeBuilder.() -> Unit)? = null) {
        tabs += TabDef(value, label, disabled, content)
    }
}

/**
 * Tab bar with an indicator that slides to the active tab, controlled:
 *
 * ```kotlin
 * var category by useState("dungeons")
 * tabs(value = category, onChange = { category = it }) {
 *     tab("dungeons", "Dungeons") { DungeonList() }
 *     tab("kuudra", "Kuudra") { KuudraList() }
 * }
 * ```
 * Without content lambdas only the bar is rendered (render the content yourself from `value`).
 * [variant] `"underline"` (default) or `"pills"` (good for sub-categories). Left/Right arrows switch tabs.
 * Styled with `.guilib-tabs` (`.underline`, `.pills`), `.guilib-tab-list`, `.guilib-tab` (`.active`),
 * `.guilib-tab-indicator`, `.guilib-tab-panel`.
 */
fun NodeBuilder.tabs(
    value: String?,
    onChange: ((String) -> Unit)? = null,
    variant: String = "underline",
    className: String? = null,
    id: String? = null,
    key: Any? = null,
    tabs: TabsBuilder.() -> Unit,
) = TabsComponent(TabsProps(value, onChange, TabsBuilder().apply(tabs).tabs, variant, className, id), key)

/**
 * Animates its height between 0 and the content's height (the building block of [details]):
 * `collapse(open = expanded) { PartyDetails(party) }`. While open the height follows the content (changes animate).
 * Closed content is unmounted after the animation unless [keepMounted]. Styled with `.guilib-collapse` (`.open`).
 */
fun NodeBuilder.collapse(
    open: Boolean,
    durationMs: Long = 200,
    keepMounted: Boolean = false,
    className: String? = null,
    key: Any? = null,
    children: NodeBuilder.() -> Unit,
) = CollapseComponent(CollapseProps(open, durationMs, keepMounted, className, children), key)

/**
 * Expandable section (accordion item) like HTML `<details>`: a clickable summary row with a turning chevron and a body
 * whose height animates. Uncontrolled: `details("Party details") { … }` (starts closed, or [defaultOpen]).
 * Controlled (e.g. only one open at a time): `details("A", open = openId == "a", onToggle = { openId = if (it) "a" else null }) { … }`.
 * Styled with `.guilib-details` (`.open`), `.guilib-details-summary`, `.guilib-details-chevron`, `.guilib-details-title`,
 * `.guilib-details-content`.
 */
fun NodeBuilder.details(
    summary: String,
    open: Boolean? = null,
    onToggle: ((Boolean) -> Unit)? = null,
    defaultOpen: Boolean = false,
    disabled: Boolean = false,
    durationMs: Long = 200,
    className: String? = null,
    key: Any? = null,
    children: NodeBuilder.() -> Unit,
) = details({ +summary }, open, onToggle, defaultOpen, disabled, durationMs, className, key, children)

/** [details] with a custom summary row: `details(summary = { b { +"Party" }; span { +"3/5" } }) { … }`. */
fun NodeBuilder.details(
    summary: NodeBuilder.() -> Unit,
    open: Boolean? = null,
    onToggle: ((Boolean) -> Unit)? = null,
    defaultOpen: Boolean = false,
    disabled: Boolean = false,
    durationMs: Long = 200,
    className: String? = null,
    key: Any? = null,
    children: NodeBuilder.() -> Unit,
) = DetailsComponent(DetailsProps(summary, open, defaultOpen, onToggle, disabled, durationMs, className, children), key)

/**
 * The toaster of this screen: `val toast = useToast(); toast.success("Party created")`, `toast.error("Server not reachable")`,
 * `toast.show("Text", kind = "info", title = "Title", durationMs = 5000)`. Toasts stack in the bottom right corner,
 * disappear on their own and on click; hovering a toast pauses its timer. Safe to call from any thread (e.g. a network callback).
 */
fun ComponentScope.useToast(): Toaster = Toaster.of(useDocument())

/**
 * The system clipboard, for "Copy" buttons: `val clipboard = useClipboard()` →
 * `button(onClick = { clipboard.set(note); toast.success("Copied") }) { +"Copy note" }`; `clipboard.get()` reads it.
 * `set` is safe to call from any thread; call `get` from the UI thread (event handlers, effects).
 */
fun ComponentScope.useClipboard(): Clipboard = useDocument().clipboard

/**
 * Escape as a back key: while [enabled], an Escape that nothing else uses (open menus, selects and modals close first,
 * a focused input is left first) calls [onBack] instead of closing the screen. For windows with sub-pages:
 * `useEscapeBack(page != "list") { page = "list" }`. With several, the component mounted last wins.
 */
fun ComponentScope.useEscapeBack(enabled: Boolean = true, onBack: () -> Unit) {
    val doc = useDocument()
    val latest = useRef(onBack)
    latest.current = onBack
    val active = useRef(enabled)
    active.current = enabled
    useEffect {
        val handler = { if (active.current) { latest.current(); true } else false }
        doc.escapeBackHandlers += handler
        onCleanup { doc.escapeBackHandlers -= handler }
    }
}

/** Collects the entries of a menu ([contextMenu]). */
@GuiDsl
class MenuBuilder {
    internal val entries = ArrayList<MenuEntry>()

    /** A clickable entry. [danger] styles it red (e.g. "Kick"); [shortcut] is shown right-aligned as a hint. */
    fun item(label: String, disabled: Boolean = false, danger: Boolean = false, shortcut: String? = null, onClick: () -> Unit) {
        entries += MenuEntry.Item(label, disabled, danger, shortcut, onClick)
    }

    /** A non-clickable caption, e.g. the player name. */
    fun header(text: String) {
        entries += MenuEntry.Header(text)
    }

    /** A divider line. */
    fun separator() {
        entries += MenuEntry.Separator
    }
}

/**
 * Right-click menu for [children], opened at the mouse and kept inside the screen:
 *
 * ```kotlin
 * contextMenu(menu = {
 *     header(player.name)
 *     item("Invite") { invite(player) }
 *     item("View profile") { openProfile(player) }
 *     separator()
 *     item("Kick", danger = true, disabled = !isLeader) { kick(player) }
 * }) { PlayerRow(player) }
 * ```
 * The children are wrapped in a `div.guilib-context-anchor` (block; give it a [className] to style it, e.g. `display: inline-block`).
 * Arrow keys + Enter choose, Escape / a click outside close it. Styled with `.guilib-menu`, `.guilib-menu-item`
 * (`.highlighted`, `.danger`, `.disabled`), `.guilib-menu-header`, `.guilib-menu-separator`, `.guilib-menu-shortcut`.
 */
fun NodeBuilder.contextMenu(
    menu: MenuBuilder.() -> Unit,
    disabled: Boolean = false,
    className: String? = null,
    key: Any? = null,
    children: NodeBuilder.() -> Unit,
) = ContextMenuComponent(ContextMenuProps({ MenuBuilder().apply(menu).entries }, disabled, className, children), key)
