# GuiLib – Architektur

> Freigegebener Architektur-Vorschlag (2026-09-30) plus Umsetzungsstand und Abweichungen am Ende des Dokuments.


## Context
SBO rendert seine GUIs heute mit **Elementa 774 + UniversalCraft 536** (`PartyFinderGUI : WindowScreen(ElementaVersion.V10)`,
imperativ mit `lateinit` Komponenten, `childOf`/`constrain`, manuelles Öffnen/Schließen von Sub-Fenstern). Ziel ist eine
eigene Library, deren API sich so eng wie möglich an HTML/React/CSS hält, damit Menschen und KI damit schnell und fehlerfrei
UIs bauen. Die Library soll später den Partyfinder tragen – die Neugestaltung ist **nicht** Teil dieses Auftrags, bestehende
SBO-UI wird nicht angefasst.

Vom Nutzer getroffene Entscheidungen:
- Markup: **A – typsichere Kotlin-DSL** mit HTML-Tagnamen (JSX-Gefühl).
- Rendering: **eigene Render-Pipeline** (SDF-Shader für border-radius/Border) **plus Bilder (PNG) und SVG**.
- Text: **eigenes TTF-Rendering**, Default-Font **Inter** (gebündelt, OFL), `font-family: minecraft` als Option.
- Aufbau **wie Elementa: eigenes Repo**, als Maven-Artefakt veröffentlicht, in Mods per `include()` (Jar-in-Jar) eingebunden.
- Name/Package: **`net.sbo.guilib`**.

## Analyse-Ergebnisse (SBO-Kotlin)
- MC **26.1.2 + 26.2**, **Fabric** (Loader 0.19.5, fabric-api, fabric-language-kotlin 1.14.1 / Kotlin 2.4.20), Java 25.
- Gradle Kotlin DSL, Loom 1.18.2, **Deftu multiversion 2.80.0** (Preprocessor `//#if MC > 26.1`, `versions/<ver>-fabric`,
  `root.gradle.kts`, Node-Link 26.2 → 26.1.2), bloom-Replacements (`mc.screen` → `mc.gui.screen()` ab 26.2).
- Tests: JUnit 6 bereits konfiguriert (`src/test/kotlin`, nur DummyTest/MixinTest).
- Vanilla-GUI-API: `Screen.extractRenderState(GuiGraphicsExtractor, mouseX, mouseY, delta)`, `MouseButtonEvent`, `KeyEvent`.
  `GuiGraphicsExtractor` ist in 26.1.2 und 26.2 **identisch** (`fill`, `text`, `enableScissor`, `blit`, `item`, `pose()`
  als `Matrix3x2fStack`, `nextStratum`). Eigene Quads mit eigenem Shader: `GuiRenderState.addGuiElement(GuiElementRenderState)`
  (`buildVertices`, `pipeline()`, `textureSetup()`, `scissorArea()`) – Zugriff auf das private `guiRenderState`-Feld per Class-Tweaker.
- MC liefert **lwjgl-freetype** und **lwjgl-stb** mit → TTF-Rasterisierung ohne zusätzliche Natives. MC hat auch einen
  Vulkan-Backend-Pfad → Shader nur über `RenderPipeline`/Core-Shader-System, keine rohen GL-Calls.
- Pipeline-Registrierung wie in `src/main/kotlin/net/sbo/mod/utils/render/SboRenderPipelines.kt` (`RenderPipelines.register(...)`).

### Was an Elementa für KI/Entwickler unintuitiv ist (→ vermeiden)
- Eigenes Constraint-Vokabular (`CenterConstraint`, `SiblingConstraint`, `ChildBasedSizeConstraint`, `10.pixels()`) statt Box-Model/Flexbox.
- Imperativer Baum (`childOf`, `constrain {}`, `lateinit`-Felder), kein deklaratives Re-Render.
- Kein Stylesheet/keine Kaskade: Farben/Abstände verstreut im Code (siehe `guis/partyfinder/Theme.kt`).
- Hover/Fokus manuell über `onMouseEnter` + `animate {}`; Effekte (`OutlineEffect`) statt `border`.
- Namen (`UIBlock`, `UIContainer`, `UIText`) ohne Web-Entsprechung; `State<T>`/`bindText` weicht von React-Hooks ab.

## Repo- und Modulstruktur
Neues Repo **`SBO-GuiLib`** (git init, Branch `main`), Build-Setup 1:1 nach SBO-Vorbild
(Deftu multiversion, gleiche Versionen/Properties), Artefakte `net.sbo:guilib-26.1.2-fabric` / `guilib-26.2-fabric`,
Mod-ID `guilib`, eigenes `fabric.mod.json`. Abhängigkeiten nur fabric-api, fabric-language-kotlin, **JSVG** (`com.github.weisj:jsvg`, MIT, Jar-in-Jar).
Kein Elementa/UC.

