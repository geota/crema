# Canvas profile preview — complete the Vico→Canvas port (increment C)

Status: ready-for-agent

The Profiles grid cards + the Quick Controls favorites strip still use the old
stepped `CurvePreview` in `ProfilesScreen.kt` — disjoint horizontal dashes
("dashed green/blue"). This is the **last piece of the Vico→Canvas conversion**.

Replace it with a Canvas port of the web `components/profiles/ProfilePreview.svelte`:

- **Pressure curve** (hero, `tel.pressure` sage) with a soft top-down gradient fill
  (colour ~30% alpha at top → 0 at baseline).
- **Damped "estimated flow" ghost** — dashed blue, light fill;
  `dampFlow(target) = min(4, target * 0.35 + 0.5)`.
- **Temperature step line** on a right °C axis (80–105), stepped (holds across a
  segment then jumps): `tempAt(segs, x)`.
- Axes: y 0–12 bar (splits 0,3,6,9,12), right °C (80,90,100), x seconds; solid
  h-grid + dashed v-grid (full mode only).
- A **`compact`** mode (bare pressure+flow silhouette, no chrome) for the favorites strip.

Needs a Kotlin port of the web `sampleCurve(segments[, transform])` sampler
(`$lib/profiles`) — the same shape used by the editor curve and the LiveChart goal
line. Consider extracting shared curve-sampling so the editor curve, this preview,
and any future goal-line overlay use one sampler.

Acceptance: Profiles grid + favorites previews render a smooth pressure/flow/temp
curve faithful to the web, no chart library.
