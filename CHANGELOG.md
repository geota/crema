# Changelog

All notable changes to Crema are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and Crema aims to follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.10.0] — 2026-10-30

### Added

- **Hub mode** — Settings → Multi-device has a Hub mode switch, applied live
  with no restart: on, this device holds the machine and other devices connect
  through it as remotes; off, it disconnects from the machine so another device
  can connect (tap Connect to take it back). A device that wants the machine
  while another device is the hub is told to turn hub mode off there, and can
  connect through that hub instead. A remote shows "Connected through ‹hub›"
  on every screen, and offers Connect directly when its hub is gone or no
  longer holds the machine. Tablet and phone.
- **A remote finds its hub again after the hub restarts** — the hub keeps its
  relay port across restarts, so its remotes simply reconnect. If that port
  was taken and the hub comes back on another port of the same address, a
  remote whose link has been down for 5 seconds reconnects to the new port on
  its own. It never dials another address, and only connects to a hub that
  proves the pairing this device already holds: anything else is refused,
  never paired with, and after three refusals the remote shows "Couldn't
  verify ‹hub›" with Connect directly. When the hub moved to another address,
  pick it again under Other devices. When the hub turns Hub mode off, the
  remote says "‹hub› isn't a hub any more". Tablet and phone.
- **A remote shows its hub's machine and session settings, and keeps its own**
  — steam, hot water and flush, the shot behaviour toggles, the grinder, idle
  sleep, the water refill point, the brew dials, profile and bean come from the
  hub, live; units, theme and the other personal settings stay this device's
  own, so a remote no longer follows the hub's units. The hub's values are
  never saved over this device's settings. A remote changes what it may through
  the hub — the brew dials, grind, stop at weight, auto-tare, profile and bean —
  and each edit shows "Sending to ‹hub›…" until the hub answers. Machine
  settings, including Quick Controls' steam, hot water and flush and the steam
  heater on/off, are read-only on a remote and say "Change on ‹hub›"; a
  view-only remote changes nothing. A value the remote doesn't know yet shows
  "—" ("Waiting for ‹hub›", or "Update Crema on ‹hub› to see this here" with an
  older hub). Tablet and phone.
- **Leaving a hub asks about its settings** — Stop, Connect directly and
  turning Hub mode on, from a remote whose hub's settings differ from this
  device's, ask once: "Use ‹hub›'s" makes the hub's machine and session settings
  this device's own (its custom profile included), "Keep mine" keeps this
  device's. Either way this device then writes its own machine settings to the
  DE1 when it connects. Tablet and phone.
- **Bengle machines receive profiles in their own encoding** — Crema waits for
  the machine model before uploading, and a stop-at-weight target another app
  saved in a Bengle is cleared on connect, even when no upload is needed.
- **The web app notices a DE1 link that has gone silent mid-session** and
  reconnects it, as Android already did.
