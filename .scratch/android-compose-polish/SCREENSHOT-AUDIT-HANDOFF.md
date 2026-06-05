# Android UI — screenshot-driven audit + PWA feature pass (HANDOFF)

> Start here in the **clean session**. An emulator MCP (launch app + screenshot) is being installed.
> This doc is the plan; the per-screen done/deferred detail is in `design-validation-roadmap.md` (same folder).

## Honest status — why this audit is needed
The whole Android shell + design passes were built **without ever seeing the app render** — from
`compose-handoff/prototype/tablet/*.jsx` + CSS + parallel agent reports + the web PWA, verified only that it
**compiles** (`./gradlew :app:compileDebugKotlin` → SUCCESSFUL). Compiling caught none of the real problems the
user sees on device:
- **Scale page is way off.** Cards are **different sizes** (inconsistent padding/height/radius across screens).
- **Missing menus, missing buttons** (rail/section-nav/overflow/search-import-export — several were deferred or stubbed).
- **Entire Settings pages missing** — the two-pane shell exists but only Machine/Brew/Display/Advanced/About are
  partly wired; Water/Sharing/Calibration are "coming soon" stubs, and the design's ~60 rows are mostly absent.
- Generally **unpolished** — spacing/sizing/typography not matched to the design tokens consistently.

Root cause: built blind. **Fix: screenshot-first.** Render the design AND the running app, diff, fix, re-shoot.

## The loop (repeat per screen, and per Settings section)
1. **Build + install**: `./gradlew -p /Users/adrianmaceiras/code/crema/android :app:assembleDebug` then install/launch
   via the emulator MCP. (Quick compile-only check: `:app:compileDebugKotlin`. Both run cargoBuild + UniFFI as deps.)
