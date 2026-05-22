# Design critique — Crema web shell

**Scope.** A read-only design / UX review of `web/src/`. Focus: token discipline,
hierarchy, consistency, density, empty/loading/error states, brew-dashboard
cognitive load, and a11y worsenings beyond `docs/12-accessibility.md`.

## Bottom line

The craft is **above average for an in-house design system** and noticeably
above generic AI-template output: the type roles, telemetry palette, copper
accent, and the chart's hand-built two-axis scale all read as deliberate. What
holds it back is **token-vs-handoff drift**. The token vocabulary in
`web/src/styles/tokens.css` is well-thought-out, but the imported handoff
stylesheets (`web-kit.css`, `quick-controls*.css`, `*-page.css`) bypass it: 311
raw `px` values in `quick-controls-v2.css` alone, zero uses of `--space-*`
anywhere except the layout shell, two different "danger reds," and one
hard-broken token (`--success-rgb`). Once that token wiring is finished, the
existing visuals will read as a coherent system instead of as a hand-off
faithfully traced into code. The second-biggest issue is **brew-dashboard
density** — every signal the screen has is on it simultaneously, with two
representations of phase, two representations of weight, two representations of
group temperature, and sub-13 px typography in the bottom third of the screen
that you can't actually read at arm's length under a kitchen pendant.

## What's working

- **Type system** (`tokens.css:264-397`). The `.t-display / t-h1 / t-h2 /
  t-page-title / t-eyebrow / t-readout` roles are well-considered: tabular
  numerics + `feature-settings: "tnum", "zero"` on every readout is exactly
  right for a telemetry surface; the eyebrow / page-title pairing gives every
  route a consistent header rhythm.
- **Brand palette restraint** (`tokens.css:32-86`). One copper accent, four
  telemetry hues that are perceptually distinct without being garish, and a
  warm-near-black "espresso" ramp instead of pure black is the right call for a
  warm-light kitchen surface. It will not date.
- **Hand-rolled LiveChart** (`lib/components/brew/LiveChart.svelte`). The
  one-shared-y-scale-two-axes trick (lines 9-23, 287-329) is genuinely clever:
  pressure + flow plot at native value, temp + weight at /10, the right axis
  labels at ×10. Adding the dashed live-setpoint overlays alongside the goal
  curve (lines 364-391) is the kind of detail a $3K-machine UI should have. The
  marker plugin + per-channel end-dot in `markerPlugin()` is restrained and
  legible.
- **Honest empty states** in the leaf routes:
  `history/+page.svelte:364-392` (no shots), `FavoritesStrip.svelte:135-143`
  (no pinned), `profiles/+page.svelte:678-684` (no match). These are
  first-class designs, not afterthoughts.
- **Settings disciplined vocabulary**
  (`lib/components/settings/{StGroup,StRow,StStepper,StToggle,StStatusDot,
  StValueChip,StSegment}.svelte`). The most internally consistent surface in
  the app — `BrewDefaultsSection.svelte` reads like real production design.

## What needs work

### Token discipline (cross-cutting)

- **Spacing tokens are dead code.** `--space-0..24` are defined in
  `tokens.css:166-181`. They are used in **two** files in the whole app
  (`routes/+layout.svelte` + `lib/shell/PlaceholderPanel.svelte`). Every other
  surface inlines `px`. `quick-controls-v2.css` alone has 311 raw `px`
  literals; `settings-page.css` has 51. Decide: either kill the tokens, or do a
  one-time sweep to map the handoff CSS onto them.
