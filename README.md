# GuiLib

> **AI disclaimer:** GuiLib was developed with substantial help from an AI coding assistant (Claude by Anthropic).
> The code is reviewed, tested (unit tests + in-game checks on both supported Minecraft versions) and maintained by
> the SkyblockOverhaul team, but please report anything that looks off.

A UI library for Minecraft Fabric mods that works like web development:
**React-style components and hooks in a Kotlin DSL with HTML tag names, styled with real `.css` files.**

```kotlin
val Counter = component("Counter") {
    var count by useState(0)
    div(className = "card") {
        span(className = "label") { +"Clicked $count times" }
        button(className = "primary", onClick = { count++ }) { +"Click me" }
    }
}

GuiLib.open(Counter, stylesheets = listOf("mymod:ui/counter.css"))
```

```css
/* src/main/resources/assets/mymod/ui/counter.css */
body { display: flex; align-items: center; justify-content: center; }
.card { display: flex; gap: 6px; align-items: center; padding: 8px 10px; background-color: #1e1f22; border-radius: 6px; }
.primary { background-color: #5b8def; }
.primary:hover { background-color: #6f9cf2; }
```

Why: developers and AI models already know HTML, React and CSS extremely well. GuiLib keeps their names and behaviour
wherever possible (`padding`, `justify-content`, `:hover`, `useState`, `onClick`, `className`, keys, bubbling, …), so
UIs can be written quickly and correctly. Differences from the web are documented in [docs/AI_GUIDE.md](docs/AI_GUIDE.md#6-differences-from-the-web-read-this).

## Features

- **Components & hooks:** function components with props, `useState`, `useEffect` (+ cleanup, timers), `useMemo`,
  `useRef`, context, keyed list reconciliation, batched re-rendering of only the changed components.
- **CSS:** stylesheets from resources + inline `style` strings; tag/class/id/attribute selectors, combinators,
  `:hover :active :focus :disabled :checked :not()` …; specificity, `!important`, inheritance, custom properties
  (`var(--accent)`), `px % em rem vw vh`. Unknown properties/values log a warning with file and line — no crashes.
- **Layout:** box model, block flow, inline text, **flexbox** (grow/shrink/basis, wrap, gap, alignment, auto margins,
  order), relative/absolute/fixed positioning, `z-index`, scroll containers with clipping and scrollbars.
- **Rendering:** own anti-aliased SDF shader for `border-radius` and borders, **TTF text** (Inter bundled; FreeType,
  pixel-exact at every GUI scale) with wrapping, ellipsis and Minecraft `§` codes, the vanilla font via
  `font-family: minecraft`, PNG and **SVG** images, item icons, opacity.
- **Events like the DOM:** click/dblclick/contextmenu, mouse enter/leave/move, wheel, keys, focus/blur, bubbling,
  `stopPropagation()`, `preventDefault()`, Tab navigation, `document`-level listeners.
- **Controls:** text/password/number inputs (caret, selection, clipboard), checkbox, select dropdown, tooltip,
  `title` tooltips, modal dialogs, portals — all styleable with CSS.
- **Dev workflow:** CSS hot reload from `src/main/resources`, `/guilib showcase`, `/guilib reload`.

## Using it in a mod

GuiLib is a Fabric mod published as `net.sbo:guilib-<mc>-fabric` (currently `26.1.2-fabric` and `26.2-fabric`)
to the SkyblockOverhaul Maven repository.

```kotlin
// your mod's build.gradle.kts
repositories {
    exclusiveContent {
        forRepository { maven("https://skyblockoverhaul.github.io/maven") }
        filter { includeGroup("net.sbo") }
    }
}
dependencies {
    implementation(include("net.sbo:guilib-26.2-fabric:0.1.0")!!) // jar-in-jar, like Elementa
}
```

and add `"guilib": ">=0.1.0"` to `depends` in your `fabric.mod.json`.

While working on GuiLib and a mod at the same time, use a Gradle composite build instead
(`includeBuild("../SBO-GuiLib")` with a dependency substitution, see SBO's `settings.gradle.kts`), or
`./gradlew publishToMavenLocal` + `mavenLocal()`.

Releasing a new version is described in [docs/PUBLISHING.md](docs/PUBLISHING.md).

## Documentation

- [docs/AI_GUIDE.md](docs/AI_GUIDE.md) — complete reference: elements, props, hooks, events, CSS, web differences, recipes.
- [llms.txt](llms.txt) — short entry point for AI tools.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — design and rendering pipeline (German).
- Showcase: [Showcase.kt](src/main/kotlin/net/sbo/guilib/fabric/showcase/Showcase.kt), [showcase.css](src/main/resources/assets/guilib/showcase/showcase.css).

## Development

```bash
./gradlew build                     # both MC versions + unit tests
./gradlew :26.2-fabric:runClient    # then /guilib showcase
```

- `src/main/kotlin/net/sbo/guilib/core` is pure Kotlin (no Minecraft imports, enforced by a test): CSS engine,
  layout, DOM/reconciler/hooks, events, painter, controls. Unit tests live in `src/test/kotlin`.
- `src/main/kotlin/net/sbo/guilib/fabric` is the Minecraft backend: screen, render pipelines, fonts, images, resources.
- Version differences use the Deftu preprocessor (`//#if MC >= 26.2`); `src/` targets 26.1.2.
- Tag functions are generated: edit `scripts/gen_tags.py`, run `python scripts/gen_tags.py`.
- Visual checks without clicking: `./gradlew :26.2-fabric:runClient -Pguilib.dev.shots=all` opens every showcase
  section, saves screenshots to `versions/26.2-fabric/run/screenshots/` and quits
  (see `DevAutomation.kt` for hover/click/type scripts).

## License

GuiLib is licensed under the **GNU Lesser General Public License v3.0** ([COPYING.LESSER](COPYING.LESSER), which
builds on the GPL v3 in [COPYING](COPYING)). You may use GuiLib in mods under any license, including closed-source ones;
changes to GuiLib itself must be published under the LGPL.

Bundled third-party components:
- Inter font — SIL Open Font License 1.1 (`src/main/resources/assets/guilib/fonts/inter-license.txt`)
- JSVG (bundled jar-in-jar) — MIT License
