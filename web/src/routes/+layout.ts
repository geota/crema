// The Crema shell is browser-only: it relies on wasm and Web Bluetooth, neither
// of which exists on the server. Disable SSR and prerendering globally so the
// whole app renders as a client-side SPA (adapter-static `fallback`).
export const ssr = false;
export const prerender = false;

// ─── Wasm pre-init ─────────────────────────────────────────────────────────
//
// The wasm core is async-init: `wasm.default()` (`init()`) returns a Promise
// that must resolve before any exported function — `profile_bounds_json`,
// `roast_band`, `bar_to_psi`, … — is callable. Awaiting the memoised
// `loadCore()` here means the core is ready before any route renders, so the
// synchronous wasm helpers a first paint may reach for (`newProfileId()`, the
// UUID-v7 minter, …) work. `loadCore()` is memoised, so the orchestrator's own
// `await createCremaApp()` in the layout component reuses the same Promise — no
// second wasm load.
//
// This await is NOT a module-eval-order guarantee for the rest of the app:
// page nodes evaluate concurrently with this layout, and ES module evaluation
// order is not gated by it (an earlier version of this comment claimed it was —
// it isn't). So any module that reads core-sourced data must resolve it
// *lazily* on first use, never at module scope — `de1-uuids.ts`,
// `profiles/bounds.ts`, `ble/scale.ts`, and `profiles/model.ts` all do. Calling
// a wasm export at module scope races this init and crashes first paint with
// `Cannot read properties of undefined (reading '__wbindgen_malloc'/'_free')`.
//
// Top-level await is an ES2022 feature; Vite + SvelteKit support it natively.
// Because `ssr = false`, this code only ever runs in the browser, which is
// the only environment where the wasm module exists at all.
import { loadCore } from '$lib/core';
await loadCore();

// ─── One-shot Visualizer Basic-auth → OAuth migration ──────────────────
//
// Prior versions stored a Visualizer email + cleartext password in
// `crema.beans.sync.v1`. The 2026-05-24 cut moves to OAuth-only, so we
// strip those fields out of `localStorage` on every boot (idempotent
// no-op once clean). The Settings UI then shows the "Sign in with
// Visualizer" button as if it's first-run — the intended re-auth nudge.
import { migrateLegacyBasicAuth } from '$lib/visualizer';
migrateLegacyBasicAuth();