- **`--success-rgb` referenced but not defined.**
  `routes/profiles/+page.svelte:1036-1038` and
  `routes/history/+page.svelte:798-800` both write
  `rgba(var(--success-rgb), 0.08)`. `tokens.css:66-86` defines only
  `--danger-rgb`. The rgba() falls back to invalid and the banner backgrounds
  silently render transparent. Add `--success-rgb: 92, 138, 76;` (and
  `--warning-rgb`, `--info-rgb` while you're there).
- **Two reds in production.** `--danger` is `#B84A3A` (rgb 184, 74, 58) in
  `tokens.css:85` but `.st-btn-danger` uses raw `rgba(167, 64, 64, …)` in
  `styles/settings-page.css:254-259` and `routes/settings/+page.svelte:438-443`.
  Same for the danger backgrounds. Pick one.
- **mode-controls.css is dark-only by accident.**
  `styles/mode-controls.css` has 15 raw `rgba(244, 237, 224, X)` calls (the
  dark-mode tint base) instead of `rgba(var(--tint-rgb), X)`
  (`:56,:71,:84,:110,:147,:186,:228` and more). The mode chips will *not* flip
  when the user picks light theme — they will stay light-on-light.
- **Defined-but-unused `--shadow-focus`.** `tokens.css:206`. Nothing in the
  codebase reads it. Already in `docs/12-accessibility.md`; flagged here only
  to note that adding `:focus-visible { box-shadow: var(--shadow-focus); }`
  globally is the single highest-ROI a11y change.
- **Inline SVG fills with raw hex.** `MachineSection.svelte:235,244,250` use
  `#3a2a1d`, `#0d0907`, `rgba(0,0,0,0.4)` for the machine illustration. Tiny
  but visible if you ever add a light-theme test.

### Brew (cross-cutting)

- **Three steppers, one job.** `QStepper.svelte` (dark .qcs*),
  `StStepper.svelte` (light .st-*), `PeNumber.svelte` (profile editor .pe-*).
  All three implement: −/value/+ row, click-to-type, blur/Enter commits,
  Esc cancels (compare `QStepper.svelte:60-89` ≈ `StStepper.svelte:55-99` ≈
  `PeNumber.svelte:42-69`). The visual treatments differ; the *logic* is
  copied three times with subtle drift (e.g. `QStepper` uses
  `step < 1 ? toFixed(1) : toFixed(0)` for default fmt;
  `StStepper.svelte:60` takes explicit `decimals`). Collapse to one component
  with size + tone variants; the design difference is `.qcs-btn`'s 30 px dark
  vs `.st-stepper-btn`'s 24 px light vs `.pe-num-btn`'s 28 px — three sizes
  none of which hit the 44 px tablet touch target.
- **Two button vocabularies.** `.crema-btn-*` (`web-kit.css:73-93`) and
  `.st-btn-*` (`settings-page.css:231-259`). Metrics are nearly identical
  (`.crema-btn-sm` is 12 px / `6px 12px`; `.st-btn` is 12 px / `7px 12px`).
  Two near-twins with slightly different paddings reading as two systems.
- **Four status-dot vocabularies.** `cside-status-dot`
  (`quick-controls-v2.css:584`), `sc-dot` (`routes/scale/+page.svelte:636`),
  `crema-bean-rest-dot` (`web-kit.css:334`), `st-statusdot-d`
  (`routes/settings/+page.svelte:490-500`). Same idea, four implementations,
  three of them with the same `box-shadow: 0 0 0 2.5px rgba(107, 140, 95, 0.2)`
  glow hard-coded.

### Brew dashboard (the most-touched surface)

- **The screen is doing too much at once.** Top to bottom on
  `BrewDashboard.svelte:396-651` the user sees, simultaneously, while a shot is
  pulling: profile name + meta line + sync chip + 3 buttons (head), live mode
  pill (when active), big timer + phase label, Yield / Ratio / Volume target
  cards, Phase indicator card with overall + per-segment bars, Bean card,
  Last-shot card (after the shot ends), 4-channel readouts, live chart with
  goal + setpoint dashed overlays, foot scale cluster, foot Group temp, foot
  Steam temp, foot Water tank, 3 mode chips, big Start button. The chart is
  the right primary; almost everything else is competing.
- **Phase is shown three times.** Timer's `step` caption
  (`BrewDashboard.svelte:465` → `ExtractionTimer.svelte:41`), the entire
  `PhaseIndicatorCard` (lines 121-152: overall pills + per-row list), and the
  goal-curve segment in the chart. Pick one canonical surface for phase —
  recommend keeping the timer's phase caption + the chart's goal line and
  retiring `PhaseIndicatorCard` from the resting dashboard (or moving it into
  the QuickSheet).
- **Group temperature is shown twice.** TEMP readout
  (`BrewDashboard.svelte:530-536`) and `Group` in the foot meta (lines
  587-589) — both live, same source. Same for **weight** (WEIGHT readout
  line 537-543 and the foot scale cluster line 570-576).
- **4-channel readouts are sized below the headroom they're given.**
  `web-kit.css:393` sets `.crema-readout-num` to `font-size: 28px`. Above each
  is a 10 px label (line 391). The timer panel next to them is 76 px. Either
  shrink the timer to claw back room for ~40 px readouts, or accept that the
  readouts are secondary glance-info — but right now their visual weight does
  not match their actual importance during a pour.
- **Bottom-left clutter is sub-13 px.** `LastShotCard.svelte:90,102` —
  value `15px`, label `8.5px`; `BeanContextCard.svelte:233,257,280` —
  10 / 12 / 11 px; `crema-foot-meta` (`web-kit.css:475`) — `13px`.
  Three small cards stacked plus an 11 px footer means the bottom third of
  the screen is unreadable at arm's length under kitchen light. Either make
  these cards the primary content during idle (and bigger), or move them
  off-canvas (Quick Sheet).
- **No "DE1 not connected" treatment.** `BrewDashboard.svelte` reads
  `ui.de1State` only at line 81 (`modeReady`) to gate the service-mode chips.
  The big Start button (`:643-649`) is always copper, never disabled, and
  flips local UI state regardless of connection. With no DE1 the screen
  renders normally with `—` everywhere — there is no explicit "Connect your
  DE1" empty state in the most important screen of the app. Compare
  `routes/history/+page.svelte:364-392` (excellent) and
  `routes/scale/+page.svelte:341-343` (acceptable).
- **Touch targets are below 44 px on the only screen the user touches
  mid-pour.** `.qcs-btn` is 30 px in the six-up layout
  (`quick-controls-v2.css:1034`); `.mc-chip` is 52 px (good); the big Start
  button is ~60 px tall (good); but every Quick Sheet stepper button is 30 px
  with 4 px gap — fine on a desktop, hostile on a wet finger.
- **Header right-cluster crowds.** `BrewDashboard.svelte:443-459` puts the
  Quick Controls pill, Edit, Switch profile in the same row as a
  potentially-active mode pill (line 433). Edit and Switch profile are stubs
  (the `// TODO: wire to DE1 control` at line 450 admits this); they're three
  buttons users will press and nothing will happen. Either hide stubs or
  ship-or-cut.

### History

- **Empty state — strong.** `routes/history/+page.svelte:364-392`. Glyph + serif
  title + sub + a single contextual CTA. Use this as the pattern for the rest
  of the app. The "Select a shot to see its detail" empty side pane (line 495)
  is acceptable but could earn a glyph too.
- **Detail pane has no loading state.** `ShotDetail.svelte` mounts and renders
  the SVG immediately — if `StaticShotChart`'s expensive curve path ever
  becomes async (e.g. when raw capture loads from IDB for the export), it'll
  flash blank. Worth pre-empting with a skeleton.
- **Star rating is also small.** `ShotRow.svelte:201` — `font-size: 11px;`
  + `letter-spacing: -1px`. The stars are the most affordance-rich glyph in
  the row; they read as decorative. Lift to 14 px and let the row breathe.
- **9 px metric labels in the row.** `ShotRow.svelte:194`. At standard tablet
  DPR they render below the legible floor.

### Profiles

- **ProfileCard's 4-up metric grid is the fourth way the app shows brew
  params.** `ProfileCard.svelte:133-150` (Ratio / Dose / Temp / Pre-inf in a
  mini-grid) vs the brew header meta-line, the brew target cards, the profile
  editor's `PeNumber` grid, the settings rows. None of them are wrong; they
  just aren't recognizable as the same thing across surfaces. Unify on one
  layout for "the four numbers that define a recipe" and reuse it everywhere.
- **No profile-library empty state for first-launch.**
  `routes/profiles/+page.svelte:678-684` only renders "no profiles match the
  current filters" — it requires `store.loaded && filtered.length === 0`. The
  store loads built-ins from the core so it likely never hits this empty path
  unless the user filters everything out. Worth verifying that built-in load
  always populates, otherwise add a true empty.
- **Import banner colors silently broken.** `:1036-1038` references
  `--success-rgb` (see "Token discipline"). The success banner background and
  border render as `rgba(, 0.08)` → invalid → ignored → transparent. The
  text foreground (line 1038 same var) renders fully transparent. Test the
  success import flow in the browser — the banner is invisible.

### Scale

- **Hero readout typography earns its pixels.** `routes/scale/+page.svelte`
  `.sc-readout-num` at `font-size: 132px` (line 694) is the right scale.
  Tare-pulse to copper (lines 701-702) is a tasteful micro-interaction.
- **"Connect a scale to weigh your dose" warning** (line 384) is in the
  dose-helper card but the page itself doesn't reach for a real empty state
  when no scale is paired — it just shows `0.0 g` huge with `no scale
  connected` underneath (lines 322-345). For a single-purpose page that's
  probably fine, but: the **Start timer** and **Reset peak** buttons (lines
  352-359) have no `disabled={!connected}` and the page admits both are
  UI-only stubs (lines 200-211). They will mislead users.

### Settings

- **The most disciplined surface in the app.** `BrewDefaultsSection.svelte`
  is a model of how the rest of the app could look — `StRow` + `StStepper` +
  `StToggle`, every value persisted, every label and sub written by someone
  who can write. Preserve this pattern.
- **Section nav has no scroll anchor.** `routes/settings/+page.svelte:73-74,
  82-106`. State is local; on reload you're back on "Machine." If a deep-link
  from the brew dashboard ever needs to jump straight to e.g.
  Display→Theme, you'll need URL state. Defer until needed.

### Shared

- **No global `:focus-visible` rule.** Already in
  `docs/12-accessibility.md`; flagged here because the design system has the
  `--shadow-focus` token ready to go (`tokens.css:206`) and not wiring it is
  literally a one-line `app.css` addition.
- **Sidebar is solid.** `lib/components/CremaSidebar.svelte` — five items,
  clean, two well-labeled connection toggles, 1-5 keyboard shortcuts, correct
  z-index (line 149, with a comment explaining why). Don't touch.
- **Phosphor icon mix is inconsistent.** `.ph-duotone` (sidebar nav,
  ChannelReadout), `.ph` (most buttons), `.ph-fill` (big start, stars).
  Choose deliberately: duotone for nav/identity, regular for actions, fill
  only for "this is now ON." Currently mostly right but not by rule —
  e.g. the brew head buttons (`BrewDashboard.svelte:446,452,456`) are all
  regular `ph` but the channel readout icons are `ph-duotone` (line 41 in
  ChannelReadout.svelte). Document the rule somewhere.

## Top 5 highest-ROI improvements

1. **Finish the token wiring (1-day sweep).** Define `--success-rgb`,
   `--warning-rgb`, `--info-rgb` in `tokens.css`. Replace
   `rgba(167, 64, 64, …)` with `rgba(var(--danger-rgb), …)` in
   `settings-page.css:254-259` and `routes/settings/+page.svelte:438-443`.
   Replace the 15 hard-coded `rgba(244, 237, 224, X)` in `mode-controls.css`
   with `rgba(var(--tint-rgb), X)` so mode chips work in light theme. This
   fixes the invisible-import-success-banner bug and unbreaks light theme
   without any redesign.

2. **De-clutter the brew dashboard.** Pick one canonical surface per signal:
   retire `PhaseIndicatorCard` from the resting dashboard (or hide behind
   QuickSheet); drop `Group` from the foot meta (the TEMP readout already
   shows it); drop the foot's redundant `Scale · 18.4 g` cluster while a shot
   is in progress (the WEIGHT readout already shows it). Promote the four
   readouts to ~40 px so they actually compete with the 76 px timer for
   importance.

3. **Ship a real "DE1 not connected" state on the brew dashboard.** Match the
   pattern from `routes/history/+page.svelte:364-392`: a centered glyph,
   serif title ("Connect your DE1"), short sub, single CTA that opens the
   sidebar pairing flow. Disable the big Start button when `de1State !==
   'ready'`. This is the most-visited screen in the app — it should not
   render normally with `—` placeholders when nothing is connected.

4. **Collapse the three steppers to one component.** Build a single
   `Stepper.svelte` with `size?: 'sm' | 'md' | 'lg'`, `tone?: 'light' | 'dark'`,
   `label?`, `unit?` — match the click-to-type behavior already triplicated.
   Replace `QStepper` / `StStepper` / `PeNumber`. Also lift the dark-tone
   button to 44 px minimum so a tablet finger can hit it mid-pour.

5. **Global `:focus-visible` ring.** Add to `app.css`:
   `:focus-visible { box-shadow: var(--shadow-focus); border-radius:
   inherit; outline: none; }` plus the per-control sweep noted in
   `docs/12-accessibility.md`. Already-defined token, no design change
   needed, unlocks keyboard nav. This is the cheapest accessibility win
   available.