```
SBO-GuiLib/
  settings.gradle.kts, root.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml
  versions/26.1.2-fabric, versions/26.2-fabric, versions/mainProject
  src/main/kotlin/net/sbo/guilib/
    core/            ← REIN Kotlin, KEINE net.minecraft-Imports (per Test erzwungen) → unit-testbar
      dom/           VNode, Element, ComponentType, Hooks, Reconciler, Scheduler
      dsl/           Tag-Funktionen (div, span, p, button, input, img, item, scroll, …), @DslMarker
      css/           Tokenizer, Parser, Selector, Specificity, Cascade, ComputedStyle, Values/Units, Warnings
      layout/        BoxModel, BlockLayout, FlexLayout, TextLayout (via TextMeasurer-Interface)
      event/         UIEvent-Klassen, Dispatcher (Capture/Target/Bubble), HitTest, FocusManager
      paint/         DisplayList (RectCmd mit Radius/Border, TextCmd, ImageCmd, ItemCmd, Clip push/pop, Opacity)
    fabric/          ← MC-abhängig
      GuiLibScreen   Screen-Adapter (Input → UIEvents, Frame → Update/Layout/Paint)
      render/        DisplayList → GuiGraphicsExtractor + eigene GuiElementRenderStates, Pipelines, Shader
      font/          FreeType-SDF-Glyphatlas, FontManager, § -Codes → Runs, Vanilla-Font-Adapter
      image/         PNG (NativeImage/DynamicTexture), SVG (JSVG → Raster, Cache pro Größe×GUI-Scale), Items
      resources/     Stylesheet-Loader (Resource-Location), Hot-Reload-Watcher
      showcase/      Demo-GUI, Command `/guilib showcase`, `/guilib reload`
  src/main/resources/assets/guilib/
    css/ua.css       User-Agent-Stylesheet (Default-Styles aller Elemente)
    shaders/core/    rounded_rect.{vsh,fsh}, sdf_text.{vsh,fsh}
    fonts/Inter-*.ttf (+ OFL-Lizenz)
  src/test/kotlin/…  CSS-Parser, Selektor/Spezifität, Kaskade, Layout, Reconciler
  docs/ARCHITECTURE.md, README.md, AI_GUIDE.md, llms.txt
```
Einbindung in Mods (später, SBO): `implementation(include("net.sbo:guilib-${mcProject}:<ver>"))` aus mavenLocal;
für schnelle Entwicklung optional Gradle-Composite-Build (`includeBuild("../SBO-GuiLib")`), damit kein Publish-Zyklus nötig ist.

## Markup-/Komponenten-API (Variante A)
```kotlin
data class PartyProps(val id: String, val leader: String, val members: Int)

val PartyCard = component<PartyProps>("PartyCard") { props ->
    var open by useState(false)
    useEffect(props.id) { val t = setInterval(1000) { /* … */ }; onCleanup { t.cancel() } }

    div(className = classNames("card", "open" to open), onClick = { open = !open }) {
        span(className = "title") { +props.leader }
        span(style = "color: var(--muted)") { +"${props.members}/5" }
        if (open) button(className = "btn primary", disabled = props.members >= 5, onClick = { e -> e.stopPropagation(); join(props.id) }) { +"Join" }
    }
}

val App = component("App") {
    val parties by useData { PartyStore.parties }           // später/optional
    scroll(className = "list") {
        for (p in parties) PartyCard(p, key = p.id)
    }
}

GuiLib.open(App, stylesheets = listOf("sbo:ui/partyfinder.css"))
```
- Tags: `div, span, p, h1–h3, button, input, checkbox, select/option, scroll, img, item, tooltip, modal` + `+"text"`.
- Props wie React: `className`, `id`, `style` (CSS-String wie HTML-`style=""`, einmal geparst/gecacht), `key`, `disabled`,
  `onClick/onMouseDown/onMouseUp/onMouseEnter/onMouseLeave/onWheel/onKeyDown/onKeyUp/onInput/onChange/onFocus/onBlur`.
- Hooks: `useState`, `useEffect(deps){…; onCleanup{}}`, `useMemo`, `useRef`, `createContext/useContext`, Helfer `setInterval/setTimeout` (Tick-basiert, auto-cleanup).
- Reconciliation: `setState` markiert Komponente dirty; vor dem nächsten Frame werden **nur dirty Komponenten** neu gerendert,
  Kinder werden nach `(type, key)` gematcht, Element-Baum gepatcht, nur betroffene Teilbäume für Style/Layout dirty markiert.
  Mehrere setState pro Frame werden gebatcht. Alles auf dem Render-Thread; Async-Daten per `GuiLib.runOnUi {}`.

