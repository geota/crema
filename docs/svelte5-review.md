# Svelte 5 idiomatic-correctness review — `web/`

Reviewer pass: read-only. Targets the runes-era idiom layer, not the
domain logic. Where the recommendation involves a code change, the
shape of the patch has been validated with the official Svelte MCP
`svelte-autofixer` against a synthetic minimal example or against the
existing file.

The codebase is already in good shape: zero `svelte/store` imports,
zero `$:` / `on:click` / `export let` / `<slot>` / `$$props` /
`<svelte:component>` leftovers, every `{#each}` is keyed, snippets are
used everywhere a slot would have been, singleton `$state`-classes
replace the old `writable()` pattern. The findings below are the
sharp-edged remainder.

---

## 1. Runes hygiene

### 1.1 The whole `UiSnapshot` is a proxy when it never needs to be

- **Location:** `web/src/lib/state/ui-state.svelte.ts:1063-1075`
- **Issue:** `CremaUiState.current = $state<UiSnapshot>(INITIAL_SNAPSHOT)`
  holds a snapshot that is **always replaced by a fresh object** —
  every fold in `applyEvent` (and every `patch()` / `log()` call) does
  `this.current = { ...this.current, … }`. Nothing in the codebase
  mutates a `UiSnapshot` field in place; every consumer reads
  `state.current.someField`, never `state.current.someField = …`.
  This is textbook `$state.raw` territory — the snapshot has ~50 fields
  plus a 2000-element `shotTelemetry` array, all wrapped in a
  recursive proxy that none of the writes go through. At ~25 Hz
  telemetry, every sample currently allocates a fresh proxy chain for
  the 4-channel `TelemetrySample` *and* re-proxies the whole snapshot.
- **Fix:** change one line —

  ```ts
  // ui-state.svelte.ts:1065
  current = $state.raw<UiSnapshot>(INITIAL_SNAPSHOT);
  ```

  `applyEvent`, `patch` and `log` already produce new objects, so no
  caller needs to change. This is the single highest-impact change
  in the whole review.

### 1.2 `shotTelemetry` array proxy is recreated 25 Hz

- **Location:** `web/src/lib/state/ui-state.svelte.ts:704-711` (the
  `Telemetry` case in `applyEvent`).
- **Issue:** even with the fix above, `shotTelemetry` is itself an
  `readonly TelemetrySample[]` rebuilt by `[...prev, sample].slice(-MAX)`
  every sample. If `current` stays as a proxied `$state`, each rebuild
  re-proxies the new array (up to 2000 entries) — pure overhead
  because no UI element mutates a sample, they spread + reassign at
  the snapshot level. Once 1.1 is applied this disappears (`$state.raw`
  does not deep-proxy), so 1.1 fixes 1.2.
- **Fix:** subsumed by 1.1.

### 1.3 Store classes hold a `$state` of an always-replaced bundle

- **Location:**
  - `web/src/lib/settings/store.svelte.ts:215`
  - `web/src/lib/profiles/store.svelte.ts:68-85` (five fields)
  - `web/src/lib/history/store.svelte.ts:62`
  - `web/src/lib/bean/store.svelte.ts:27`
- **Issue:** same pattern as 1.1 at smaller scale. `HistoryStore.shots`
  is replaced via spread on every `setNotes`, `setRating`, `record`,
  `addImported`, `delete`. `ProfileStore.builtins` / `.custom` /
  `.overrides` are replaced wholesale. `SettingsStore.current` is
  replaced wholesale on every `set()`. None of them mutate; all are
  candidates for `$state.raw`.

  `StoredShot.series` and a `CremaProfile.segments` are arrays of small
  objects — the per-shot history payload is the biggest by mass (a few
  MB / 300-record cap). Proxying that on every store-load is wasted
  work that vanishes with `$state.raw`.
- **Fix:**

  ```ts
  // history/store.svelte.ts:62
  private shots = $state.raw<StoredShot[]>(readJson<StoredShot[]>(HISTORY_KEY, []));

  // settings/store.svelte.ts:215
  current = $state.raw<Settings>({ ...DEFAULT_SETTINGS, ...readJson(...) });

  // profiles/store.svelte.ts:68-74
  private builtins = $state.raw<CremaProfile[]>([]);
  private custom = $state.raw<CremaProfile[]>(readJson(...));
  private overrides = $state.raw<Record<string, BuiltinOverride>>(readJson(...));

  // bean/store.svelte.ts:27
  private bean = $state.raw<Bean>(migrateBean(...));
  ```

  Leave `ProfileStore.activeId` (a `string | null` scalar — proxy is
  free) and `ProfileStore.loaded` (a `boolean`) as plain `$state`.

