# 34 — App Backup & Restore

**Status:** 2026-05-24 design doc. v1 = local ZIP download; v2 = Google Drive sync (future).

## Why

Users need a way to:

1. **Survive a browser cache clear** — `localStorage` is local to the device + browser profile. Cache wipes (intentional or accidental) lose every profile, bean, shot, setting.
2. **Move between devices** — set up Crema on a new laptop with all their library intact.
3. **Try things safely** — back up before importing a Beanconqueror dump, before factory-reset, before any experiment.

A clean, opinionated "Backup / Restore" surface in Settings → Sharing covers all three at low engineering cost.

## v1 — Local ZIP backup (ship now)

Browser-only, no auth, no cloud. The simplest thing that solves the three motivations above.

### UX

New "Backup & restore" group in **Settings → Sharing**, with two primary buttons:

- **Download backup** — builds a ZIP in-browser, triggers a download via the existing `downloadBlob` helper (`web/src/lib/utils/download.ts`). Filename: `crema-backup-{deviceLabel}-{yyyymmdd-hhmm}.zip`.
- **Restore from backup** — opens a file picker (`.zip` accept), reads the ZIP, parses the manifest, shows a confirmation modal listing what will be replaced, and on confirm wipes the affected `localStorage` keys + reloads the app.

The Restore confirmation modal is **destructive** — uses the same red-bordered alert pattern PR-C established for the heater-voltage modal, with type-to-confirm. The phrase the user types is the device label of the backup (e.g. `type "my-laptop" to confirm`) so they can't accidentally restore the wrong backup onto an active library.

### Backup contents

The ZIP is a flat archive of one file per store + a manifest:

```
crema-backup-my-laptop-20260524-1430.zip
├── manifest.json            # version, deviceLabel, timestamp, appVersion, contents[]
├── profiles.json            # localStorage 'crema.profiles.v1'
├── history.json             # localStorage 'crema.history.v1'
├── beans.json               # localStorage 'crema.beans.v1'
├── roasters.json            # localStorage 'crema.roasters.v1'
├── settings.json            # localStorage 'crema.settings.v1' (with auth tokens stripped)
└── maintenance.json         # localStorage 'crema.maintenance.v1'
```

### Manifest shape

```json
{
  "version": 1,
  "createdAt": 1748113800000,
  "appVersion": "0.0.1",
  "deviceLabel": "my-laptop",
  "contents": [
    { "key": "crema.profiles.v1", "file": "profiles.json", "byteSize": 12453, "itemCount": 17 },
    { "key": "crema.history.v1", "file": "history.json", "byteSize": 482910, "itemCount": 213 },
    ...
  ]
}
```

The manifest powers the Restore confirmation UI ("Replace 17 profiles, 213 shots, 5 beans, …") and lets a future Crema version refuse / migrate backups created by older / incompatible versions.

### Exclusions

- **OAuth tokens** (`crema.visualizer.tokens.v1`, etc.) — security. User re-auths after restore. Documented in the Restore confirmation modal.
- **Replay captures** (IndexedDB blobs) — can be tens of MB each, niche. Defer to v2 with an opt-in checkbox.
- **Bean photos** (future IndexedDB blobs) — same as captures.
- **Browser state** (open tabs, scroll positions, focus state) — not backed up.

### Device label

User can set a friendly device label in Settings → Sharing → Backup ("MacBook Pro", "Home iMac", "iPad"). Defaults to a derived label from `navigator.userAgentData` if available, else a placeholder. Persisted in `crema.settings.v1.deviceLabel`. Used to disambiguate multi-device backups in the Restore picker.

### Dependencies

- `jszip` — likely already a dep from the Beanconqueror import work (confirm during implementation). Build + download a ZIP entirely client-side.
- Reuses the existing `downloadBlob` + `filenameStamp` from `web/src/lib/utils/download.ts`.
- Reuses the existing destructive-modal pattern from `MainsConfirmModal.svelte` (heater voltage).

### Effort

~1 day. Mostly UI + the manifest design.

---

## v2 — Google Drive sync (future)

Once v1 is live and the OAuth+PKCE patterns from the Visualizer work are stable, Google Drive becomes a small additional surface.

### Reuses

The Visualizer OAuth work (PR #72) builds these reusable primitives:

- `lib/visualizer/oauth.ts` PKCE helpers (`generateCodeVerifier`, `codeChallengeFromVerifier`, `randomState`) — domain-agnostic; lift to `lib/auth/oauth.ts`.
- `lib/visualizer/token-store.ts` — generalise to `lib/auth/token-store.ts` keyed by provider.
- `/auth/{provider}/callback` route shape.
- Settings → Sharing UI pattern (Sign in / Disconnect, expiry display).

Drive then needs only:

- A Google Cloud Console OAuth client (5 min setup; PKCE supported for SPAs).
- Drive REST calls (`POST /upload/drive/v3/files`, `GET /drive/v3/files/{id}?alt=media`, `GET /drive/v3/files?spaces=appDataFolder`).
- Scope: `https://www.googleapis.com/auth/drive.appdata` — hidden per-app folder, not visible in user's Drive UI. Keeps things tidy.
- Multi-device collision strategy: one backup file per device label, e.g. `crema-backup-my-laptop.zip` in the App Data folder. Two laptops with auto-backup don't overwrite each other.

### Phasing

**v2.0** — Manual: extends the v1 buttons with "Backup to Drive" / "Restore from Drive" alongside the local versions.

**v2.1** — Auto: opt-in debounced auto-backup (e.g. 5 min after the last change). Per-device collision via the device label.

**v2.2** — Cross-device *sync*: real merge logic, not just restore. Conflict resolution on a per-store basis. Materially harder; defer until users ask.

### Trade-offs to revisit at v2 time

| Topic | Note |
|---|---|
| **Verification ceremony** | Google requires OAuth consent screen verification for production apps. Adds 1-4 weeks of review for first launch. Skippable for internal use; required for public app store. |
| **Encryption at rest** | Drive stores plaintext. For coffee data, low-stakes. If Crema ever stores personal-data-y fields, add client-side passphrase encryption before upload. |
| **Quota** | Drive App Data counts against user quota. Realistic Crema backup is <10MB for years of data; non-issue at the data sizes we're shipping. |
| **Multiple providers** | iCloud Drive / Dropbox / OneDrive — each their own OAuth. Pick a single one for v2 (Drive). Revisit only if cross-platform users push back. |
| **Backup vs sync** | "Restore my library on a new device" is **backup** (rare, destructive). "Add a bean on my phone, see it on desktop" is **sync** (frequent, merge-based). v1 + v2.0 / v2.1 are backup. Sync (v2.2) is a separate product question. |

---

## Open questions for v1 implementation

1. **Device label default** — derive from `navigator.userAgentData` if available; otherwise default to "this device" and prompt for a friendly label on first backup.
2. **Restore type-to-confirm** — the device label of the **backup being restored** (so you can't accidentally restore "work-laptop" onto "home-laptop"). If labels match the current device, just type "restore".
3. **Backup size warning** — if the manifest's total byte size exceeds, say, 50 MB, warn the user before downloading (history with many captures can grow).
4. **`crema.settings.v1` token strip** — make sure the OAuth token store key (`crema.visualizer.tokens.v1` and future `crema.google.tokens.v1`) is **excluded** from backup. Document the exclusion in the manifest.