2. **Navigate** to the screen / settings section via the MCP (taps).
3. **Screenshot** the running screen.
4. **Get the pixel target**: the design is a runnable React prototype. Best path — serve
   `compose-handoff/prototype/tablet/` and open `prototype.jsx`'s host HTML in the **chrome-devtools MCP**
   (already available) at **1280×800**, navigate to the same screen, screenshot it. Side-by-side = the real diff.
   (Fallback: read the screen's `*.jsx` + `screens-v2.css`/`screens.css` + `m3-tokens.css`.)
5. **Diff** structure → spacing → sizing → color/type → present-vs-missing elements. Write the gap list.
6. **Fix** the Compose (use the `compose-expert` skill; map CSS tokens → `MaterialTheme`/`CremaTheme.*`).
7. **Re-build + re-screenshot** to confirm. Don't move on until it matches.

## Resources (all absolute)
- **Design truth**: `/Users/adrianmaceiras/code/compose-handoff/prototype/tablet/` — `brew-screen.jsx`,
  `brew-header.jsx`, `profiles-screen.jsx`, `profile-edit-screen.jsx`, `profile-preview.jsx`, `beans-screen.jsx`,
  `bean-edit-screen.jsx`, `history-screen.jsx`, `scale-screen.jsx`, `settings-screen.jsx`, `m3-components.jsx`,
  `prototype.jsx` + `m3-tokens.css` (tokens), `screens.css` + `screens-v2.css` (**v2 overrides win**).
- **PWA (feature-pass source of truth)**: `/Users/adrianmaceiras/code/crema/web/src` (esp. `lib/components/`, `lib/state/`).
- **Android UI**: `/Users/adrianmaceiras/code/crema/android/app/src/main/java/coffee/crema/ui/`
  (`screens/`, `components/CremaComponents.kt`, `theme/`).
- **Build**: `./gradlew -p /Users/adrianmaceiras/code/crema/android :app:assembleDebug` (NDK + Rust toolchain are set up).
- **Roadmap (per-screen done/deferred)**: `.scratch/android-compose-polish/design-validation-roadmap.md`.
- **Memory**: `android-design-validation.md`, `android-shell-progress.md`, `android-chart-vico.md`.
- **Skill**: `compose-expert` (Modifier hygiene, theming, lists, design-to-compose).

## Screen-by-screen audit targets (design file → suspected gaps to verify on screenshot)
1. **Rail / nav** (`m3-components.jsx` `M3NavigationRail`): all 6 destinations present + correct icons/labels/active
   pill? "Missing menus" may start here. Check width, item spacing, the connection pips.
2. **Brew** (`brew-screen.jsx` + `brew-header.jsx`): twin-block header sizing; the `ModeBannerPill` (NOT built);
   profile/bean tags + freshness dot (deferred); 248dp left col proportions; channel-card sizes; foot layout.
3. **Profiles** (`profiles-screen.jsx` + `profile-preview.jsx`): **card sizing consistency** (user flagged), Fixed(3)
   grid gaps, the 4-up metrics grid, preview wells, the **overflow ⋮ menu** (deferred), search/filter bar (deferred).
4. **Profile editor** (`profile-edit-screen.jsx`): the design is **flat (no section cards)** — I kept `EditorCard`
   wrappers; segment accordion + the **3-up SegFieldCell** compact grid (I used full-width steppers); top-bar.
5. **Scale** (`scale-screen.jsx`) — **user: "way off."** Audit hard: the 132sp hero readout + "grams" placement,
   the tare column widths (372/280 split), dose-helper content (Switch-profile, Add-0.5g, target marker), the
   recent-activity rows. Likely the biggest single rebuild.
6. **History** (`history-screen.jsx`): stats strip (6 tiles in design vs my 4), flat rows + **sparkline** (not built),
   row **stars** column, detail metric **tiles** below chart, 480dp master width.
7. **Beans** (`beans-screen.jsx` + `bean-edit-screen.jsx`): tile anatomy + **Bags/Roasters tabs** + filter/sort
   (deferred), section labels; editor TOC jump-scroll, RoastPicker pip sizing.
8. **Settings** (`settings-screen.jsx`) — **user: "entire missing settings pages."** Biggest gap. The design is 8
   sections × ~60 rows (`set2-*` in `screens-v2.css:500-555`). I built the two-pane nav + 5 partial sections + 3
   stubs. **Build out every section + every row**: Machine hero + diagnostics, Brew-defaults steppers, Water &
   maintenance, Display & units (units segmented), Sharing (Visualizer), Calibration, Advanced (full), About.
   New molecules the design needs: `SetSelect` (pill+caret), `SetStepper` (compact 36dp), `StatusDot`, mono readouts.
9. **QuickControls** (`brew-screen.jsx` QuickControlsContent): design has a **6-up** stepper grid; I built only 3
   brew steppers. Add Steam/Hot-water/Flush cells, preset chips, the mini-pill chart/shot toggles.

## "Cards different sizes" — likely root cause to fix globally
My screens hand-roll `CremaCard`/`Column` paddings ad hoc (14dp here, 16dp there; `Adaptive` vs `Fixed` grids;
varying heights). Do a **consistency pass**: pull card padding/radius/elevation + grid gaps from `screens-v2.css`
into shared constants and apply uniformly. Verify on screenshots that equivalent cards are pixel-identical in size.

## Feature pass — Android vs PWA
The user wants a **feature gap audit** between the Android app and the PWA. Suggested approach: spawn parallel
read-only agents, one per domain (brew, profiles, beans, history, scale, settings, quick-controls, sharing/sync),
each diffing `web/src` features against the Android screens/VM, producing a concrete "PWA has X, Android lacks X"
list with effort + whether it's shell-only. Known-likely gaps: search/filter/sort bars, Beanconqueror **import**,
**Visualizer sync/sharing**, the full Settings sections, steam/water/flush params, the live SAW dial, roasters
management, profile import/export. Decide per item: build now (shell) vs defer (needs FFI/core).

## Hard-won gotchas (don't re-discover)
- **PhIcon/adamglin**: needs `import com.adamglin.phosphoricons.Regular` (weight-group) + per-icon
  `…phosphoricons.regular.X`. Icon names are referenced by string; an unmapped name renders a `?` (silent).
- **`%f` on an Int crashes at render** (`IllegalFormatConversionException`) — compiles fine. `preinfuseSeconds`/
  `maxTotalVolumeMl` are Int, `Bean.roastLevel`/`rating` are UByte. Interpolate Ints; `%f` only on Float/Double.
- **`ExposedDropdownMenu`** is a scope-member extension (don't import standalone); `MenuAnchorType` →
  `ExposedDropdownMenuAnchorType`. `FlowRow` needs `@OptIn(ExperimentalLayoutApi)`.
- **No core changes needed** — every feature so far was shell-reachable; when something looks core-gated, check
  how the PWA does it first (the core model + existing upload/duplicate paths usually cover it).
- `git status` shows `build.gradle.kts` / `gradle-wrapper.properties` / a `Task` file as modified — environmental,
  not app code; stage app files explicitly.

## First moves in the clean session
1. Confirm the emulator MCP tools (ToolSearch), launch the app, screenshot the **rail + Brew** to calibrate.
2. Render the prototype in chrome-devtools at 1280×800 for the side-by-side target.
3. Start with **Scale** and **Settings** (the two worst per the user), screenshot-diff-fix-reshoot each.
4. Then sweep the rest in rail order; finish with the PWA feature-pass audit.
