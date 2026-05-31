# Context Map

Crema spans three contexts. Each owns its own `CONTEXT.md` glossary; this map
records where they live and how they relate. (Context glossaries are created
lazily — a context's `CONTEXT.md` appears once its first term is resolved.)

## Contexts

- [Web](./web/CONTEXT.md) — the SvelteKit front-end: brew dashboard, history,
  bean/profile libraries, settings; drives the machine over Web Bluetooth and
  syncs to Visualizer.
- [Core](./core/CONTEXT.md) — the Rust workspace (`de1-app`, `de1-domain`,
  `de1-protocol`, `de1-wasm`): the DE1 protocol, the machine state model, and the
  shot-capture engine, compiled to WASM. _(glossary not yet seeded)_
- [Android](./android/CONTEXT.md) — the Android shell hosting the web app and
  bridging native Bluetooth. _(glossary not yet seeded)_

## Relationships

- **Core → Web**: Core compiles to a WASM bundle the Web context loads; Web's
  `$lib/core` is the typed binding over the machine model and the notification fold.
- **Web → Visualizer**: Web is the only context that talks to the Visualizer cloud
  service (see [Web](./web/CONTEXT.md) → `visualizerCall`).
- **Android → Web**: Android hosts the same Web app and forwards native BLE
  notifications into the shared core.
