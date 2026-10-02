# Changelog

All notable changes to Crema are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and Crema aims to follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed

- **Visualizer edit sync no longer fails or loses edits on free accounts** —
  Premium-only fields are skipped; Premium status is checked on sign-in,
  daily, and on Test connection. Web and Android.
- **Decent uploads send the DE1 firmware as its build number** — the
  shot's `machine.firmwareVersion` is now just the CPU firmware build (e.g.
  `1352`), the same value Decent's own app sends. Shots recorded earlier
  with a longer label (`v1.43 build 1352`) are normalised when uploaded;
  a label with no build number in it is left out. Web and Android.
- **Editing an uploaded shot updates its Decent copy** — with Decent
  auto-upload on, changing the rating, notes, grind or bean of a shot that is
  already on your Decent account re-uploads it over the old copy
  (`replace=1`), once, a moment after you stop editing. Same trigger as
  Decent's own shot-upload plugin. Web and Android.

- **Descale, clean and air purge work on a cold machine with older DE1
  firmware** — firmware below build 1356 silently ignores those requests
  while the machine is still heating. On such a machine (or one whose build
  hasn't been read) Crema now loads a one-step 1 °C profile, waits for the
  machine to report it has stopped heating, then sends the request, and puts
  your profile back afterwards (Decenza `b1ceab8c`, de1app, decaid). Firmware
  1356 and newer get the plain request as before. Settings → Water shows the
  running cycle with a Cancel button; a descale reads its real progress from
  the DE1's fixed 12-minute step schedule ("42% · Step 4 of 5 · 7 min 0 s
  left"). Web, tablet and phone.
- Calibration's 'Reset to factory' is hidden until the DE1 command is verified
- **Both flow readings now share the Flow card** (#92) — machine flow (ml/s)
  on the left, scale flow (g/s) on the right, so the two numbers being compared
  sit side by side. Dispensed water volume moved to the Weight card next to the
  scale weight. Web, tablet and phone.
- **Roaster cards open the roaster's shelf** (#86) — tapping a roaster in
  Beans → Roasters now shows that roaster's bags on the Bags tab, archived ones
  included (dimmed), with a pill to return to the full library. Roaster cards
  also say how many of their bags are archived. Editing stays on the pencil.

### Added

- **Adaptive v3 built-in profile** — de1app's update to Adaptive. It exits
  Pressurize at 7.7 bar (8.8 in v2) and limits extraction at 8.6 bar (9.5).
  Adaptive v2 stays available.
- **Stolen-machine check** — when the connected DE1's serial is on the list
  Decent publishes of machines stolen from customers or lost in transit,
  Settings → Machine shows a one-line notice. Nothing is blocked. The list is
  fetched at most once a day and cached; if it can't be fetched, nothing is
  shown. Web, tablet and phone.
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

- **Visualizer shows the first profile step** — the step markers Crema sends
  with each shot (`state_change`) counted frames from 0, and Visualizer reads 0
  as "no marker", so the step out of the first frame never showed. Markers now
  count from 1, and importing a Crema v2 shot file reads the frames back.
  Re-uploading a shot first uploaded by an older version makes a new copy on
  Visualizer rather than updating the old one (Visualizer matches re-uploads by
  their exact data).

- **Visualizer notes no longer show HTML tags** — Visualizer switched shot
  notes (bean, espresso, private) and coffee-bag notes to rich text in July.
  Pulled notes came into Crema with `<p>` and `<br>` in them, and notes Crema
  sent were stored as one run-on line. Crema now converts at the wire: rich
  text becomes plain text (paragraphs, line breaks, lists and `&amp;`-style
  characters kept) on pull, and plain text becomes paragraphs on push. Older
  plain-text notes still read as they are.
- **Visualizer's 30-shots-a-day free plan stops a backlog upload cleanly** —
  when a free account reaches its daily cap, "Upload all", the Settings
  catch-up and Sync now stop after the first refusal instead of trying every
  remaining shot, and say "Visualizer's free plan uploads up to 30 shots a day
  — the rest will upload tomorrow". The rest stay unsynced for the next pass.
  Web and Android.
- **Visualizer rate limiting is retried** — a "too many requests" reply (429)
  now waits a minute (then longer) and retries, instead of failing the shot.
  Web and Android.

- **Beanconqueror brew times** — Beanconqueror stores a brew time as whole
  seconds plus the sub-second remainder. Crema read the remainder as the whole
  time on import (a 28.45 s brew came in as 0.45 s) and wrote the total into
  the remainder on export (Beanconqueror showed about double). Both directions
  now use seconds + remainder; a value of 1000 ms or more is still read as a
  total. Brews imported before this fix keep their old (wrong) times — they
  can't be told apart reliably, so re-import from Beanconqueror to correct them.

- **Water tank readouts no longer read ~5 mm (~135 ml) high** — the sensor offset was being added twice; tank ml, %, depth and the "refill soon" cue now match de1app and Decenza. Web, tablet and phone.
- **No more false "water low" warnings while the pump runs** — the tank
  sloshes by about a third of its depth under the pump; the level is now
  smoothed over ~3 s in core (Decenza parity), so the readout and the low-water
  warning follow the real level. Web, tablet and phone.
- **No surprise sleep right after a refill** (Android) — on DE1 firmware older
  than 1357, a sleep requested while the machine is asking for water is held by
  the firmware and fires the moment the tank is refilled. The screensaver and
  sleep-on-quit no longer send it in that state.
- **No "front power switch is off" flash while the machine heats** — the DE1
  briefly reports that fault on every wake and warm-up. Crema now shows it only
  on firmware 1337+ and only once it has lasted 6 s (Decenza parity), so the
  `machineError` webhook no longer fires on a heating machine.
- **The shot-start auto-tare waits for a still scale** — taring a load cell
  that is still ringing (cup just put down, GHC just pressed) baked the wobble
  in as the zero, and stop-at-weight then stopped grams late. The tare now
  fires once the last four readings sit within 1 g (Decenza parity), with the
  old tare-at-first-flow as the fallback; a cup put down during preheat gets
  its own settled re-tare. The scale's residual zero at first flow (up to 2 g,
  only after a tare was seen to land) is corrected for the whole shot, so the
  stop and the saved yield are no longer short by the drift.
- **The firmware check knows DE1 firmware v1358** — the latest release (cold
  maintenance, sleep and air-purge while out of water), so a v1352 machine is
  now offered the update.
- **The cup-warmer card is Bengle-only** — Bengle is machine model 128 and up
  (de1app and decaid agree). Crema was gating it on models 4–7, which are the
  DE1XL / CAFE / XXL / XXXL, so those machines were shown a cup-warmer control
  for hardware they don't have and a real Bengle didn't get it. Bengle also
  now shows its name in the machine model row.
- **The steam heater turns off during Clean, Descale and Air purge** (de1app
  parity) and back to your setting when the cycle ends; your saved steam
  temperature is never changed.
- **Calibration readouts only show the machine's stored values** — the DE1
  echoes every calibration read and write back on the same channel, and Crema
  used to read those echoes as values. Only real value replies (WriteKey 0) are
  shown now, and the number shown is the stored value field every reference app
  reads. Needs a check on a real machine.
- **No false "First step skipped" on fast-filling profiles** — the shot
  summary now confirms a frame's pressure / flow exit when one more sample's
  worth of the observed rise reaches the threshold (Decenza parity), instead of
  a fixed 0.1 margin; a flat or receding reading gets no allowance.
- **A DE1 that disconnects mid-shot stops the scale's timer** — the scale stays
  connected and used to keep counting.
- **Android backups keep brew details** — a backup made on Android dropped
  each brew's method, recipe name, water, temperature, next-time plan and
  guided weight curve; they now survive backup and restore.

- Guided brews let you choose the bean before you start (issue #10 feedback)
- **Built-in profiles' preinfusion handling and stop-at-volume now match
  de1app.** Basic pressure and flow profiles now tell the DE1 how many of
  their leading steps are preinfusion, counting the pressure profiles'
  "forced rise" steps, exactly as de1app works it out. 24 built-ins
  (Default, Best overall pressure profile, Classic Italian, the lever and flow
  profiles…) used to send 0. Stop-at-volume now counts only the water poured
  after preinfusion, so it no longer stops a shot early. Basic profiles take
  their volume and weight targets from the keys de1app uses, which corrects
  stale 135 / 180 / 74 ml volume targets. Built-ins now carry their own
  stop-at-weight target instead of a flat 36 g (for example Blooming Allongé
  135 g, Filter 2.x 100 g). Every built-in's preinfusion count, volume and
  weight target was checked against de1app's own calculation.
- **Per-step weight exits work.** A step's weight target (A-Flow's Infuse:
  2–4 g) used to be dropped when profiles were loaded, so A-Flow held its
  Infuse step for up to 60 s. It is now kept, and with a scale connected
  Crema moves the DE1 to the next step when the cup reaches that weight. It
  allows one skip per step and re-sends the skip if the DE1 doesn't move on.
  It never skips the last step while a stop-at-weight target is set. The
  weights follow the profile you select, so they also work after a page
  reload or app restart when the DE1 already holds the profile and Crema
  skips the re-upload.
- **Imported `.tcl` profiles keep multi-word titles** — Visualizer's `.tcl`
  downloads leave titles like `D-Flow / Q` unbraced, which failed to import or
  scrambled the profile. Title, author and notes now read to the end of the
  line. The dose is also read from de1app's `profile_grinder_dose_weight`.

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
