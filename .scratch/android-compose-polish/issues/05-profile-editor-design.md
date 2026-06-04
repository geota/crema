# Profile editor — match the web design (two-pane + rich segment editors)

Status: ready-for-agent

The current `ProfileEditScreen` is a single scrolling column of cards (Curve,
Identity, Targets, Segments-as-target/time-steppers) — the v1 NON-curve subset. It
does **not** match the web / handoff profile-edit design (IMPLEMENTATION-PLAN §2.4).

Target design:
- **Two-pane**: breadcrumb header (back · Profiles › Edit · Discard / Duplicate /
  Save) over `grid[ 380px form | 1fr curve ]` on tablet.
- **Left form**: Title, Bean, Notes, Roast segmented, **Tags as input chips** (+ add),
  Pin toggle, **2×2 Targets grid** (Dose / Yield / Brew-temp steppers + computed Ratio).
- **Right**: the draggable curve + a **Segments header (+ Add)** and an expandable
  **SegmentEditor** per segment — collapsed = name + summary; expanded = Type
  (Pressure/Flow) & Ramp (Smooth/Fast) toggles + a 6-field grid (Target, Time, Temp
  w/ coffee↔water swap, Volume w/ enable-dot, Exit w/ metric swap + enable-dot, Max
  w/ enable-dot). **Enable-dots** gate optional fields (off → 0.4 alpha, non-interactive).
- **Segment add / remove** (deferred from v1 — needs `patchCremaProfileJson` to grow/
  shrink the segment array, cloning `default_profile_segments()` entries).

Needs the fuller round-trippable segment model in Kotlin: the thin `ProfileSegment`
drops `tempSensor` / `volumeLimitMl` / `limiter` (this issue adds `ramp` + `temp`).
`patchCremaProfileJson` already preserves the dropped fields, but the editor can't
show/edit them yet. Source: web `components/profiles/{ProfileEditor,SegmentRow}.svelte`,
`compose-handoff/.../profile-edit-screen.jsx`.
