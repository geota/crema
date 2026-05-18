# 09 — Web Shell Architecture

Status: **decided 2026-05-18**. Builds on [08 — FFI & Web Scope](08-ffi-and-web-scope.md).

## Goal

A native-feeling **Svelte web shell** for Crema — the second shell over the shared
sans-IO Rust core, alongside the Android/Kotlin shell. Feature parity with the
Android shell. A fully static, installable PWA, hosted on Cloudflare Pages at a
custom domain. Multiple screens as the app grows; a single-screen POC to start.

## Stack

| Concern   | Choice |
|-----------|--------|
| Framework | SvelteKit 2.6, `adapter-static` (SPA mode, `ssr = false`) |
| Language  | TypeScript; Svelte 5 (runes) |
| Styling   | Tailwind CSS v4 + daisyUI 5 (components/themes) + Bits UI 2 (headless a11y primitives) |
| PWA       | `@vite-pwa/sveltekit` (Workbox) |
| wasm      | `de1-wasm` built with wasm-pack `--target web` |
| Hosting   | Cloudflare Pages, custom domain, `wrangler` deploy |

## Key decisions

### SvelteKit + adapter-static, SPA mode
File-based routing makes multi-screen growth free — a new screen is a new
`routes/` folder. `adapter-static` with a `fallback` page emits a fully static
SPA (no server). `ssr = false` globally: the app is client-only (wasm + Web
Bluetooth are browser APIs), so skipping SSR removes all "can't run on the
server" friction.

### Core on the main thread, behind an async facade
The core is a sans-IO event processor at ~10 Hz — microsecond-scale work. It
runs on the **main thread**: no Web Worker, no Comlink, no `SharedArrayBuffer`,
no COOP/COEP. (`pdf.maceiras.dev`'s worker/SAB machinery exists for CPU-bound
PDF rendering — not applicable here.) Web Bluetooth is main-thread-only
regardless, so a worker would only relocate the trivial decode step at the cost
of a `postMessage` hop. The UI reaches the core through an **async facade**, so
it could move into a worker later with no UI changes if a real need appears.

### Styling: three composable layers
Tailwind v4 is the utility engine. daisyUI 5 adds pre-styled, themeable
component classes (CSS-only) and carries the visual load. Bits UI 2 provides
headless, accessible behavioral primitives (focus/keyboard/ARIA) for genuinely
interactive widgets. They compose — daisyUI classes apply to Bits UI primitives.

### Cloudflare Pages
The static build deploys via `wrangler pages deploy` (`wrangler.jsonc` points at
the build output). Custom domain → `base: '/'`. Cloudflare can serve a `_headers`
file if ever needed; with no `SharedArrayBuffer`, COOP/COEP are not required.

## Repo layout

```
crema/web/
  svelte.config.js  vite.config.ts  package.json  wrangler.jsonc
  static/                       PWA icons, manifest assets
  src/
    routes/+page.svelte         POC screen; more routes added later
    lib/
      core/   async facade over the de1-wasm CremaBridge; CoreOutput parsing
      ble/    Web Bluetooth — BleTransport, scanner, DE1 + scale managers
      state/  MainUiState-equivalent runes stores; event folding
      components/  ScaleCard, ConfigStepper, ConfigToggle, ModeSelector, ...
  pkg/                          wasm-pack output — gitignored
```
All logic lives in `$lib`, screen-agnostic; screens are thin views over shared
runes stores.

## Prerequisite: `de1-wasm` ↔ `de1-ffi` parity

`de1-wasm` lags `de1-ffi` — it lacks the scale-config surface. Before the shell
can match Android, `de1-wasm`'s `CremaBridge` must add: `scale_capabilities`,
`query_scale_settings`, `set_scale_{volume,standby_minutes,flow_smoothing,
anti_mistouch,mode,auto_stop}`, and `scale_uuids` (the connected scale's GATT
UUIDs, so the shell knows which characteristics to subscribe to).

## BLE — Web Bluetooth

Chromium only (Chrome / Edge / Opera — not Firefox / Safari / iOS); the shell
shows a graceful unsupported-browser notice elsewhere. Each connect is a
`navigator.bluetooth.requestDevice()` call (requires a user gesture; no
background scan) with name-prefix filters — which maps cleanly onto the existing
"Connect DE1" / "Connect scale" buttons. `startNotifications()` per
characteristic (CCCD automatic). DE1 GATT UUIDs (`A000` service; `A002/A00B/
A00D/A00E/A011`) are mapped in the shell like Android's `De1Uuids.kt`; scale
UUIDs come from the core's `scale_uuids()`. Each notification →
`core.onNotification(source, bytes, performance.now())` → `CoreOutput` → folded
into the runes state.

## Build sequence

1. `de1-wasm` ↔ `de1-ffi` parity (Rust); regenerate TS bindings.
2. Scaffold `crema/web/` — SvelteKit + adapter-static + Tailwind/daisyUI/Bits UI
   + PWA; wire the two-stage `wasm-pack → svelte build`; `wrangler.jsonc`.
3. `lib/core` — wasm load + async facade + `CoreOutput` parsing.
4. `lib/ble` — Web Bluetooth transport, scanner, DE1 + scale managers.
5. `lib/state` — runes stores; event folding.
6. POC screen — components matching the Android shell.
7. PWA manifest, icons, browser-support gate.
8. Build + Cloudflare Pages deploy.

## Parity scope

Matches the Android shell: a single screen to start (DE1 connect, scale card,
capability-gated config controls, readout card, event log), DE1 read-only (no
machine control). Both shells grow to multiple screens later.
