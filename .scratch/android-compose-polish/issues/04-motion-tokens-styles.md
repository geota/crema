# Motion, spacing tokens, Styles API (compose-expert review)

Status: needs-triage

Extended compose-expert review (M3 motion + atomic/tokens + Styles API + side effects):

- **M3 Motion.** The app has **no animations** — no violations, but a fidelity gap
  vs the web (500 ms chart sample interpolation, screen transitions,
  micro-interactions). Add via `MaterialTheme.motionScheme` specs (NOT raw
  `tween(N)`): live-chart sample interpolation, NavHost enter/exit transitions, a
  few control micro-interactions. Enter = decelerate, exit = accelerate.

- **Spacing tokens.** Spacing is raw `dp` throughout. Consider a `LocalSpacing`
  CompositionLocal scale tied to the handoff density values (atomic-design tokens).

- **Styles API (experimental).** The experimental Foundation Styles API
  (`Style {}`, `MutableStyleState`, `Modifier.styleable()`) could fit the
  CremaComponents design system, but it is experimental + churny — **defer**;
  revisit when it stabilises.

- **Side effects — reviewed, CLEAN (no action).** The app uses no
  `LaunchedEffect` / `DisposableEffect` / `SideEffect`; async work lives in the
  ViewModel (`viewModelScope`), and the charts dropped their `LaunchedEffect` when
  they became Canvas. `ProfileCurveChart` uses `rememberUpdatedState` correctly.