- **A System theme** that follows the device's light or dark setting. Web.
- **Barista and drinker on each shot** — the History detail has Barista and
  Drinker fields, with the names you've used before offered as you type
  (most recent first). Settings → Brew defaults → Barista fills in the
  barista on every new shot and brew. They export where the formats have
  fields: de1app's `my_name` / `drinker_name` in a `.shot.json` (Visualizer
  reads `my_name` as its barista), Visualizer's `barista` on an edit, and
  decaid's `baristaName` / `drinkerName` on a Decent account upload. Visualizer
  has no drinker field. Importing a `.shot` / `.shot.json` that carries
  `my_name` / `drinker_name` (de1app's, or one Crema exported) reads both
  back. Web, tablet and phone.
- **History's bean filter picks a coffee, not just a bag** — choosing a coffee
  lists every bag of it (same roaster and name, ignoring case and spacing; a
  roaster you merged counts as the one it was merged into). A bean's "See all
  N shots" still opens just that bag, shown as "This bag", and choosing its
  coffee widens to all its bags. Web, tablet and phone.

### Changed

- **Connections between Crema devices are now encrypted.** Pair your devices
  again once: on the hub, open Paired devices ▸ Pair a device; on the phone,
  choose the hub and type the code it shows into the hub. Update every device:
  older versions can't connect. Pairing takes a 6-digit code shown on the phone
  and typed on the hub, which also chooses View and control or View only;
  three wrong codes pause pairing for a minute. After that the devices
  reconnect on their own, and only to each other: a device on the network that
  only claims to be your hub is never trusted. Forget on either device ends the
  pairing at once, and Reset pairing key starts this device over. The keys are
  kept in the Android Keystore and never leave the device in a backup.
  Tablet and phone.
- **The DE1's steam / hot-water settings always hold what Crema has loaded**,
  so the machine's own hot-water button pours the same amount as the app: the
  settings follow the hot-water amount, a scale connecting, going quiet or
  leaving, and the active profile. They are re-sent on every connect, and a
  change made by another app is put back once per connection. While a shot,
  steam, hot water or a flush runs, a steam or hot-water change waits until it
  ends. Web, tablet and phone.
- **The DE1's steam settings flag byte is sent back as the machine reports
  it**, as reaprime does; de1app and Decenza always send 0. Web, tablet and
  phone.
- **The espresso group temperature in the steam / hot-water settings is the
  active profile's**, including a Quick Controls brew-temperature change, as
  de1app and Decenza send it. Web, tablet and phone.
- **Basic pressure profiles limit flow during the forced rise**, and pressure
  steps without a flow limit — cleaning and calibration profiles included — are
  capped at 8 ml/s, matching de1app and Decenza. A profile an earlier version
  already loaded is sent once more after the update so it picks these up. Web,
  tablet and phone.
- **A dead DE1 Bluetooth link is detected in about 10 s** instead of about a
  minute. Web and Android.
- **Steam eco mode waits for 10 minutes without any app activity**, as in
  de1app; touching the app returns steam to its normal temperature.
- **Larger Quick Controls touch targets, all bean filters visible on tablets,
  and animations respect the reduced-motion setting.** Web.
- **Less CPU and battery while the screensaver is up**, and smoother History
  and Profiles lists. Android.
- **The web app no longer ships the PWA plugin's unused registration script.**
- **The web app shell now runs on SvelteKit 3** — no visible change; routes,
  deep links, offline caching and the install prompt behave as before. For
  maintainers: `svelte.config.js` is gone (configuration lives in the
  `sveltekit()` plugin in `web/vite.config.ts`), imports use `#lib/...` with
  explicit extensions, and the service worker is built with
  `@katama-labs/vite-pwa-sveltekit`, a published copy of the pending upstream
  SvelteKit 3 support for `@vite-pwa/sveltekit` (#172 tracks switching back;
  a weekly workflow watches npm for the official release).
- **History search finds more** — it now also matches the coffee's name and
  roaster, the grinder, and the barista and drinker. Web, tablet and phone.
- **Reset preferences no longer unpairs this device** — it keeps the
  multi-device role, the hub address, the paired devices and the remembered
  DE1 and scale, so nothing has to pair again afterwards. On a remote it reads
  "Reset this device's preferences" and leaves the hub's settings on screen.
  Tablet and phone.

### Removed

- **Hand-off and take-over between devices** — the "Take over the DE1" and
  "Hand off" actions are gone; to move the machine to another device, turn off
  hub mode on the hub. Nightly users: update every device — devices on an
  older nightly can't connect to updated ones. Tablet and phone.

### Fixed

- **Hot water stops by weight above 250 ml no longer silently fails** — the
  amount is capped at 250 ml, the most the DE1 is asked for with a scale. Web,
  tablet and phone.
- **A scale that disconnects no longer leaves the next hot-water pour running
  to 250 ml unwatched**, and a scale that connects after the DE1 turns the
  weight stop on for the machine's button too. Web, tablet and phone.
- **A hot-water pour from the machine's button right after a reconnect still
  stops by weight.** Web, tablet and phone.

- **A second Start can no longer stop and restart a running shot, and Stop
  during the pre-shot flush no longer starts the shot** — a shot start is now
  one sequence: while it runs (profile upload, pre-flush, waiting for the
  machine) the Coffee button reads Stop, a second Start is ignored, and Stop,
  any other mode, or a disconnect cancels it. Before, a second Start during the
  pre-flush made a second sequence whose late Espresso request stopped the
  shot and restarted it on the spent puck, and stopping the pre-flush went on
  to the shot. After the pre-flush, the shot now starts only if the machine is
  back in Idle (a group-head button pressed mid-flush wins), and an Espresso
  request while the machine is already pulling a shot is ignored instead of
  passing through Idle. If the machine doesn't start the shot (one set to
  start only from the group head), Crema now says to start it on the machine.
  A mirroring tablet no longer sends its own post-steam purge to the primary,
  where it ran a second flush and could cancel the primary's shot start. Web,
  tablet and phone.
- **The heater voltage is written the way every Decent app writes it** — the
  mains voltage is now sent as 120 or 230; earlier builds sent 1120 or 1230,
  which no other DE1 app does. Web, tablet and phone.
- **An interrupted or stalled profile upload is no longer reported as loaded** —
  a Bluetooth error part-way through could leave the DE1 holding a mix of two
  profiles, which the next start or a group-head press brewed. A failed or
  stalled upload is now treated as failed, and Crema re-uploads your profile
  once the machine is idle (asking for about half a minute at most when
  nothing can answer). Switching profiles quickly, or starting a shot while
  Crema re-uploads, no longer fails the upload or drops the start, and a late
  report from a replaced upload can no longer fail the new one. Web, tablet and
  phone.
- **A step that ends on weight and also has its own pressure or flow exit no
  longer skips the step after it.** Web, tablet and phone.
- **Calibration changes are refused unless the machine is idle or asleep**, and
  implausible values (zero, not a number, out of range for the sensor) are
  rejected with a reason. Web, tablet and phone.
- **No more group-head-controller mode write before every shot** — the
  register is treated as unsafe by other DE1 apps. Steps can run up to 127 s,
  a profile's tank temperature is sent to the machine (and a profile without
  one no longer keeps the previous profile's tank target), and a steam timeout
  of 0 no longer stops steam immediately. Web, tablet and phone.
- **Profiles longer than the DE1's 20-step limit are flagged** with how many
  steps to remove instead of failing at Start, and recipes in pulled shots stay
  readable. A cup-warmer change made right after connecting is applied once the
  machine is identified, and an unrecognised heater-voltage reading says so
  instead of showing a wrong voltage. Web, tablet and phone.