### 1.4 `$effect` to flip-flop `modeStartedAtMs` + 250 ms timer

- **Location:** `web/src/lib/components/brew/BrewDashboard.svelte:131-148`.
- **Issue:** this is the only `$effect` in the dashboard that *writes
  state* the same effect reads — `modeStartedAtMs` is read on entry,
  reassigned on the first tick, and read again to drive the interval.
  Autofixer flags it. The pattern is fragile (a `modeState` toggle
  during the same tick can reset the timer mid-flight) and mixes two
  concerns: "anchor a start time" and "schedule a 4-Hz repaint clock".
- **Fix:** separate the two:

  ```ts
  // Reactive: when modeState changes, derive a new anchor (or null).
  const modeStartedAtMs = $derived(modeState === 'idle' ? null : performance.now());

  // Pure side-effect: tick a 250 ms clock only while active.
  let modeNowMs = $state(0);
  $effect(() => {
    if (modeState === 'idle') return;
    modeNowMs = performance.now();
    const id = window.setInterval(() => (modeNowMs = performance.now()), 250);
    return () => window.clearInterval(id);
  });
  ```

  Note: the `$derived(performance.now())` is technically a
  side-effect — Svelte will memoize per-dep-change, which is exactly
  the intent (one anchor per mode transition). If you'd rather make
  that explicit, `$derived.by(() => modeState === 'idle' ? null : performance.now())`
  is equivalent and reads as deliberate.

### 1.5 Subscribe-only effect in `FavoritesStrip`

- **Location:** `web/src/lib/components/brew/FavoritesStrip.svelte:86-89`.
- **Issue:**

  ```ts
  $effect(() => {
      void items.length;
      syncArrows();
  });
  ```

  This is "re-measure arrow-affordance state when the list length
  changes". The `void items.length` is the explicit dep-tracking line.
  Effectively this `$effect` *is* state computation derived from
  `items.length` + DOM measurements, but DOM measurements are why
  `$derived` can't quite express it. Acceptable, but the comment
  pre-empts the autofixer warning and is correct.
- **Fix:** none required. Optional polish: lift the body into a
  `function refreshArrowAffordance()` and call it both from the
  attachment and the effect, so the effect body becomes a one-liner
  the reader can recognise without reading the comment.

### 1.6 `untrack`-wrapped chart effects — well-justified, leave them

- **Locations:** `LiveChart.svelte:403-462`, `StaticShotChart.svelte:196-239`,
  `ProfileCurveEditor.svelte:316-368`, `ProfilePreview.svelte:201-259`.
- **Issue:** none — `untrack` is the only way to keep "create chart
  once" / "rebuild on theme flip" / "push data on series change" as
  three separate effects without tearing the uPlot instance down on
  every settings tweak. The comments document each call. Autofixer
  generically flags any `untrack` call as "maybe move to `$derived`",
  which is wrong here (DOM-attached imperative state).
- **Fix:** none.

### 1.7 `bind:this` for chart container elements — could be attachments

- **Locations:** as 1.6 plus `FavoritesStrip.svelte:65-81` (already uses
  an attachment for the scroll container).
- **Issue:** autofixer suggests converting `bind:this={plotEl}` +
  imperative effect to a Svelte 5 `{@attach}` attachment. The chart
  components do not benefit much — they need both element reference
  *and* multiple effects keyed off props (theme, series, data, segments)
  — but `FavoritesStrip` shows the cleaner pattern (a one-shot capture
  attachment + a separate `$effect` for the list-dependent re-measure).
- **Fix:** **don't change** for the chart components — the current
  structure has clear separation of create-once vs feed-data-live
  effects and is already battle-tested. Note for future surfaces.

---

## 2. Props

### 2.1 Callback props instead of `$bindable()` — consistent and correct

- Pattern across `StStepper`, `StToggle`, `StSelect`, `QStepper`,
  `PeNumber`, `TagInput`, every Setting section, `ProfileEditor`,
  `ShotDetail`, `BrewDashboard`: `onChange` / `onCommit` callback
  props, value passed in as a plain prop, parent owns the state.