## CSS-System
- `.css`-Dateien in Resources (`GuiLib.stylesheet("modid:path.css")`) + `ua.css` + Inline-`style`. Eigener Tokenizer/Parser (CSS-Syntax-Subset).
- Selektoren MVP: `*`, Tag, `.class`, `#id`, Compound (`button.primary:hover`), Nachfahre (` `), Kind (`>`), Liste (`,`),
  Pseudo: `:hover :active :focus :disabled :checked`. Spezifität (a,b,c), Quellreihenfolge, `!important`, Vererbung wie im Web
  (`color`, `font-*`, `line-height`, `text-align`, `cursor`, `visibility`, Custom Properties).
- **CSS-Variablen** (`--accent`, `var(--accent, #fff)`) direkt im MVP (billig, wichtig fürs Theming).
- Einheiten: `px` (= GUI-Pixel), `%`, `vw/vh`, `em/rem`, `auto`. Farben: `#rgb[a]`, `#rrggbb[aa]`, `rgb()/rgba()`, Named Colors, `transparent`, `currentColor`.
- Properties MVP: `width/height/min-*/max-*`, `padding*`, `margin*` (inkl. `auto`), `border`, `border-width/color/style(solid)`,
  `border-radius` (4 Ecken), `background-color`, `background-image: url()`, `color`, `font-family/size/weight/style`, `line-height`,
  `text-align`, `white-space (normal|nowrap)`, `text-overflow: ellipsis`, `opacity`, `display (block|flex|none)`, `flex-direction`,
  `justify-content`, `align-items`, `align-self`, `gap/row-gap/column-gap`, `flex(-grow/-shrink/-basis)`, `overflow(-x/-y) (visible|hidden|scroll|auto)`,
  `position (static|relative|absolute|fixed)`, `top/left/right/bottom`, `z-index`, `cursor` (→ Hover-Feedback/GLFW-Cursor), `visibility`, `object-fit`.
- Unbekannte Property/Wert/Selektor: **eine** Warnung pro Stelle im Log mit `datei:zeile:spalte`, Regel wird verworfen, kein Crash.
- Style-Neuberechnung nur bei Klassen-/ID-/Pseudo-State-/Baum-/Stylesheet-Änderung für den betroffenen Teilbaum; ComputedStyle gecacht.

## Rendering-Pipeline
1. **Tree**: Komponenten → VNodes → Reconciler patcht den persistenten Element-Baum.
2. **Style**: Selektor-Matching (Regeln nach rechtestem Selektor-Key indiziert) → Kaskade → ComputedStyle (Variablen aufgelöst, Einheiten relativ zu Viewport/Font).
3. **Layout**: Box-Model (content/padding/border/margin, `box-sizing: border-box` als **Default** – bewusste Abweichung, dokumentiert),
   Block-Layout (vertikales Stapeln, Margin-Auto-Zentrierung, kein Margin-Collapsing) und Flexbox nach Spec (Basis, Grow/Shrink-Verteilung,
   min/max-Clamping, Justify/Align, Gap); Text über `TextMeasurer` (Wrap, Ellipsis). Nur bei Dirty-Flags oder Viewport-/GUI-Scale-Änderung.
4. **Paint**: Element-Baum → `DisplayList` (nur bei Änderung neu gebaut; Hover-Wechsel → Restyle nur des Elements), sortiert nach Stacking-Context/z-index.
5. **Backend**: DisplayList → `GuiGraphicsExtractor`: Rechtecke/Borders/Radius über eigene `RenderPipeline` mit SDF-Fragment-Shader als
   `GuiElementRenderState`; Clipping über `enableScissor` (Rechteck-Clip; runde Clips später); Text über SDF-Font-Pipeline oder Vanilla `text()` + `pose()`-Scale;
   Bilder über `blit`, SVG vorher mit JSVG in Textur gerastert (Cache pro Größe × GUI-Scale); Items über `item()`.
6. **Events**: Hit-Test gegen Layout-Boxen (respektiert Clip, z-index, `pointer-events: none`), DOM-artig Capture→Target→Bubble,
   `stopPropagation()`/`preventDefault()`, Fokus-Management (Tab-Navigation), `:hover`/`:active`/`:focus` werden daraus gesetzt, Wheel scrollt nächsten Scroll-Container.