- **On Android, a retried "stop" can no longer land after the next shot has
  started**, a write that times out no longer cancels the writes queued behind
  it, and a stop cancels only the write in progress.
- **Reloading during the firmware update's offline check no longer tells you
  to power-cycle early.** Web.
- **Android no longer drops the DE1 link during a quiet stretch of a firmware
  update.**
- **The built-in cleaning profiles are no longer saved to history as shots** —
  forward flush and Weber Spring Clean were typed as espresso, so they were
  recorded, debited from the bag and uploaded. Tea, pour-over, calibration and
  manual built-ins now carry their real type too. Web, tablet and phone.
- **On older DE1 firmware, Descale, Clean and Air purge wait for the cold-start
  profile to load** before they start; if it cannot load, or you load another
  profile while the machine is still heating, the cycle does not run and the
  app says which. Web, tablet and phone.
- **Original Decent Scales (firmware v1.0/v1.1) show weight again**, so
  stop-at-weight works with them, while Half Decent Scale frames with a bad
  checksum are still dropped. Also: Acaia scales that miss the first handshake
  start streaming without a reconnect; Eureka Precisa and Solo Barista commands
  use the write type those scales expect; a Decent Scale tare after a reconnect
  is no longer ignored; Hiroia Jimmy negative weights were 0.1 g off; Difluid
  timer stop also resets the on-scale timer; Timemore Dot stop-at-weight uses
  de1app's 0.5 s sensor lag.
- **Corrupt values no longer break the web app** — importing a shot file with a
  huge time value, or restoring corrupt flush or warm-up timeouts, could stop
  machine and scale processing until reload or break every DE1 connect (on
  Android, the connect-time settings were dropped); a corrupt duration no
  longer shows a 24-hour shot ending a day later. If the web app's engine ever
  crashes, it recovers and asks you to reload once the machine is idle,
  because shot settings such as stop-at-weight are off until you do (closing
  that notice no longer hides a later one); after repeated crashes it stops the
  engine, says so in an alert whose Reload works even mid-shot, and logs the
  stop once instead of an error per Bluetooth notification.
- **Nightly Android builds no longer crash on conditions that stable builds
  tolerate**, and `./gradlew assemble` / `build` package the optimized Rust
  library in release and nightly APKs.
- **A DE1 at the edge of range no longer fails its first connect outright** — a
  link that keeps coming up silent now backs off and shows the stuck-link hint,
  Crema still reads the machine's model, serial, firmware build and heater
  voltage after a shaky first connection, and Android no longer shows "Ready"
  before the DE1 has reported its state. Returning to the tab no longer
  reconnects a device you had just switched away from. Web, tablet and phone.
- **The update notice's "Reload" waits if a shot has started**, a newer update
  replaces an older offer, the offer comes back if Reload was tapped while the
  machine was busy, and the web app can be installed as a PWA again (the
  manifest link was missing). Pinned notices no longer cover the Coffee or
  Flush buttons, and their buttons are easier to tap. Web.
- **Clearing a shot's barista now clears it on Visualizer too** — even when
  Visualizer was unreachable at the time — and editing a shot in Crema (even its
  rating) no longer erases a barista stored on Visualizer; shots pulled from
  Visualizer show their barista. Web, tablet and phone.
- **Android barista and drinker names save when you stop typing**, not on every
  keystroke; search finds a shot's own grinder model; untitled shots read
  "Untitled shot" on every platform. The bean editor opens for bean ids with
  special characters, and three buttons got their icon spacing back.
- **A Visualizer outage or a dropped connection no longer signs you out** —
  only a refused sign-in does. Requests firing at once no longer race each other
  into a sign-out, a refresh cut short by a timeout no longer loses the rotated
  token, a request that never answers no longer stalls syncing, and pending
  Visualizer deletes are kept until you sign in again. Web.
- **If the web app's engine fails to download, Crema says so** with a Reload
  button instead of a bare error page. Deleting a bag from an editor opened from
  a roaster's shelf returns to that shelf.
- **"Sleep on quit" now puts the DE1 to sleep when you swipe Crema away** (and
  reopening right after no longer turns it off for the new session), and
  backing up — including the daily Google Drive backup at launch — no longer
  freezes the app on a large history. After a firmware update the
  background-connection notification no longer lingers with its wake lock, and
  tablet layouts follow the real window size in split screen and on foldables.
  Android.