- **No** `$bindable()` anywhere in the codebase. Given that the
  parent always owns persistence (settings store, profile store, draft
  state) this is the right call. `$bindable` would force the parent
  to wire up a writable target where it currently has a one-liner
  callback that often does more than just assign (clamping, dispatch
  to the orchestrator, optimistic-update + remote-write). Leave it.

### 2.2 Snippets where slots used to be

- `StRow` exposes `control` and `hint` snippet props; `StGroup`
  renders `children`; every settings section uses them. Pattern
  is correct and uniform. No `<slot>` anywhere.

---

## 3. Stores — singleton classes vs `svelte/store`

- **Audit:** grep of `src/` shows zero `from 'svelte/store'` imports,
  zero `writable(`, zero `readable(`, zero `derived(` (the rune
  primitive, not the store factory).
- Every shared store is a `*Store` class with a module-level singleton
  via `get*Store()`. `SettingsStore`, `ProfileStore`, `HistoryStore`,
  `BeanStore`, `MaintenanceStore`, `CaptureStore`, plus the
  `ThemeSignal` (module-level `export const theme = new ThemeSignal()`).
- **Verdict:** consistent, idiomatic, modern. No action.

---

## 4. Performance

### 4.1 `shotTelemetry` chart series — covered above

See 1.1 / 1.2. The single change `current = $state.raw` removes the
hottest proxy-overhead path in the app.

### 4.2 `Set<string>` held in `$state`

- **Location:** `web/src/lib/profiles/store.svelte.ts:81-83, 211-217, 232-236`.
- **Issue:** `hiddenBuiltins = $state<Set<string>>(new Set(…))` works
  because every mutation **reassigns** the field with a fresh `Set`.
  Svelte 5 ships a reactive `SvelteSet` (from `svelte/reactivity`) that
  notifies on `.add` / `.delete`, which would allow direct mutation
  and avoid the `new Set([...old, x])` realloc. Autofixer flags this.
- **Fix:** swap once, then mutate in place:

  ```ts
  import { SvelteSet } from 'svelte/reactivity';
  private hiddenBuiltins = new SvelteSet<string>(readJson<string[]>(HIDDEN_BUILTINS_KEY, []));
  // delete:
  this.hiddenBuiltins.add(id);
  this.persistHiddenBuiltins();
  // unhide:
  this.hiddenBuiltins.delete(id);
  this.persistHiddenBuiltins();
  ```

  Minor; small data, low frequency. Polish, not perf.

### 4.3 `BrewDashboard.LiveChart` props — re-creates `series` reference

- **Location:** `BrewDashboard.svelte:550-555` passes
  `series={ui.shotTelemetry}` directly.
- **Issue:** with 1.1 applied, `ui.shotTelemetry` is the same
  reference until `applyEvent` replaces the parent snapshot. The
  `LiveChart` `$derived(toData(series))` re-derives correctly. No
  action.

### 4.4 Inline arrow handlers in `{#each}` rows

- **Location:** `BrewDashboard.svelte:617, 626, 635` etc., plus
  `SegmentRow` in `ProfileEditor`.
- **Issue:** non-issue. Svelte 5's runes-mode compiler hoists static
  template structure; the per-row arrow is cheap. Stop chasing this
  unless a profile shows it.

---

## 5. Module-load side effects

### 5.1 `theme.svelte.ts` — `MutationObserver` at module init

- **Location:** `web/src/lib/theme.svelte.ts:37`.
- **Issue:** `export const theme = new ThemeSignal()` runs at module
  load and the constructor calls `new MutationObserver(…)` against
  `document.documentElement`. The class guards both reads with
  `typeof document === 'undefined'`, so SSR works in principle; given
  `ssr=false` it's moot anyway, but the guard is correct and harmless.
- **Verdict:** OK. The pattern is the minimum viable
  "reactive-on-DOM-attribute-flip" signal.

### 5.2 `getCremaAppContext()` document listeners at construction

- **Location:** `web/src/lib/state/app.svelte.ts:1005-1018`.
- **Issue:** the `createCremaApp` function (called from the layout's
  `onMount`) attaches `pointerdown` + `keydown` listeners directly to
  `document`. There's no cleanup — the app lives for the page
  lifetime, so this is fine, but worth a comment that the listeners
  are deliberately leaked because the orchestrator is a singleton.
- **Fix:** none required. Optional one-line comment.