## Fonts (eigenes TTF)
FreeType (LWJGL, über Minecrafts `FreeTypeUtil`, bereits in MC) rastert Glyphen **in der exakten physischen Pixelgröße**
(`font-size × GUI-Scale`, Light-Hinting) in einen Graustufen-Atlas (1024² Seiten, Shelf-Packing). Gezeichnet wird mit der
Vanilla-Pipeline `GUI_TEXTURED` (weiße Glyphen, Alpha = Coverage, Vertex-Farbe färbt), Glyphen auf ganze Pixel gesnappt.
Inter Regular/Medium/SemiBold/Bold/Italic/BoldItalic gebündelt (OFL, `assets/guilib/fonts`). Mods registrieren eigene
Fonts per `FontManager.register(family, weight, italic, "modid:fonts/x.ttf")`.
§-Codes werden in Runs (Farbe/Bold/Italic/Underline/Strikethrough) übersetzt. `font-family: minecraft` nutzt Vanilla-`Font`.
Fehlende Glyphen → Fallback auf Vanilla-Font (pro Zeichen).

> **Änderung gegenüber dem ursprünglichen Vorschlag (SDF-Atlas):** Da Minecraft-GUIs nie frei skaliert/rotiert werden,
> ist Text immer an ganzzahlige physische Pixelgrößen gebunden. Ein Atlas pro Größe ist dort schärfer als SDF (Hinting,
> keine Weichzeichnung bei kleinen Größen) und braucht keinen eigenen Shader. Kerning (GPOS) wird mangels HarfBuzz
> nicht angewendet.

## Basis-Komponenten (alle nur über CSS stylebar, Defaults in `ua.css`)
`button`, `span/p/text`, `input` (Cursor, Selektion, Clipboard, Placeholder, maxLength), `checkbox`, `select` (Dropdown als Overlay-Layer),
`scroll` (div mit `overflow: auto` + Scrollbar), `img` (`src="modid:textures/x.png"` oder `.svg`), `item(stack)`, `tooltip` (Portal, folgt Maus),
`modal` (Portal + Backdrop, Escape schließt).

## MVP vs. später
- **MVP**: alles oben Genannte + CSS-Hot-Reload im Dev-Modus + Showcase + Doku + Tests.
- **Später**: transitions/animations, `@media (gui-scale: n)`, `box-shadow`, Gradients, runde Clips, `flex-wrap`, `:nth-child`/Sibling-Selektoren,
  `calc()`, Grid, `@font-face`, HTTP-Bilder, Markup-Dateien (Variante B/C, erzeugen denselben VNode-Baum), textarea, virtualisierte Listen, HUD-Overlays.

## Umsetzungsschritte (nach jedem Schritt: `./gradlew build` grün, inkl. Tests beider MC-Versionen)
0. **Scaffold**: Repo `SBO-GuiLib` anlegen, Build aus SBO übernehmen (root.gradle.kts, multiversion, libs.versions.toml, Class-Tweaker für `guiRenderState`),
   leeres Client-Entrypoint, `publishToMavenLocal`. `docs/ARCHITECTURE.md` = dieser freigegebene Plan (inkl. getroffener Entscheidungen).
1. **CSS-Core**: Tokenizer, Parser, Werte/Einheiten/Farben, Selektoren, Spezifität, Kaskade, Variablen, Warnungen + Tests.
2. **Layout-Core**: Box-Model, Block, Flexbox, Positionierung, TextMeasurer-Fake + Tests.
3. **DOM/Reconciler/Hooks** + Tests (State-Update rendert nur Teilbaum, keyed Reorder erhält State, Effect-Cleanup).
4. **Fabric-Backend v0**: `GuiLibScreen`, Paint mit Vanilla `fill`/`text`, Events/Fokus/Scroll/Scissor → erste Showcase in `runClient`.
5. **Eigene Pipeline**: SDF-Rounded-Rect + Border-Shader, PNG-Bilder, SVG via JSVG.
6. **TTF**: FreeType-SDF-Atlas, Inter, font-size/weight, §-Codes, Wrap/Ellipsis mit echten Metriken.
7. **Komponenten**: input, checkbox, select, tooltip, modal, `ua.css`.
8. **Hot-Reload** (WatchService auf `src/main/resources` im Dev-Env) + `/guilib reload`.
9. **Doku & Showcase fertig**: README, AI_GUIDE.md + llms.txt (Elemente, Props, Hooks, CSS-Properties/Selektoren, **Abweichungen vom Web**), Abschlussbericht (fertig / fehlt / nächste Schritte).
10. *(Optional, erst nach Rückfrage)* SBO-Build: Dependency auf guilib hinzufügen – ohne Änderungen an der Partyfinder-UI.

