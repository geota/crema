# Crema web-shell PWA — Security Review

**Scope:** `/Users/adrianmaceiras/code/crema/web/` — static client-only SvelteKit 2 / Svelte 5 PWA, deployed to Cloudflare Pages. No backend, no auth, no Crema account. Web Bluetooth to a DE1 + Bookoo scale; `localStorage` for settings/history/profiles; IndexedDB for shot captures.

**Reviewed:** entrypoints (`app.html`, `+layout.svelte`), orchestrator (`lib/state/app.svelte.ts`), BLE managers, profile/shot/capture import paths, all settings stores, all sections rendering user data, dependency tree (`pnpm audit`), build/PWA config.

**Headline finding:** for what this app actually is — a single user driving their own espresso machine from their own tablet — the security posture is genuinely fine. No critical, high, or medium findings. Everything below is preventive / hardening.

---

## Low

### L1 — No Content Security Policy on any deployment path
- **Location:** `/Users/adrianmaceiras/code/crema/web/src/app.html` (no `<meta http-equiv="Content-Security-Policy">`); `/Users/adrianmaceiras/code/crema/web/wrangler.jsonc` (no `_headers`); `/Users/adrianmaceiras/code/crema/web/static/` (no `_headers` file).
- **Issue:** The app ships zero CSP headers. The build is a pure static SPA on Cloudflare Pages, which serves nothing by default. The only third-party origins the app actually contacts are `fonts.googleapis.com` and `fonts.gstatic.com` (the Google Fonts link in `app.html:14-17`); WASM + JS + CSS are all same-origin.
- **Why it matters here:** Genuinely thin given the threat model. No `@html`, no `innerHTML`, no `eval`, no string-arg `setTimeout` anywhere in `/src` (verified by grep). All imported profile / shot data renders through Svelte's curly-brace interpolation which HTML-escapes. So a stored-XSS injection requires a Svelte bug or a `@html` directive someone might add later — CSP would be the second line of defence for the latter. The realistic value of CSP here is mostly future-proofing the codebase against a careless raw-HTML directive slipping in during a polish pass.
- **Suggested fix:** Add a `static/_headers` for Cloudflare Pages with a strict CSP:
  ```
  /*
    Content-Security-Policy: default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'none'
    X-Content-Type-Options: nosniff
    Referrer-Policy: no-referrer
  ```
  `'wasm-unsafe-eval'` is required for the `de1_wasm` core. `'unsafe-inline'` for styles is needed for SvelteKit's inline `<style>` blocks and for the inline `style="..."` attributes the codebase uses (see e.g. `ProfileCard.svelte:111`); the alternative is hash-based and brittle.

### L2 — Service worker uses `registerType: 'autoUpdate'` with no version pin
- **Location:** `/Users/adrianmaceiras/code/crema/web/vite.config.ts:17`
- **Issue:** The Vite-PWA plugin is configured with `registerType: 'autoUpdate'`, which means the SW skips waiting and takes over on the next navigation. Combined with `globPatterns: ['**/*.{js,css,html,ico,png,svg,webmanifest,wasm}']` precaching the entire shell, a poisoned deploy or an MITM on the *first* HTTPS load would be cached and served indefinitely.
- **Why it matters here:** Cloudflare Pages serves over HTTPS-only with HSTS available; the actual MITM window is the initial page load on a network the user trusts. The "poisoned deploy" axis assumes an attacker has compromised the Cloudflare account, in which case CSP/SW caching are noise. Real exposure is small.
- **Suggested fix:** None strictly necessary. If you want belt-and-suspenders: switch to `registerType: 'prompt'` and surface "an update is available, reload?" UI; this lets the user notice if the app starts behaving oddly after a deploy. Independently: enable HSTS in the Cloudflare dashboard if not already on.

### L3 — Capture replay accepts unbounded file size and processes synchronously
- **Location:** `/Users/adrianmaceiras/code/crema/web/src/lib/replay/capture.ts:111` (`parseCapture`), `/Users/adrianmaceiras/code/crema/web/src/lib/components/settings/sections/AdvancedSection.svelte:80-85` (file picker), `/Users/adrianmaceiras/code/crema/web/src/lib/state/app.svelte.ts:852-870` (`replayCapture`)
- **Issue:** `parseCaptureFile` calls `file.text()` on the whole file at once, then `split('\n')` and `JSON.parse` per line. A multi-GB JSONL upload would OOM the tab; a single 100 MB line of JSON would also be expensive. No `file.size` check, no streaming.
- **Why it matters here:** This is a self-inflicted DoS vector only — the user picks the file themselves from their own disk. There is no scenario where someone else's malicious file ends up here without the user explicitly choosing it from the Advanced settings → Replay picker. Worst case: the user clicks the wrong file, the tab freezes, they reload. Not a real risk.
- **Suggested fix:** Optional. If you want a friendlier failure mode: reject `file.size > 50 * 1024 * 1024` in `replayCapture` with a clear toast, and/or stream the file via `file.stream().pipeThrough(new TextDecoderStream())` and parse line-by-line. Same applies to `importProfileFile` / `importShotFile` in `app.svelte.ts:551-623`, where the Rust parser at least bounds memory differently. Lowest priority.

