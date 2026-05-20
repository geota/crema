# 15 — App-level Write Actions (Deferred Plan)

**Status:** planning — none of these are core/FFI work
**Companion:** `docs/14-write-actions-audit.md` (§8)

## Purpose

The write-actions audit (`docs/14`) found 8 actions that the legacy DE1 app
and DSx2 expose to the user but that **do not touch the DE1 over BLE** — they
are shell-side concerns (file I/O, HTTP uploads, tablet niceties, profile/log
bookkeeping). Doc 14 left them explicitly out of the core/FFI implementation
plan. This doc captures them as Crema *app-level* features to land in the
web and Android shells in their own time.

For each: what it does, current Crema status, the shell that owns it, the
proposed approach, and priority/effort.

## Status table

| # | Legacy action | Crema status | Implementation home | Priority |
|---|---|---|---|---|
| 8.1 | `save_settings` | **Done** — `$lib/settings/store` (web localStorage), Android `DataStore` | both shells | — |
| 8.2 | `visualizer_upload` | **Stub** — `SharingSection.svelte` has UI but no upload code | web first, Android later | **High** |
| 8.3 | `log_upload` (diagnostics export) | **Missing** — only a "console" event log exists | web (Settings → Advanced) | Medium |
| 8.4 | `print_the_shot` | **Missing** | web (browser print API) | Low |
| 8.5 | Screen-saver image rotation | **Missing** | both shells (tablet UX) | Low |
| 8.6 | `scale_disconnect_now` | **Done** — sidebar Scale toggle + `CremaApp.disconnectScale()` | both shells | — |
| 8.7 | Profile save / hide / unhide | **Partial** — save / edit / delete done; **hide-built-in is missing** | web profile store | Medium |
| 8.8 | History writes (`history_save`) | **Done** — `$lib/history/store` (now `crema.history.v2` localStorage) | both shells | — |

## To-do work, by priority

### 8.2 — Visualizer upload (High)

Visualizer.coffee is the de-facto cloud shot-sharing service the DE1 community
uses. Today `web/src/lib/components/settings/sections/SharingSection.svelte`
shows a "Visualizer integration" card with stub buttons; nothing actually
uploads.

**API.** POST `https://visualizer.coffee/api/shots/upload` with basic-auth
(`Authorization: Basic base64(user:pass)`). Body is `multipart/form-data` with
the shot file (the same JSON that `StoredShot::to_json` produces — or, since
the web's `StoredShot` is a different shape, a Visualizer-shaped JSON serializer
in the web shell). Response carries the shot's Visualizer URL + share code.

**Plan.**
1. **Web shell** owns it; nothing in the core changes. New `$lib/visualizer`
   module with `uploadShot(record: StoredShot, creds): Promise<UploadResult>`.
2. **Credentials**: Visualizer username + password live in
   `$lib/settings/store`'s `sharing` section (already roughed in;
   `SharingSection.svelte` has the form fields).
3. **Auto-upload toggle** + manual "Upload to Visualizer" action in
   `ShotDetail` next to Download.
4. **Upload queue** for retry (an in-memory FIFO in the orchestrator; persist
   pending uploads to localStorage if we want offline resilience).
5. **Status surface**: per-shot "uploaded ✓ / pending / failed" with the
   Visualizer URL once available.
6. **Shot format**: emit the de1app-compatible shot JSON. The audit notes the
   Rust `StoredShot` is Crema-internal, not Visualizer's format — the web
   shell will need a small mapper. Look at de1app `visualizer_upload` plugin
   for the exact field expectations.

Effort: Medium (the mapper is the bulk; the HTTP call is small).

### 8.3 — Diagnostics / log export (Medium)

The Tcl app has a `log_upload` plugin that POSTs a debug log file. For
Crema, the analog is "export the in-app event log + last shot's raw capture +
relevant settings for bug reports."

**Plan.**
1. Settings → Advanced gets a "Download diagnostics" button.
2. Bundles: the `ui.eventLog` JSON, current settings, current shot's BLE
   capture from IndexedDB if one exists, app version + build info, browser /
   user-agent.
3. Single `.zip` (or single `.json` if the zip dep is heavy) download via the
   same Blob + anchor pattern the shot capture already uses.

Effort: Small.

### 8.4 — Print the shot (Low)

The legacy `print_the_shot` plugin prints a one-page shot card.

**Plan.** A "Print" button in `ShotDetail` that triggers `window.print()` with
a `@media print` stylesheet that lays the detail page out as a single A4 / US
letter card. No backend, no permissions. Could be a satisfying small thing.

Effort: Small.

### 8.5 — Screen-saver image rotation (Low)

Tablet UX nicety. Off-topic for most users. Park it.

**Plan.** If pursued, the web shell would render a `/saver` route the PWA can
display when idle. Picks a static image from a small bundled set; the Android
shell could use Android's own screen-saver. Defer until there is genuine
demand.

Effort: Small once we have a design; basically zero right now.

### 8.7 — Profile hide / unhide (Medium)

The web profile store supports create / edit / delete for user profiles, but
the legacy app additionally lets you **hide a built-in** so it does not appear
in the library. We have a `pinned` flag (for favorites); we do not have a
`hidden` flag.

**Plan.**
1. Add `hidden?: boolean` to `CremaProfile` model + `$lib/profiles/store`.
2. Persist in the same localStorage entry that already holds the per-built-in
   override (last-used time, pin state).
3. Profiles page: an overflow-menu "Hide" item (for built-ins; user profiles
   keep "Delete"). A small toggle in some settings area lists hidden built-ins
   so they can be un-hidden.
4. Android mirror later.

Effort: Small.

## Implementation order (recommended)

1. **8.2 Visualizer upload** — most-requested community feature, has the most
   user-facing payoff, and the UI scaffold is already in place.
2. **8.3 Diagnostics export** — small, high value for bug reports.
3. **8.7 Profile hide / unhide** — small, completes the library UX.
4. **8.4 Print** — small, nice polish.
5. **8.5 Screen saver** — only if there is demand.

Each is a self-contained shell feature; they can ship independently and in
any order. None depends on the core write-actions implementation work in
`docs/14`.

## Out of scope here

- Anything that touches BLE / the DE1 (covered by `docs/14`).
- A general "plugin architecture" (Crema deliberately does not have one — see
  `docs/05-plugin-architecture.md`). Each item above is a first-class shell
  feature, not a plugin.