Bekannte, dokumentierte Abweichungen vom Web: `px` = GUI-Pixel; `box-sizing: border-box` Default; kein Margin-Collapsing;
kein volles Inline-Formatting (gemischte span/Text-Kinder werden zu einem Rich-Text-Absatz, andere Elemente sind block-/flex-artig);
`overflow: hidden` clippt nur rechteckig; `style` ist ein CSS-String statt JS-Objekt; Events heißen `onClick` usw., Handler sind Kotlin-Lambdas.

## Verifikation
- `./gradlew build` im Library-Repo (kompiliert 26.1.2 + 26.2, führt Unit-Tests aus: CSS-Parser, Selektor/Spezifität, Kaskade/Variablen, Flex-Layout-Fälle, Reconciler).
- Test, der sicherstellt, dass `net.sbo.guilib.core` keine `net.minecraft`-Imports enthält.
- `./gradlew :26.2-fabric:runClient` (und 26.1.2) im Library-Repo → `/guilib showcase`: Hover/Active/Focus-Styles, Klick/Bubbling, Scrollen mit Clipping,
  Texteingabe, Dropdown, Modal, Tooltip, Bilder/SVG/Items, border-radius, Inter-Text scharf bei GUI-Scale 1–4 und Fenster-Resize.
- Hot-Reload: CSS-Datei im Dev-Run ändern → Showcase aktualisiert sich ohne Neustart; fehlerhafte CSS → Warnung mit Zeile, kein Crash.
- SBO-Repo bleibt unverändert (bis optionaler Schritt 10).


## Umsetzungsstand & Entscheidungen während der Umsetzung (2026-09-30)

Umgesetzt: Schritte 0–9 (Scaffold, CSS-Core, Layout, DOM/Reconciler/Hooks, Fabric-Backend, eigene Pipeline + PNG/SVG,
TTF, Controls, Hot-Reload, Doku/Showcase). 97 Unit-Tests; visuell geprüft auf 26.1.2 und 26.2 über `DevAutomation`
(automatische Screenshots). Schritt 10 (SBO-Einbindung) ist offen und wartet auf Rückfrage.

Entscheidungen/Abweichungen gegenüber dem Vorschlag:
- **Paketstruktur:** eine Gradle-Quelle statt getrennter Module; `net.sbo.guilib.core` bleibt MC-frei (per Test erzwungen),
  `net.sbo.guilib.fabric` ist das Backend. Zusätzlich `core/controls` (Input, Select, Tooltip, Modal).
- **Fonts:** Graustufen-Atlas pro physischer Pixelgröße statt SDF (siehe Abschnitt Fonts).
- **Portale & globale Listener:** `portal { }` (wie `createPortal`) rendert in `#guilib-overlay` (letztes Kind von `body`);
  `document.addEventListener` / `useDocumentEvent` für „Klick außerhalb“, Escape usw. Select, Tooltip und Modal nutzen das.
- **Controls:** `input` bekommt intern generierte Kinder (`.guilib-input-text`, `.guilib-caret`, `.guilib-selection`,
  `.guilib-check`), die der Reconciler nicht anfasst (Shadow-DOM-artig). `select` ist eine Komponente, die ein
  `select`-Element rendert. `onChange` feuert bei jeder Änderung (React-Semantik, nicht DOM-`change`).
- **Render-Reihenfolge:** Minecraft sortiert GUI-Quads innerhalb eines Layers nach Scissor/Pipeline/Textur und zeichnet
  Vanilla-Text/Items nach allen Quads. `CommandRenderer` startet deshalb einen neuen Layer (`guiRenderState.up()`),
  sobald sich ein Quad mit anderem Batch-Key überlappt oder nach Vanilla-Text/Items gezeichnet wird.
- **Stacking:** jedes positionierte Element ist eine eigene Paint-Ebene; `z-index` ordnet Geschwister-Ebenen.
- **Hot-Reload:** Polling der Quelldatei unter `src/main/resources` (gefunden über den Mod-Container, nach oben gesucht,
  oder `-Dguilib.resourceDirs`), statt WatchService.
- **Doku-Ort:** Doku liegt im Library-Repo (`README.md`, `llms.txt`, `docs/AI_GUIDE.md`, `docs/ARCHITECTURE.md`) statt
  unter `docs/ui-library/` im SBO-Repo, weil die Library ein eigenes Repo ist.

Vollständige Liste der Web-Abweichungen: `docs/AI_GUIDE.md`, Abschnitt 6.