### 5.3 `getSettingsStore()` paints theme from constructor

- **Location:** `settings/store.svelte.ts:223`.
- **Issue:** `applyTheme(this.current.theme)` runs on first store
  construction (i.e. first import on any route). That construction is
  triggered lazily by route code, not module-load — so this is fine.

---

## 6. Migration leftovers

- `$:` reactive statements: **none**.
- `on:click` / `on:input` / `on:*` directives: **none**.
- `export let`, `$$props`, `$$restProps`: **none**.
- `<slot>` / `<svelte:fragment>` / `$$slots`: **none**.
- `<svelte:component this={…}>`: **none**.
- `<svelte:self>`: **none**.
- `use:action`: **none** (replaced by `{@attach}` in `FavoritesStrip`).
- `class:foo={cond}` directives: still used in many places; this is
  *not* deprecated in Svelte 5 — only the docs' "prefer clsx-style
  arrays" is a polish suggestion, not a migration debt. Leave.

The migration is fully done.

---

## 7. SvelteKit 2 idiom

### 7.1 `goto(...)` without `resolve()`

- **Locations:** `ProfileEditor.svelte:231, 254`, `CremaSidebar.svelte:77`.
- **Issue:** SvelteKit 2's `goto` recommends route resolution via
  `resolve('/profiles')` so the route id (not the literal path) is
  what the call commits to. Today `void goto('/profiles')` works but
  autofixer flags it.
- **Fix:**

  ```ts
  import { resolve } from '$app/paths'; // or '$app/state' helper
  void goto(resolve('/profiles'));
  ```

  Minor — Crema's routes are all literal (`/`, `/profiles`,
  `/history`, `/scale`, `/settings`), no params. The resolve()
  pattern matters more for param-bearing routes. Apply when convenient.

### 7.2 `href` on the sidebar nav

- **Location:** `CremaSidebar.svelte:93`.
- **Issue:** autofixer flags `href={it.href}` for the same reason —
  prefer `resolve()`-derived href.
- **Fix:** same as 7.1.

---

## 8. One real-world bug I noticed in passing

Out of scope for this review, but flagging because it's right next to
the code I read: `BrewDashboard.svelte:131-148` resets `modeStartedAtMs`
to `null` inside the effect on every `modeState === 'idle'` entry —
including the *initial* render with no mode active. Harmless today, but
if a mode transition fires the effect re-entry before the cleanup
returns (it shouldn't, but the data flow is fragile), the timer could
double up. The 1.4 refactor closes this. Just noting.

---

## Top 5 changes that would have real impact

1. **`$state.raw` for `CremaUiState.current`**
   (`ui-state.svelte.ts:1065`). One line, removes the recursive
   proxy off the hottest object in the app. With ~25 Hz telemetry
   and a ~50-field, multi-thousand-element snapshot, this is the
   only change in this review that is plausibly *measurable*. Net
   diff: 4 characters. Risk: ~zero — every existing write path
   already produces a fresh object.

2. **`$state.raw` for the four store bundles** —
   `HistoryStore.shots`, `SettingsStore.current`,
   `ProfileStore.builtins` / `.custom` / `.overrides`,
   `BeanStore.bean`. Same one-line pattern. Same risk profile. The
   history store especially: a 300-shot, multi-megabyte payload that
   is currently deeply-proxied for no reason.

3. **Refactor the `modeStartedAtMs` effect in `BrewDashboard`**
   (split anchor into `$derived`, keep the 250 ms tick as a small
   `$effect`). The current effect both reads and writes its own state
   and is the only autofixer-flagged correctness issue in the
   component tree. Three-line patch.

4. **Adopt `SvelteSet` for `ProfileStore.hiddenBuiltins`**. Removes
   one rebuild allocation per hide/unhide and reads more cleanly than
   `new Set([...old, x])`. Pure polish but the diff is small.

5. **`resolve()` the routes in `goto` / `<a href>`**. Five call sites
   across `ProfileEditor` + `CremaSidebar`. Future-proofs for
   parametric routes; matches SvelteKit 2 idiom; flagged by autofixer.

Everything else in this codebase is already idiomatic Svelte 5. The
runes hygiene, prop conventions, snippet adoption and store pattern
are uniformly modern. Nothing here is *wrong*; what's listed is the
delta between "modern Svelte 5" and "perf-tuned modern Svelte 5".