- **The Brew start button is fully visible on 1024x768 tablets** (the DE1
  tablet's size) and other tablet sizes; short and phone-size screens scroll
  instead of hiding it, and the Quick Controls sheet works at phone width.
  Web.
- **Keyboard, screen-reader and light-theme fixes** — Quick Controls opens as a
  dialog that Escape closes, with its hidden controls out of the tab order;
  keyboard actions on bean and roaster cards do what they say instead of
  opening the drawer; the Profile and Bean pickers return focus when closed;
  screen readers hear which mode, preset and Settings option is selected; text
  meets WCAG AA contrast in light and dark themes, switches and text fields
  have visible edges, and the light theme paints from the first frame; bean
  editor fields, searches, filters, menus, ratings and charts are announced
  correctly; reduced-motion users no longer get animated scrolling. Web.
- **Factory reset erases everything** — on the web it removes shot telemetry,
  captures, bag photos and sign-ins, revokes Visualizer and Google Drive access,
  and stops other open Crema tabs from writing data back. Android "Erase all
  data" deletes every copy of your data, including backup copies, bag photos,
  recipes, sign-ins, the Visualizer and Decent activity logs and sync settings,
  and Bluetooth session captures; Android keeps at most 10 captures, none older
  than 14 days, and disconnecting Google Drive now revokes access at Google.
- **The privacy policy lists every service Crema contacts**, including the
  Decent shot upload, Google Fonts and the Google favicon lookup for roaster
  websites.
- **Restoring a backup asks before applying a webhook URL, wider Visualizer
  sharing, or turning on uploads, background sync or profile sharing** (web),
  showing a webhook's real host, and keeps the device's sharing settings
  (Android). Very large or malformed backup and import files are refused with a
  clear message, and bean and roaster links open only when they are web
  addresses.
- **Security hardening** — a narrower Content-Security-Policy for Google
  sign-in, a stricter webhook URL check, no leftover copies of Android sign-in
  files, no silent unencrypted storage of web sign-ins, and a Visualizer
  sign-in that cannot be stored encrypted now fails with a clear message
  instead of showing "Connected".
- **The web app now works offline and opens instantly** — the offline app
  shell (including the wasm core) had been built on every deploy but never
  switched on: nothing registered the service worker. It is registered now,
  so a return visit loads from the device even without Wi-Fi. A new release
  is never applied behind your back: an "updated — Reload" notice appears
  once the machine is idle (never mid-shot, mid-brew or during a firmware
  update), and the next launch picks it up regardless.
- **Restored and pulled shots lost their charts (web)** — a backup restore, a
  Visualizer pull and a Beanconqueror import stored each shot's summary row
  but never its telemetry series, so every such shot showed an empty chart
  and 0 samples after the next page load. The series is now written with the
  row.
- **History kept showing a shot the filter had hidden (web)** — with a search
  or a profile / bean / tag filter active, the detail pane stayed on the
  previously picked shot even once it no longer appeared in the list. It now
  shows the first matching shot, except that a shot you were sent to (a
  link, or a brew log you just saved) always opens, even when a remembered
  filter hides it.
- **A bag's "Shots" list showed "—" for every non-espresso brew** — the
  bean profile's recent-shots rows only ever read the espresso profile name,
  so a logged V60 or a custom-method brew (an "ORB recipe", say) had no
  title. The rows now use the same name History does: the profile, else the
  recipe, else the method. Web, tablet and phone. (#165)
- **A link that came up at the edge of range could read "Ready to brew" with
  no machine behind it** — GATT connected and subscribed, but the DE1 never
  answered the connect-time machine-state read, and an unknown state was
  shown as ready. "Ready" now needs a state the DE1 actually reported; until
  then the Brew hero says "Syncing with the DE1…". A link that cannot
  produce the state in two tries is treated as hollow and reconnected rather
  than kept. The last reported state is also cleared the moment a link drops,
  so a reconnect never replays "Idle" from before it. Web, tablet and phone.
- **A DE1 holding a dead link after a drop looked like "looking for the
  DE1" forever** — the DE1 takes one connection and advertises only while it
  has none, so a drop at the edge of range that never delivered a clean
  disconnect leaves it invisible until its own timeout or a power cycle.
  After two reconnect attempts that find nothing, the status now says so,
  with the remedies in order: toggle this device's Bluetooth, then
  power-cycle the DE1. On Android, when this device's own Bluetooth stack
  still holds the link, the app now reuses that link directly instead of
  scanning for an advertisement that cannot come.

### Notes

- **Multi-device: update every device together.** Remotes and hubs on
  different builds don't fully interoperate. A remote on an older nightly
  still saves the hub's settings over its own and still sends steam, hot water
  and flush changes, which a newer hub applies. A newer remote shows "—" for
  the settings an older hub doesn't send, and asks to adopt only the ones it
  does. An older hub still shows Hand off next to a newer remote, which
  ignores it (tapping it waits forever).
- **What changes for remotes:** they stop following the hub's units; steam,
  hot water and flush (including the steam heater on/off) are read-only on a
  remote; leaving a hub asks before taking its settings, and Keep mine writes
  this device's own machine settings when it connects; turning off Hub mode
  disconnects the hub from the machine until you tap Connect; Hand off is gone.

## [0.0.9] — 2026-10-15

Fixes a crash on Android 14 and later when turning on "Keep connected in the background" while the DE1 was connected.

### Fixed

- **Turning on "Keep connected in the background" could crash Crema
  (Android 14+)** — with the DE1 connected and idle, the one-time "nearby"
  setup paused the link a few milliseconds after the keep-alive service
  started, and Android kills an app whose foreground service stops before
  it shows its notification. The service now always goes foreground first
  and stops itself if it's no longer wanted. The "nearby" setup also no
  longer crashes on a device address Android won't accept. Tablet and phone.

## [0.0.8] — 2026-10-14

New: update your DE1's firmware from Crema (alpha), on every classic DE1. Crema now puts the DE1 to sleep after an idle time you choose (Settings → Machine → Sleep after, default 30 min). Android has one simpler reconnect setting per device, with Forget. Fixes for Decent shot uploads and for DE1 and scale reconnects in the browser.

### Added

- **Firmware updates (alpha)** — Settings → Machine → "Update firmware…"
  installs DE1 firmware from Crema. It is alpha: it has not yet been tried
  on a real DE1, and a click-through warning says so first. Pick Decent's
  Stable build (1333) or the Nightly / early-access build (1358); Crema
  downloads it from Decent's own update server and installs it only if it
  matches a copy it has verified byte for byte. Updating takes about 15–25
  minutes with the device charging and near the DE1, and ends with
  switching the DE1 off and on; Crema then reads the build back to confirm.
  Until then, and while the DE1 erases or checks the new firmware, Crema
  sends it nothing else (no settings, profile or sleep commands, also after
  a reconnect), and the update screen stays up until the DE1 restarts or
  you exit; it has its own Connect button for reconnecting the DE1 after
  the restart. You can cancel, after a confirmation, until the DE1 starts
  checking; the DE1 is then put to sleep and should keep its current
  firmware, since Crema only writes its spare memory bank. A failure offers
  Retry. The steps follow Decent's de1app and decaid: sleep the machine,
  erase, send 16-byte blocks one at a time, let the DE1 verify, power-cycle.
  Every classic DE1 model (DE1, DE1+, PRO, XL, CAFE, XXL, XXXL; no Bengle),
  as Decent's de1app does. On Android the
  update keeps running in the background, with the DE1 link at high priority
  so each block goes out faster; in the browser, keep the tab open
  and in front (and if the browser can't download Decent's file directly,
  download it from Decent and choose it). Web, tablet and phone.

### Fixed

- **No "ghost" scale after a cancelled or failed scale connect** — tapping
  Disconnect while a scale was still connecting, Re-pair with the chooser
  then cancelled, or a scale handshake that failed partway, could leave
  Crema thinking a scale was attached: shots then waited for a weight stop
  that never came, and hot water could run on to 250 ml because Crema
  expected the scale to stop it. Web.
- **Reconnecting the DE1 in the browser no longer drops the new link** —
  connecting while Crema was still retrying the old connection could let
  that old attempt land afterwards, drop the new connection and show
  "disconnected". Crema now stops retrying the old connection once you
  connect or disconnect, and ignores anything it reports afterwards. Web.
- **A DE1 that isn't on your Decent account no longer retries forever.**
  Decent answers HTTP 403 when the machine's serial isn't registered to the
  linked account (de1app `5b09f6aa`). Crema treated that as a passing server
  error: it re-sent the shot twice more, queued it to retry on every
  reconnect, and a backlog upload stopped after three of them with "HTTP 403".
  Now the first 403 says once "This DE1 (serial …) isn't registered to your
  Decent account — add it at decentespresso.com, then uploads resume." Later
  automatic uploads from that machine wait quietly, and Upload missing shots
  holds that machine's shots back while still uploading another machine's.
  The shots stay in the backlog. They upload after you add the machine and
  then sign in again, restart Crema, or tap Upload on a shot. Web and Android.
- **An older DE1 that reports serial number 0 no longer has its shots filed
  under "0".** Decent can never accept them under that serial (Decenza
  `6c83ca06`). The upload now waits, and says "Crema couldn't read this
  DE1's serial number yet". Web and Android.
- **Editing an uploaded shot no longer claims Decent has the edit when it
  doesn't.** Decenza found that decentespresso.com answers every replace
  (`?replace=1`) with "duplicate" and keeps its earlier copy (`f5c3ce3e`).
  Crema now reports "Decent kept its earlier copy of this shot — the edit
  wasn't saved there", once per session after an edit, and every time in the
  last-upload line and Recent activity. The earlier upload and its link
  stay. Web and Android.
- **A Wi-Fi sign-in page is no longer recorded as a Decent upload.** Only
  Decent's own JSON answer counts as stored. Any other reply that comes
  back as a success, such as a captive portal's page or a proxy's, now
  fails and is retried like a network error. Web and Android.
- **Decent uploads from Android are smaller and no longer time out at
  30 s.** The shot is sent gzipped (`Content-Encoding: gzip`), as Decent
  asked app authors to do (de1app `bd4b8c0c`, Decenza `7caec84f`): a typical
  shot shrinks from about 340 KB to about 40 KB. The wait for Decent's answer
  is now 60 s, as in Decenza; the server has taken 25-38 s to answer. Before,
  a slow answer timed out and the shot was sent a second time. The web app
  still sends the shot uncompressed: decentespresso.com doesn't yet allow
  browsers to send compressed uploads.
- **A Descale or Clean tapped right after connecting is no longer dropped**
  on DE1 firmware below build 1356. Crema reads whether a Group Head
  Controller is fitted a moment after connecting; a request made before
  that read landed went out as a plain write, which that firmware ignores
  while heating. An unread GHC now counts as fitted, as Decenza does
  (`d5f405fb`): the worst case on a headless machine is a throwaway
  warm-up profile. Web and Android.
- **The DE1 now goes to sleep after it's been left alone** — Crema relied on
  a firmware sleep timer that a stock DE1 doesn't have, so the machine could
  stay hot indefinitely (always on the web; on Android whenever the
  screensaver was off, its machine-sleep switch was off, or the machine was
  refilling or reconnecting at that moment). Crema now puts the machine to
  sleep itself after an idle period, as de1app, Decenza and decaid do.
  Touches, keys and navigation in Crema, commands, returning to the app and
  anything the machine is doing (espresso, steam, hot water, flush, descale,
  clean) count as activity; scale readings don't. It never sleeps mid-shot,
  while steaming, descaling or cleaning, during a profile upload or a guided
  brew. If the sleep can't be sent yet (reconnecting, or refilling on
  firmware below 1357) it keeps trying every 30 seconds; once the machine is
  asleep it isn't sent again, and nothing is sent while disconnected. Waking
  the machine at the group head starts the timer over. On a Bengle the same
  timeout is also set in the machine's own firmware timer. Web and Android.
- **The firmware card no longer says "Up to date" without checking** — on
  Android it said so whenever a DE1 was connected. Settings → Machine now
  shows the installed build and what it means against a dated list of
  Decent's builds: "Build 1358 · newest", "Build 1333 · stable", "Build
  1352 · newer than stable", "older than stable", or "newer than Crema
  knows", with a short note naming Decent's builds. The 2023 build 1363,
  which Decent withdrew, reads as "withdrawn" instead of newer than 1358.
  "Update firmware…" sits at the bottom right of the card; on a phone it
  shares the note's row, so the card is a row shorter. Web, tablet and
  phone (the phone hero gains the card).
- **No more advice to get build 1358 from de1app's stable channel** — the
  web card used to say to, though de1app's stable and beta versions install
  build 1333, and its "Update firmware…" button ended in "not yet
  implemented". The card now says which build is newer, and the button
  opens the real update.
- **Firmware verify request matches Decent's apps** — the request that asks
  the DE1 to verify an uploaded image now sets the map flag the way de1app,
  decaid and Decenza do.

### Changed

- **One reconnect control per device (Android)** — Settings → Machine →
  Connection now shows "Remembered DE1" with a **Forget** button and "Keep
  connected in the background"; the scale gets the same "Remembered scale"
  row under Peripherals and on the Scale page, and the Devices sheet's
  Auto-connect switches became Forget. A remembered device always reconnects
  after a dropout and on launch, as in de1app and Decenza. The separate
  "Reconnect when nearby" row and the pop-up after connecting are gone: its
  "Set up" searched for a DE1 that was connected, so it never found it.
  Android's "nearby" association is now part of "Keep connected in the
  background" — set up once (when the setting is turned on, or shortly after
  a connect while it's on), on an idle machine: Crema pauses the link so
  Android can find the DE1, Android asks to confirm, and Crema reconnects.
  Tablet and phone; Tablet charging's "Always on" also reads "Always" on web.
- **New setting: Settings → Machine → Machine sleep → "Sleep after"** —
  Never, 15, 30, 60, 90 or 120 minutes; default 30. One setting on phone,
  tablet and web, carried in backups. Upgrading Android users who had changed
  the screensaver delay and had it put the machine to sleep keep that delay
  (rounded to the nearest choice); everyone else starts at 30.
  "Sleep the DE1 when you quit Crema" moved into the same group.
- **The screensaver is screen-only** — "Sleep machine with screensaver" is
  gone; the timer above sleeps the machine whether or not the saver is on.
  Tapping the saver while the DE1 sleeps still wakes it by default (as in
  de1app), and the setting now says that starts heating. Android.
- **"Keep DE1 awake while Crema is open" is gone** — Crema no longer pokes
  the DE1 every minute while it's on screen (Android) or on every touch
  (web); it tells the machine you're present only when you actually use
  Crema, at most every 30 seconds. Web and Android.

## [0.0.7] — 2026-10-08

Brew Log for filter coffee: guided pour-overs with credited recipes, your own brew methods, and bean usage tracking.

• Faster Bluetooth reconnects when you come back to the app
• Upload shots to Decent's shot history
• Visualizer bean & roaster sync on Android, plus catalogue search when adding beans
• Filter archived beans; lists keep your place
• Crash-safe saving, and many scale, profile and machine fixes

### Changed

- **The rail's DE1 / scale buttons follow the connection state** — the dot is
  green only when connected and amber while connecting or reconnecting. A tap
  connects when disconnected, does nothing while connecting, and retries now
  while reconnecting (it used to end the reconnect attempts). When connected,
  a tap opens a small menu with the device name and **Disconnect**, so
  disconnecting takes two deliberate taps. Esc closes the menu. Web.
- **The Visualizer catalogue asks before it replaces what you typed** — the
  "Replace fields I've already filled" checkbox is gone. Picking a catalogue
  bag fills every empty field at once; when a field you filled has a
  different catalogue value, a dialog ("Some fields are already filled in")
  names those fields and offers **Keep mine** (fill only the empty fields) or
  **Use catalogue** (replace them too; a catalogue blank never erases).
  Dismissing it changes nothing. Differences in case or spacing don't count.
  The roaster form now has the same catalogue search (roasters by name, with
  country), filling name, website and country and linking the catalogue
  roaster. Picking a bag also files it under the right roaster: one already
  linked to that catalogue roaster, else one with the same name (ignoring
  case, spacing and punctuation), else a new roaster with the catalogue's
  name, website and country. That roaster's differing fields are listed in
  the same dialog. Web, phone and tablet.
- **Visualizer API spec updated to v1.17.2** (from v1.8.2) for the catalogue
  endpoints. A roaster's local "duplicate of" tag now stays on the device
  instead of being sent as Visualizer's catalogue link.
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

- **Every machine setting Crema owns is re-applied on connect, from one list
  both apps share** — besides the fan, refill point, two-tap steam stop,
  steam / hot water and flush settings, the connect now also sets the steam
  heater's phase flow rates, the hot-water heater's idle temperature and the
  espresso warm-up timeout (de1app's heater tweaks and defaults), and the
  active profile's tank-temperature target, so they survive a DE1 power
  cycle. New **Tablet charging** setting (Settings → Machine) for a tablet
  powered from the DE1's USB port: it defaults to **Always** (the port is
  never switched off, as before; decaid's default). Opt in to **Smart**
  (keeps the battery between 55 and 65 %) or **High** (90–95 %), Decenza's
  smart-charging modes, and Crema switches the port on and off, re-checking
  every minute and leaving charging on when you disconnect. Web, tablet and
  phone.
- **A Half Decent Scale on firmware 3 or newer stays connected while the DE1
  sleeps** — when the machine sleeps, Crema now puts such a scale into its
  SoftSleep mode instead of switching its display off or powering it down,
  and wakes it when the machine wakes, so there's no reconnect. The scale is
  identified by its answer to a capability probe plus its firmware version
  (decaid `f6c91efe`, `38f5c0a8`, `46e8c224`); older and original Decent
  Scales keep the previous behaviour. Web, tablet and phone.
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

- **The web app reconnects your last DE1 and scale on launch**, where the
  browser lets a site list the devices it may use
  (`navigator.bluetooth.getDevices()`; in Chrome this is still behind
  `chrome://flags`). It waits for the device to advertise (up to 30 s) when
  the browser can watch for that, and connects without the chooser. A device
  you disconnected yourself is left alone until you connect it again; an
  unexpected drop keeps it eligible. Elsewhere nothing changes. Web.
- **Android: "Reconnect when nearby"** — after Crema first connects to a DE1
  or scale it offers, once, to let Android watch for that device (a one-time
  system confirmation filtered to that device). When it comes back into
  range Android tells Crema, which reconnects at once by address, even in the
  background, and may start the keep-alive service then (still only when
  "Keep connected in the background" is on and the device is charging).
  While the device is away Crema stops trying until Android reports it back
  (with one check every 30 minutes as a safety net). Turn it on, see its
  state or remove it in Settings → Machine (DE1) and the scale section.
  "Not now" is remembered. If the app has been closed completely, Crema
  reconnects when you next open it. Phone and tablet, Android 12 and later
  on devices with companion-device support.
- **Android: cheaper background reconnects** — once the quick retries after a
  drop are used up, Crema now hands the device to Android's own background
  connection (it connects the moment the device advertises, with no scanning
  by Crema) instead of scanning for it every minute. If that fails (the
  "GATT 133" some Bluetooth chips report), Crema goes back to scanning with
  the quick retries. Coming back to the app, Retry now and Bluetooth on still
  take the fast path straight away.
- **Reconnect timeline in diagnostics** — Settings → Advanced lists the last
  20 reconnects of the DE1 and the scale: what started each one (a dropped
  link, coming back to the app, Bluetooth turned on, launch, Retry now), how
  long it took to be ready, and how long each step took (waiting, scanning,
  connecting, finding services, subscribing, the post-connect reads or
  profile sync). Each one is also a line in the event log, and on Android in
  Copy diagnostics. Web, tablet and phone.

- **Android syncs beans and roasters with Visualizer** — Settings → Sharing →
  Sync gains Beans and Roasters direction rows, and **Sync now** runs the bean
  and roaster sync before the shots, as on the web: it pulls your Visualizer
  coffee bags and roasters into the library (Visualizer wins when both sides
  changed) and pushes new and edited ones back, with their catalogue links.
  Pushing needs Visualizer Premium; on a free account the sync only pulls and
  says so. Deleting a bag or roaster can also delete its Visualizer copy,
  and deleting a roaster can take its bags with it (as on the web).
- **Roaster duplicate merge on Android** — the Roasters tab suggests merging
  roasters with the same name ("Sey looks like SEY"). Merging moves the bags
  and tags the duplicate, which **Show dupes** brings back for an
  **Un-merge**. Web and Android now share one implementation of the
  duplicate detection, the merge and the bean-sync write bodies, in the core.
- **Adaptive v3 built-in profile** — de1app's update to Adaptive. It exits
  Pressurize at 7.7 bar (8.8 in v2) and limits extraction at 8.6 bar (9.5).
  Adaptive v2 stays available.
- **Stolen-machine check** — when the connected DE1's serial is on the list
  Decent publishes of machines stolen from customers or lost in transit,
  Settings → Machine shows a one-line notice. Nothing is blocked. The list is
  fetched at most once a day and cached; if it can't be fetched, nothing is
  shown. Web, tablet and phone.
- **Search the Visualizer catalogue from the bean form** — with a Visualizer
  account linked (free or Premium), the add/edit bean form has a "Visualizer
  catalogue" search. Type a roaster or bag name; pick a result to fill in the
  name, roaster, origin, process, variety, elevation, roast level, tasting
  notes and product link. Only empty fields are filled unless you tick
  "Replace fields I've already filled". The bag remembers which catalogue
  entry it came from. Web and Android.
- **Synced bags link to the Visualizer catalogue** — on Premium accounts, bean
  sync now sends the catalogue link with a coffee bag and its roaster, and
  pulls back links made on visualizer.coffee. A bag Crema never linked no
  longer clears a link set on the website. Web (Android has no bean sync yet).
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

- **Coming back to Crema reconnects at once** — returning to the app (or
  unlocking the phone onto it, or the browser tab coming back into view)
  now retries a dropped DE1 or scale immediately and restarts the fast
  retries, instead of waiting out the up-to-60-second quiet retry. A device
  you disconnected yourself stays disconnected. While a device is
  reconnecting, the Devices panel and Settings say so and offer **Retry
  now**. On Android the DE1 now really does look for the machine's
  advertisement before reconnecting (a short scan filtered to its address,
  which also works with the screen off), and the scan-free reconnect by
  address works again when Bluetooth comes back on: both were being
  silently skipped. Web, tablet and phone.

- **A crash or power loss mid-save can no longer wipe your data** — on
  Android, shot history, beans, profiles, recipes and settings are written to
  a temporary file and swapped in atomically, with the previous version kept
  as a backup (each browser value is already replaced atomically).
  A file (or browser-stored value) that can't be read is no longer replaced
  with an empty one: it is kept aside (`<name>.corrupt-<time>` on Android,
  `<key>.corrupt` on the web), the Android backup is used when there is one, and a
  one-time notice says what couldn't be read, with Share (Android) or
  Save copy (web) for the damaged data. A browser storage quota error is
  reported instead of silently not saving. Web and Android.

- **Archived beans can be filtered** ([#124](https://github.com/geota/crema/issues/124))
  — Archived is now one more status that combines with the roast, tag and
  roaster filters, the search and the sort, instead of a separate view where
  only sorting worked. A new **Include archived** chip shows archived bags,
  dimmed, alongside the rest under All or Favourite; it is off by default, so
  the list looks the same until you turn it on. Every chip count now reflects
  the other filters you picked, and the roast bands are the same on web and
  Android (a level-4 bag is Medium on both). Profiles get the same fix: the
  **Hidden** built-ins can be filtered by roast (and on web by beverage and
  tag) and searched. Android now shows tag chips on Beans too. Web, Android
  phone and tablet, with the filtering in the shared core.
- **Coming back to a list keeps your place** (#123) — closing a bean,
  shot or profile, backing out of an editor, switching tabs or filters, or
  rotating between the phone and tablet layouts no longer jumps the list
  back to the top or resets its filters, search and sort. Beans (every tab
  and filter combination, Include archived included), Roasters, History, Profiles and brew
  recipes, and Settings each return to the same item, even if it moved;
  if you archived or deleted it from its detail, you land next to where it
  was. Web and Android (phone and tablet).
- **Bean and roaster sync now follows the direction you picked** — a
  Beans or Roasters direction of **Pull** no longer writes to Visualizer on a
  Premium account, and **Backup** no longer pulls; Off, Backup, Pull and
  Two-way now each do exactly what they say. Web and Android.
- **Syncing beans no longer pushes every bag back to Visualizer** — a bag or
  roaster pulled from Visualizer kept a fresh "edited" time, so the next
  sync on a Premium account sent all of them back. A pull now keeps the
  bag's own edit time, so only bags and roasters you actually changed are
  sent, and a change you made here since the last sync is no longer
  overwritten by the Visualizer copy. Edited roasters are now sent too.
  Pulling from Visualizer also no longer clears a bag's local details —
  grams left, bag size, notes, origin, roast date, a roaster's website —
  because Visualizer's bag and roaster lists only carry names and links, and
  new bags and roasters now pick up their full details. Web and Android.
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

- **Stop-at-weight learning now remembers a real shot's drip and flow;
  corrupted learning data is kept aside instead of silently reset** — each
  batch of three shots used to store the median drip and the median flow
  separately, usually from two different shots, so the remembered pair
  described no shot that happened. It now stores the shot whose stop lag is
  the batch's median. This is a correctness fix, not an accuracy upgrade:
  Decenza, which made the same change, measured about 1% difference. A
  learning file that no longer reads is now copied aside (web:
  `crema.saw-model.v1.corrupt` in local storage; Android:
  `sawModel.corrupt.json`) with a warning in the log, and learning starts
  fresh, instead of being overwritten by the next shot.

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
[0.0.7]: https://github.com/geota/crema/releases/tag/v0.0.7
[0.0.8]: https://github.com/geota/crema/releases/tag/v0.0.8
[0.0.9]: https://github.com/geota/crema/releases/tag/v0.0.9
[0.10.0]: https://github.com/geota/crema/releases/tag/v0.10.0
