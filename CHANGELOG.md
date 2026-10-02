# Changelog

All notable changes to Crema are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and Crema aims to follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed

- **Both flow readings now share the Flow card** (#92) — machine flow (ml/s)
  on the left, scale flow (g/s) on the right, so the two numbers being compared
  sit side by side. Dispensed water volume moved to the Weight card next to the
  scale weight. Web, tablet and phone.
- **Roaster cards open the roaster's shelf** (#86) — tapping a roaster in
  Beans → Roasters now shows that roaster's bags on the Bags tab, archived ones
  included (dimmed), with a pill to return to the full library. Roaster cards
  also say how many of their bags are archived. Editing stays on the pencil.

### Added

- Recipes have a Notes field for filter, water recipe and other setup details (issue #10 feedback)
- **Your own brewing methods** (issue #10 feedback: "I only have an ORB this
  week — can I add it?") — "+ Add method…" in the log form, the recipe
  editor and the Scale page's Brew setup creates a method with a name, a
  style (pour-over, immersion, pressure or cold), an optional icon and
  optional dose / water / temperature that default from the style; it is
  selected straight away and joins every method picker. Brew setup offers
  "+ New recipe for ORB", shaped by the style, and your recipes for it are
  grouped under its name in Profiles → Brew recipes, where a new "Your
  methods" section renames, edits or deletes methods. Past brews keep the
  method's name after a rename or delete (each brew remembers the label),
  recipes that use a deleted method are kept and still run, and History's
  method filter lists your methods. After logging a one-off "Other…" brew,
  Crema offers once to save that name as a method. Custom methods ride in
  backups; older versions skip them. Web, tablet and phone.
- **Built-in brew recipes are now real, credited recipes** — the generic
  "V60 classic"-style starters are replaced by 13 published recipes, each
  shown with its author and a link to the source: James Hoffmann (1 Cup V60,
  Ultimate V60, AeroPress, French Press, Clever Dripper, Cold Brew, and an
  adapted Moka Pot), Tetsu Kasuya's 4:6 Method, the official AeroPress
  instructions, Tuomas Merikanto's 2021 World AeroPress Champion recipe,
  Stumptown's Chemex and Kalita Wave guides, and Hario's Syphon guide. They
  appear in Profiles → Brew recipes (grouped by method, marked Built-in) and
  in the Scale page's Brew recipe picker. Built-ins are read-only: "Duplicate
  to edit" makes your own copy, credited "Adapted from …"; they can be hidden
  but not deleted. Each method opens on its built-in default until you make
  another recipe the default. Espresso, drip and free-text methods have no
  built-in — the Brew setup says so and offers "+ New recipe". Untouched
  auto-saved "classic" starters are cleaned up on upgrade; ones you edited
  stay. Step labels now read as instructions and wrap in the live session,
  clocks show hours for the 12-hour cold-brew steep, and recipes you made
  are now included in backups (built-ins aren't — they ship with the app).
  Estimated brew times now include the drawdown: pourovers read their
  source's finish time (1 Cup V60 ~3:00, Ultimate V60 and 4:6 ~3:30, Chemex
  ~4:00), a recipe with an open-ended step shows a "+" (AeroPress ~2:45+), and
  a recipe with no timed steps (moka) shows no estimate instead of "~0:30".
  During a drawdown the live step shows "about 0:40 left", then how far past
  the expected time you are; it still finishes only when you tap.
- **Chemex and Kalita Wave brew methods** — new method chips (under "More"
  in the log form) with their own marks; Beanconqueror imports of Chemex and
  Kalita brews now land on them instead of V60 / pourover.
- **The Brew Log, Phase 1** (#10) — log what you brew off the machine: V60 /
  pourover, AeroPress, French press, moka, cold brew, drip, siphon, Clever, a
  manual espresso, or any method you type. "Log brew" in History (a button on
  web and tablet, the + button on phone) and "Log a brew" on a bag's detail
  open a short form: method chips, the active bag with its remaining grams,
  dose / water / grind / temp / time seeded from that bag's last brew of the
  method, and the rating / notes / next-time journal behind one disclosure.
  Saving takes the dose off the bag's Remaining — the bag-empty prompt fires
  at zero, like a live shot — and "Log again" on any brew re-fills the form.
  Brews sit in History beside your shots with a method mark, a quiet "logged"
  tag and 1:16-style water-in ratios; a Method filter appears once you've
  logged a second method, and the stats strip counts "Beans used". A logged
  brew's dose, water, temp and time stay editable, and a dose edit re-settles
  the bag. Beanconqueror imports now bring pourovers and other non-espresso
  brews in as Brew Log rows instead of skipping them, and backups keep them.
  Brews stay on your device: they are never uploaded to Visualizer or your
  Decent account and carry no machine stamp. On phones the form is a
  full-screen page with Save above the keyboard; on tablets a side sheet that
  keeps Save on screen in landscape; rotating mid-entry keeps what you typed.
  Web, Android tablet and phone.
- **Guided brews and brew recipes, Brew Log Phase 2** (#10) — the Scale page
  gains a Weigh | Brew switch. Brew picks a method and its recipe (dose, water,
  temp and a step list: bloom, pours, waits, steeps, drawdown), then runs it:
  one big clock, the current step with live weight against its target or a
  countdown, the next step previewed, and three controls (Finish, Pause,
  Skip). With a Bookoo connected the clock can start on your first pour and
  pour steps advance at their targets; without a scale, timed steps advance
  on their own and pour steps wait for a tap. Finishing opens the Log-brew
  form pre-filled with the real time, final water and recipe name, and a
  scale-fed brew saves its weight curve with stage bands to History. "Edit
  recipe" opens the editor right there (a dialog on web, a side sheet on
  tablets, a full page on phones) and Save brings you back to the same setup.
  Recipes live in Profiles as their own "Brew recipes" section (new,
  duplicate, make default, delete) — separate from machine profiles, and a
  recipe never talks to the DE1. Cues: the step card always flashes at a step
  change and just before a pour target; vibration is on and sound is off by
  default — the bell next to Start and Settings → Display switch them. On
  tablets and wide browsers the running session sits beside a live weight
  chart; in phone landscape it becomes a compact no-scroll layout; rotating
  mid-brew keeps the clock running. The brew chart draws the recipe's plan
  as a dashed "planned" staircase under the solid "poured" weight curve, live
  and in History (targets are saved with the brew, so editing or deleting the
  recipe later doesn't change it). Guided brews stay on your device like
  every Brew Log row. Web, Android tablet and phone.
- **Upload shots to your Decent account** (#84) — Settings → Sharing gains a
  "Decent account" card next to Visualizer. Sign in with your decentespresso.com
  email + password (exchanged once for a server token; the password is never
  stored) and every finished shot is uploaded to Decent's shot history + charts
  in the decaid `ShotRecord` format — the same endpoint and shape the tablet
  app's `shot_upload` plugin and decaid's `shot-upload.reaplugin` use, so Crema
  shots sit alongside theirs. Flushes under 5 s are skipped; "Upload unsent
  shots" backfills older shots. Once uploaded, "View on Decent" opens the
  shot's public share link (`decentespresso.com/shot/<serial>/<id>`, the same
  link Decent's own "copy link" button gives). The card shows the account's
  registered machines and warns when the connected DE1 isn't one of them (the
  server refuses those). Web, Android tablet and phone.
- **One Upload row in the shot menu** — History's per-shot menu folds every
  cloud destination into a single "Upload to Visualizer + Decent" entry that
  names whichever is still missing the shot (a re-upload once it is
  everywhere). Turn on one, the other, or both in Settings → Sharing; the
  menu never grows.
- **Share link** — once a shot is uploaded anywhere, the menu offers "Share
  link" next to "View on X" (one row per destination that holds it): copies
  the public link (visualizer.coffee/shots/… or decentespresso.com/shot/…).
  Android hands the link to the system share sheet. A Decent upload with no
  public link offers only "View on Decent" (your account history), never a
  link you can't share.
- **Catch up when you enable, not later** — signing in to a destination, or
  turning its "Upload finished shots" on, asks once whether to upload the
  shots already on this device. Afterwards History carries one "Upload N"
  button for everything missing from any enabled destination (tooltip gives
  the per-destination split). Replaces Visualizer-only "Upload all" and the
  Decent "Upload unsent" settings row.
- **History tells the truth across destinations** — the row pip is filled
  when the shot is on every enabled destination, hollow when it is missing
  from one, with the breakdown in its tooltip. One completion notice per shot
  ("Uploaded to Visualizer + Decent", or naming the one that failed) instead
  of one per destination, and one "Recent activity" log tagged by
  destination.
- **Uploads that behave** — a shot is never uploaded twice to the same
  destination at once, "Upload N" and the Settings catch-up share one run,
  the run stops when you go offline or after three failures in a row, and a
  shot Decent refuses is not offered again until you upload it by hand. Web
  retries Decent uploads that failed offline once you are back online.
- **Backups keep upload status** — a backup now carries each shot's Decent id
  and the machine it was pulled on, so a restore doesn't offer to upload
  everything again.
- **One Decent shot format** — the `ShotRecord` converter and Decent's reply
  handling now live in the shared core, so web and Android upload identical
  shots (previously the two drifted on mix-temperature targets, steam
  temperature, TDS/EY and roast dates). A server reply of `0` is treated as an
  expired login instead of a successful upload.

### Fixed

- **Android backups keep brew details** — a backup made on Android dropped
  each brew's method, recipe name, water, temperature, next-time plan and
  guided weight curve; they now survive backup and restore.

- Guided brews let you choose the bean before you start (issue #10 feedback)

- **Decent Scale on v1.2 firmware shows weight again** — the original Decent
  Scale on firmware v1.2 sends a longer weight message that Crema dropped, so
  the scale showed no weight. Repeated tares are no longer ignored, and the
  scale's firmware version is read correctly.

- **Acaia scales no longer stop shots early** — timer and button messages
  from Acaia scales were sometimes read as weight, which could make
  stop-at-weight end a shot far too soon. Messages that arrive together are no
  longer dropped either.

- **Atomheart Eclair connects** — Crema now uses the Eclair's current
  Bluetooth identifiers, so the scale can be found and connected, and shows
  its battery level.

- **Skale II, Acaia (first generation) and Timemore Dot stay connected on
  Android** — commands are now sent the way these scales expect, so a missed
  reply no longer freezes the connection mid-pour.

- **Skale II reads the right weight** — weights reported at a different
  precision were off by a factor of 10 or more.

- **Bookoo ignores damaged weight messages** — a corrupted message can no
  longer show, or stop a shot on, a bogus weight.

- **Timemore Dot reads every weight** — weight is read from every message the
  scale sends at once, and a battery message is no longer mistaken for weight.

- **DiFluid Microbalance Ti connects** — Crema now recognises the Ti. The
  DiFluid Microbalance also no longer drops weight readings that arrive
  together.

### Security

- **Credentials wrapped at rest (web)** — the Visualizer OAuth tokens and the
  Decent account token are now stored AES-GCM-encrypted under a
  non-extractable Web Crypto key kept in IndexedDB, so a copy of browser
  storage no longer contains a usable session. Existing plaintext tokens are
  wrapped on first read. This is a second layer behind the CSP; script running
  on the origin can still use the tokens, as before.
- **Credential files excluded from device transfer (Android)** — data
  extraction rules keep `visualizer.json`, `decent.json` and `drive.json` out
  of cloud backup and device-to-device transfer (`allowBackup="false"` alone
  does not stop the latter on every manufacturer). Shots, beans, profiles and
  prefs still transfer.
- **Credentials wrapped at rest (Android)** — the Visualizer, Decent and Drive
  tokens are sealed with a non-exportable Android Keystore AES-GCM key;
  existing plaintext files are sealed on first read. If the key is lost the
  app asks you to sign in again rather than failing.
- **No lost encryption key across tabs (web)** — two tabs opening at once can
  no longer each create their own key and strand the other's tokens; a token
  that can't be read asks you to sign in again.

## [0.0.6] — 2026-08-07

More reliable Bluetooth reconnects — the app recovers on its own after long idle periods, no more force-quit needed.

• Bean search finds any recorded term and tolerates typos
• See a bean's photo and details without opening its editor
• Water & maintenance items are easier to scan at a glance
• Shot log now flags when a pour ends before your stop target was reached

## [0.0.5] — 2026-07-31

### Fixed

#### Stop-at-weight reliability
- **Stop targets survive a DE1 reconnect** — the long-running "stop at weight is
  intermittent" report. A reconnect rebuilt the core and dropped every configured
  stop target; shots started at the group head never re-pushed them, so the shot
  ran with no stop of any kind and recorded an empty stop reason. Restarting the
  app "fixed" it, which is why it looked like a scale fault.
- **The Brew stop-conditions card reads the core's armed projection** instead of
  re-deriving from UI state, so it can no longer show a confident target the core
  never armed. A guard that cannot fire (no scale) is flagged inline, and "nothing
  will stop this shot" is stated rather than rendered as an empty card.
- **A manual tare mid-pour is refused** — taring during extraction corrupts
  stop-at-weight.
- **Last-shot diagnostics are frozen to disk** at shot completion and surfaced in
  Copy diagnostics, so a shot problem reported hours later still has its event
  trail. Tank level is logged only when it changes, which had been flooding the
  buffer at ~2.4 lines/s.

#### Water level
- Corrected for the sensor offset, shown in the user's units, and read against the
  machine's own refill point, with a configurable low-water warning in percent or
  millilitres.

#### Visualizer
- Dispensed water is uploaded at the ecosystem's 0.1x wire scale (it had been
  10x high), and the profile frame index is emitted as `state_change` so uploaded
  shots get their step bars.
- Per-shot upload and re-upload with confirmation toasts.

#### Machine
- The DE1 sleeps when Crema is quit, and its own user-presence sleep is armed.
- Google Drive sign-in reports a refused authorisation instead of hanging.
- Nightly builds no longer tell users on the latest nightly to update.

### Added

#### Service modes are visible
- Mode glyphs stay dimmed until the heater each mode draws on is up to
  temperature — one rule in the core, shared by all three shells, replacing a
  hardcoded 130 °C threshold that never lit for a 120 °C steam target.
- The temperature card retargets to steam or hot water while that mode runs,
  demoting rather than dropping the group reading.
- The Phase card carries the running mode's progress against the firmware
  timeout — it has no profile frames to show during a service mode anyway.

### Changed
- Tablet layout fixes for ~8" and wide 240 dpi displays, and the Visualizer
  upload action stays reachable when signed out.

[0.0.5]: https://github.com/geota/crema/releases/tag/v0.0.5

## [0.0.1] — 2026-06-29

Initial release. Crema is an open-source (GPL-3.0) companion app for the
[Decent Espresso DE1](https://decentespresso.com/) — a clean-room reimplementation
of the DE1 tablet experience as a fast, type-safe web PWA, with a parallel native
Android app. Both shells share one sans-IO Rust core for the Bluetooth protocol,
shot state machine, and domain model.

### Added

#### Brewing & machine control
- **Live brew dashboard** — real-time pressure / flow / temperature / weight
  telemetry, a multi-channel chart whose time axis auto-grows with the shot, a
  phase indicator, and shot-completion metrics (time, yield, ratio, peak pressure).
- **Quick Controls** — steam, hot water, and flush with configurable targets, plus
  auto-tare and stop-on-weight.
- **Profile library** — the 88 standard de1app profiles built in, plus create / edit
  custom multi-frame profiles and live-preview each profile's intended
  pressure/flow curve.
- **Group-head controller (GHC)** — surfaced read-only; Crema correctly defers to the
  firmware's group-head start gate rather than fighting it.

#### Data
- **Shot history** — every pour recorded locally with full telemetry curves, linked
  to beans and roasters, with multi-shot overlay comparison and round-trip
  community-v2 `.shot.json` import/export.
- **Bean & roaster library** — track bags, roast dates, and grinder settings, attach
  optional bean-bag photos, and retroactively rebind a shot to a bean with snapshot
  semantics.
- **Maintenance tracking** — water-filter, descale, and cleaning reminders with
  one-tap buttons that drive the DE1's built-in cycles.

#### Hardware
- **DE1 over Bluetooth** — connect, control, and stream telemetry via the DE1's
  public BLE GATT protocol, with the wire format verified against the de1app and
  reaprime reference implementations.
- **Bluetooth scales** — Bookoo Themis, Decent Scale, Acaia (Lunar / Pyxis / Pearl),
  Skale, Eureka Precisa, Hiroia Jimmy, Difluid, Felicita, Atomheart Eclair, Varia
  Aku, and Smartchef.

#### Sync & backup (all opt-in, local-first by default)
- **Visualizer** — OAuth 2.0 + PKCE sign-in and two-way sync of shots, beans, and
  roasters with last-write-wins conflict resolution.
- **Google Drive backup & restore** — whole-app backup (preferences, profiles, shot
  history, and the bean/roaster library including photos) to your own Drive,
  strictly user-initiated.

#### Platforms
- **Web PWA** — runs entirely in the browser, offline-capable, nothing to install;
  Bluetooth pairing needs a Chromium-based browser. Hosted at
  [crema.maceiras.dev](https://crema.maceiras.dev).
- **Android** — native Jetpack Compose app with dedicated tablet and phone layouts
  and background BLE, distributed via Google Play, IzzyOnDroid, and a nightly
  Obtainium train.
- **Shared Rust core** — protocol codecs, shot state machine, profile model, and sync
  logic compiled to WebAssembly (web) and exposed via UniFFI (Android), so both
  shells stay in lockstep.

### Notes
- Crema is **unofficial** and not affiliated with Decent Espresso.
- This is an early release, built with heavy LLM-assisted development, and provided
  **as is** with no warranty — see the [Terms](https://crema.maceiras.dev/terms).
  Take particular care with machine-control settings (mains voltage, calibration,
  firmware updates).

[0.0.1]: https://github.com/geota/crema/releases/tag/v0.0.1
[0.0.6]: https://github.com/geota/crema/releases/tag/v0.0.6
