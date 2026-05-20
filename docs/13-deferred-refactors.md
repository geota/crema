# 13 — Deferred Refactors

**Status:** planned — deferred from the `wire-read-paths` whole-repo review fixes
**Companion:** the review (Part: review findings), `docs/12-accessibility.md`

Three refactors were deliberately deferred while fixing the whole-repo review.
Each is **cleanup / tech debt, not a bug** — none changes behaviour — so each was
left out of the fix pass rather than forced. They are captured here as tracked
tasks. None blocks the `wire-read-paths` merge.

---

## Task 1 — Consolidate the uPlot chart wrappers

### Context

Four components render uPlot charts and **duplicate ~300 lines** of wrapper
boilerplate:

- `web/src/lib/components/brew/LiveChart.svelte`
- `web/src/lib/components/history/StaticShotChart.svelte`
- `web/src/lib/components/profiles/ProfileCurveEditor.svelte`
- `web/src/lib/components/profiles/ProfilePreview.svelte`

The shared shape: the `cssVar()` helper, `timeSplits()`, the two-locked-axes
scaffolding inside `buildOpts`, the `ResizeObserver` + create/`setData` effect
pair, and (added in the review fixes) the theme-flip rebuild.

### Why it was deferred

The four genuinely diverge: `LiveChart` has the now-marker plugin, decimation
and the goal curve; `ProfileCurveEditor` has DOM drag handles; `ProfilePreview`
lazy-mounts via an `IntersectionObserver`; `StaticShotChart` takes a `height`
prop. Each also has subtly different `untrack` semantics that were *just*
hardened in the review fixes (chart-rebuild bugs). A shared helper rushed on top
of freshly-changed code risks reintroducing exactly those bugs as a leaky
abstraction.

### Scope & approach

- Extract a `useUplotChart` helper as a `.svelte.ts` module (or a Svelte
  `Attachment`) that owns the **common lifecycle only**: element binding,
  `new uPlot` / `destroy`, the `ResizeObserver`, the create-vs-`setData` split
  with the correct `untrack` boundary, and the `data-theme` → rebuild hook.
- Each component still supplies its own `buildOpts`, plugins, and data mapping —
  the helper must not absorb the per-chart differences.
- Hoist the genuinely shared pure helpers (`cssVar`, `timeSplits`, the axis
  colour tokens) into one module.

### Risks

Regression of the chart-rebuild / `untrack` behaviour. Mitigate by doing it as
its own change with no other work in flight, and visually diffing each of the
four charts before/after.

### Acceptance

All four charts behave identically to today; `pnpm check` 0/0; net line count
down; no component re-declares the wrapper lifecycle.

---

## Task 2 — Make the scale command/response surface device-agnostic

### Context

The architecture (`docs/08-ffi-and-web-scope.md`, `ScaleCapabilities`) is
explicit: Crema is **capability-driven, never device-driven**. One place
violates that — `de1_scale::bookoo` concrete types leak through the unified
`Scale` API:

- `Scale::parse_command_response` returns `Option<bookoo::CommandResponse>`.
- `de1-app`'s `handle_scale_command` matches on `bookoo::CommandResponse`
  directly.
- `de1-app` calls `bookoo::set_volume` / `BookooMode` / `AutoStopMode` /
  `QUERY_SETTINGS` directly, and `bookoo_mode_from_id` / `bookoo_auto_stop_from_id`
  re-encode Bookoo wire knowledge inside `de1-app`.

### Why it was deferred

It is **not a bug today** — the Bookoo is the only first-class configurable
scale, so there is exactly one implementation behind the leak. Designing the
neutral abstraction against a single example would be speculative; it should be
designed against two real cases.

### Scope & approach

- Define a scale-neutral command/response vocabulary owned by
  `de1-scale::scale`: a neutral `ScaleCommandResponse` enum (anti-mistouch
  state, active mode, enabled modes, serial, firmware …) and neutral
  mode / auto-stop enums.
- Each scale implementation translates its own wire format to/from the neutral
  types; `Scale` exposes only the neutral vocabulary.
- `de1-app` deals exclusively in neutral types — no `de1_scale::bookoo::*`
  imports, no per-device id mapping.

### When to do it

**Trigger: when adding the second configurable scale.** Doing it then means the
neutral abstraction is designed against two concrete protocols, not one — the
right time to find the actual seams.

### Acceptance

`de1-app` has no `de1_scale::bookoo` import; `Scale`'s public API names no
device-specific type; all scale tests still pass.

---

## Task 3 — Unify the button system

### Context

The web app has **four-plus parallel button vocabularies** with overlapping
roles and inconsistent metrics (radius, padding, font-size):

- `.crema-btn` — Brew
- `.pp-btn` — Profiles (pill-shaped)
- `.st-btn` — Settings, History, ShotDetail
- `.sc-*` (`.sc-secondary`, `.sc-tare`) — Scale
- plus the Quick-Sheet CTAs (`.qsheet-cta`, `.qcpill`, `.qmstart`)

### What the review-fix pass already did

The design pass unified the **colour treatment** via shared tokens
(`--copper-rgb` primary, `--danger-rgb`) and removed the true duplication (e.g.
the `.st-btn` re-declared in `ShotDetail.svelte`). It did **not** merge the
class sets — radius / padding / font-size differ deliberately in places
(`.pp-btn` is pill-shaped to sit beside the pill filter chips; `.st-btn` is the
compact settings size), and merging them risks visual regressions that cannot
be verified without the design reference.

### Scope & approach

- Design a single button system: `primary` / `secondary` / `danger` / `icon`
  variants and a small size scale (e.g. `sm` / `md`), backed by tokens — one
  component or one class set.
- Decide intentionally which current differences are real (the pill shape may
  be a deliberate Profiles-screen choice) versus accidental drift.
- Migrate each screen, verifying each against the design handoff.

### Risks

Visual regression across all six screens. **Needs the design reference and
visual QA / sign-off** — this is the reason it was not done blind in the fix
pass.

### Acceptance

One button system backs every screen; intentional variants are documented;
each screen visually matches (or deliberately supersedes) the handoff.

---

## Priority

Task 1 (uPlot wrappers) is the most self-contained and the safest standalone
win. Task 3 (buttons) is gated on design QA. Task 2 (scale abstraction) is
gated on a real second configurable scale — do not pull it forward.

---

## Persisted format note — `StoredShot` v1 → v2

Bumped [`STORED_SHOT_FORMAT_VERSION`](../core/de1-domain/src/history.rs) from
`1` to `2` when unit-suffixed field names were dropped from the Rust persistence
layer. **Breaking on-disk shape**, no migrator is shipped — the only consumer
(the project owner) accepted the break and v1 files should be deleted or
reformatted by hand if they exist.

Renames:

- `ShotMetadata.dose_in_g` → `dose`
- `ShotMetadata.yield_out_g` → `yield_out`
- `StoredShot.recorded_at_unix_ms` → `recorded_at` (still Unix epoch ms;
  documented on the field)
- `ShotRecord.duration_ms: u64` → `duration: Duration` (now serializes as the
  serde-default `{secs, nanos}` shape)
- `TimedSample.elapsed_ms: u64` → `elapsed: Duration` (ditto)
- `ShotMetrics.{peak_pressure_bar, peak_flow_ml_per_s, total_water_ml, duration_s}`
  → `{peak_pressure, peak_flow, total_water, duration: Duration}` (this type
  is not persisted; included for symmetry with the rest of the rename)

A reader that needs to accept v1 input must do its own conversion: it has the
`format_version` field to branch on.
