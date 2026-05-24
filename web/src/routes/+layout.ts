// The Crema shell is browser-only: it relies on wasm and Web Bluetooth, neither
// of which exists on the server. Disable SSR and prerendering globally so the
// whole app renders as a client-side SPA (adapter-static `fallback`).
export const ssr = false;
export const prerender = false;

// ─── Wasm pre-init ─────────────────────────────────────────────────────────
//
// The wasm core is async-init: `wasm.default()` (`init()`) returns a Promise
// that must resolve before any exported function — `profile_bounds_json`,
// `roast_band`, `bar_to_psi`, … — is callable. Modules that *statically*
// import those symbols and call them at top level (e.g. `$lib/profiles/bounds`
// runs `JSON.parse(profile_bounds_json())` on first evaluation) raced the
// orchestrator's own `loadCore()` and crashed with
// `Cannot read properties of undefined (reading '__wbindgen_malloc')` on
// first paint.
//
// The fix is structural, not per-call-site: a top-level `await` on the
// memoised `loadCore()` in this layout module blocks every route + component
// module's evaluation until wasm is fully initialised. SvelteKit imports the
// root layout before any page module, so by the time any of those modules
// evaluates their top-level expressions, `__wbindgen_malloc` is defined.
// `loadCore()` is memoised, so the orchestrator's own `await createCremaApp()`
// in the layout component reuses the same Promise — no second wasm load.
//
// Top-level await is an ES2022 feature; Vite + SvelteKit support it natively.
// Because `ssr = false`, this code only ever runs in the browser, which is
// the only environment where the wasm module exists at all.
import { loadCore } from '$lib/core';
await loadCore();