### L4 — `pnpm audit` flags a transitive `cookie <0.7.0` in the dev tree
- **Location:** `pnpm audit --prod` output: `.>bits-ui>runed>@sveltejs/kit>cookie` (GHSA-pxg6-pf52-xh8x)
- **Issue:** SvelteKit drags in `cookie@<0.7.0` via `bits-ui → runed → @sveltejs/kit`. The advisory is about out-of-bounds characters in cookie name/path/domain — only exploitable in code that sets/parses cookies on the server.
- **Why it matters here:** Crema sets zero cookies. `grep -rn "cookie" src/` returns nothing. The SvelteKit cookie helper is unreachable code in a static SPA build — adapter-static emits HTML/JS/wasm only, no SSR runtime. Pure dependency-graph hygiene.
- **Suggested fix:** Will fix itself when `bits-ui`/`runed`/`@sveltejs/kit` bump their cookie pin. If you want to silence it, add a pnpm `overrides` for `cookie@^0.7.0` in `package.json`. Don't lose sleep.

---

## Informational

### I1 — Visualizer upload is entirely a UI stub today
- **Location:** `/Users/adrianmaceiras/code/crema/web/src/lib/components/settings/sections/SharingSection.svelte:53-61`, `/Users/adrianmaceiras/code/crema/web/src/lib/components/history/ShotDetail.svelte:185-188`
- **Note:** The threat-model concern about "what does Crema actually send to Visualizer" does not apply yet — the codebase contains *zero* `fetch()`, `XMLHttpRequest`, `sendBeacon`, `WebSocket`, `Worker`, or `EventSource` calls (verified). `signIn()`, `uploadNow()`, and `share()` are all `// TODO` stubs that do nothing. The Visualizer preferences in `lib/settings/store.svelte.ts:115-123` (`visualizerAutoUpload: true`, `visualizerPrivacy: 'unlisted'`, `visualizerIncludeNotes: false`) are persisted state describing how the upload *would* behave once implemented — they do not cause any data leakage today.
- **For when the upload lands:** the `visualizerIncludeNotes` flag currently defaults to `false` (good — keeps personal notes opt-in). Re-review at that time; in particular, verify the `private` privacy setting genuinely scopes to the uploader's account on Visualizer's side, and that bean `roaster`/`type` (which are always sent as part of the shot record) match the user's expectation of what gets published.

### I2 — BLE scan filters are tight; device data routes through Rust core
- **Location:** `/Users/adrianmaceiras/code/crema/web/src/lib/ble/de1.ts:180-186`, `/Users/adrianmaceiras/code/crema/web/src/lib/ble/scale.ts:112-115`
- **Note:** `requestDevice` is filtered to the DE1 A000 service + name-prefix and the Bookoo `BOOKOO_SC` name-prefix respectively. This is the correct hardening pattern. All inbound notification bytes go through `core.onNotification(source, data, atMs)` (the Rust core) before reaching the UI, so a misbehaving device cannot inject TS-side values directly. Captured into IndexedDB as bytes — no parsing-side state pollution.

### I3 — `localStorage` data is non-sensitive
- **Location:** `/Users/adrianmaceiras/code/crema/web/src/lib/utils/storage.ts`, all `*Store` classes
- **Note:** What's persisted: app preferences, profile library, shot history with telemetry curves + tasting notes, bean log. No credentials, no tokens, no PII beyond what the user typed into a notes textarea. Nothing here needs to be encrypted at rest; the threat-model framing ("flag if surprised") finds nothing surprising.

### I4 — External links correctly use `rel="noreferrer noopener"`
- **Location:** `AboutSection.svelte:50-56,65-70,81-86`, `SharingSection.svelte:116-123`
- **Note:** All four `target="_blank"` links carry `rel="noreferrer noopener"`. Nothing to do.

---

## Bottom line

This is a coffee app on a tablet, and there is nothing actually wrong with it.

The realistic threats from the brief — cross-site BLE permission trickery, Visualizer data exposure, malicious profile injection, dependency surface, local-data hygiene — all came up clean. Web Bluetooth permissions are origin-gated and per-device-chosen by the user; the Visualizer upload literally does not exist yet (it's a UI stub with no network calls); imported profiles/shots are parsed by Rust and rendered through Svelte's auto-escaping interpolation (no raw-HTML directives, no `innerHTML`, no `eval` anywhere in the codebase); the one `pnpm audit` finding is a SvelteKit transitive in a code path the static build cannot reach; and `localStorage` holds coffee preferences and tasting notes, not credentials.

The only genuinely worth-doing item is L1 (add a `_headers`-based CSP) — not because anything is broken, but because it's a 10-minute future-proofing edit that costs nothing and would catch a careless raw-HTML directive if one ever slipped in during a polish pass. Everything else is hand-wave territory for an app of this shape.

When the Visualizer integration actually lands (currently TODOed across `SharingSection.svelte`, `ShotDetail.svelte`, and the `lib/settings` flags), re-review specifically the upload payload and the OAuth flow — that's the one place a real network attack surface will appear. Until then, this is fine.
