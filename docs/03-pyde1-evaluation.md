# 03 — pyDE1 & Reference-Implementation Evaluation

> Status: **Done** — drafted 2026-05-16.
> Scope: evaluate external reference implementations for the `de1-core` rewrite (see
> `01-feasibility.md`). Decide whether **pyDE1** is worth porting from, referencing,
> or ignoring; catalogue the authoritative protocol spec; survey other reference
> implementations; and summarize the Visualizer.com upload format.

> ### UPDATE 2026-05-16 — licensing resolved
> Crema is licensed **GPL-3.0-or-later**. The "license blocker" this document raises against
> pyDE1 (GPLv3 infecting a permissive core) **no longer applies** — pyDE1 and `de1app` may be
> freely referenced and adapted. The standing recommendation — write our own sans-IO
> `de1-core` rather than port pyDE1 wholesale — now rests *only* on the architecture-mismatch
> grounds (pyDE1 is an IO-coupled async service with no clean sans-IO core to lift out).

---

## ⚠️ Methodology note — read this first

**The research environment for this document had no live web access.** `WebSearch`,
`WebFetch`, and outbound `curl` were all denied. Every claim below is therefore sourced from
one of three places, and the source type is stated for each:

1. **[REPO]** — verifiable in this repository right now (the legacy Tcl app, its plugins,
   `documentation/`). These claims are solid.
2. **[KNOWN]** — drawn from prior knowledge of the DE1 ecosystem as of the assistant's
   training cutoff (early 2026). Directionally reliable but **version/date-sensitive facts
   (latest release, last commit, exact license file) must be re-verified online**.
3. **[VERIFY]** — explicitly unconfirmed. A human must check these before they are relied on.

A short checklist of exactly what to verify online is in [§7](#7-follow-up-actions). Treat
this document as a well-grounded starting map, **not** as a final, citation-complete report.

---

## 1. Executive summary & recommendation

**Recommendation on pyDE1: REFERENCE it, do not port it. Treat it as a second opinion, not a
source tree.**

- pyDE1 is the most authoritative *non-Decent* implementation of the DE1 protocol and shot
  lifecycle. It is written by **Jeff Kletsky**, who is also the author of this repo's
  `documentation/de1_app_core_shot_cycle_overview.md` [REPO — see that file's content and
  authorship style; cross-check the named author online, **[VERIFY]**]. That makes pyDE1 and
  our own shot-cycle doc effectively two expressions of the same person's mental model — high
  value as a cross-check.
- **But it is not a porting *donor*.** Two hard blockers:
  1. **License.** pyDE1 is distributed under the **GNU GPL v3** [KNOWN — pyDE1's source
     headers and `LICENSE` carry GPLv3; **[VERIFY]** the exact license text and whether any
     files differ]. GPLv3 is **viral and incompatible with shipping `de1-core` under a
     permissive license** (MIT/Apache-2.0, the Rust-ecosystem norm and what a UniFFI-bound
     library would normally want). Translating GPLv3 Python line-by-line into Rust produces a
     **derivative work** — the Rust core would then also have to be GPLv3, which would in turn
     infect the statically-linked Android binary. This is a legal decision, not a technical
     one: **do not copy or line-translate pyDE1 code into `de1-core`.**
  2. **It is IO-coupled, not sans-IO.** pyDE1 is a long-running **async Python service**
     (`asyncio` + `bleak` for BLE + an HTTP/MQTT API surface). Its protocol logic is
     interleaved with `await`ed BLE calls and database writes. `de1-core` is explicitly
     **sans-IO** (`rewrite/01-feasibility.md` §5). You cannot lift pyDE1's architecture; you
     would be re-cutting the same seam we already designed.
- **What pyDE1 *is* good for:** reading it to (a) confirm characteristic/packet semantics
  against `binary.tcl`, (b) see how a clean-room author modelled the **shot state machine**
  and **MMR registers** without DUI baggage, (c) sanity-check edge cases (reconnection,
  partial packets, firmware-version branching). Use it the way you would use a textbook:
  understand the idea, then write your own Rust.

**The primary spec source remains the legacy Tcl app in `de1plus/`** (`binary.tcl`,
`machine.tcl`, `de1_comms.tcl`, `de1_de1.tcl`) plus Decent's published GATT documentation.
pyDE1 is reference #3, behind those two. This matches `01-feasibility.md` §8 ("three
independent sources").

**Net:** budget time to *read* pyDE1, budget **zero** time to *port* it. Write `de1-core`
clean-room from `binary.tcl` + Decent's spec, and use pyDE1 only to resolve ambiguities.

---

## 2. Per-source table

> URLs marked **[VERIFY]** are best-known locations and must be confirmed; they are given so a
> human can navigate directly. The assistant could not open them in this run.

| # | Source | URL (verify) | Language | License | Maturity | Relevance to `de1-core` |
|---|--------|--------------|----------|---------|----------|--------------------------|
| 1 | **pyDE1** (Jeff Kletsky) | `github.com/jeffsf/pyDE1` **[VERIFY]**; PyPI `pypi.org/project/pyDE1` **[VERIFY]** | Python 3 (asyncio) | **GPL-3.0** [KNOWN/VERIFY] | Mature, multi-year, was actively maintained; **current activity [VERIFY]** | **High as a reference, zero as a code donor** (GPL + IO-coupled). Cross-check protocol & shot SM. |
| 2 | **Decent DE1 protocol / firmware docs** | `github.com/decentespresso/de1app` (this repo's upstream); Decent's GATT/firmware docs **[VERIFY exact location]** | Tcl + docs | repo-dependent **[VERIFY]** | Authoritative, continuously updated | **Primary spec.** `de1plus/binary.tcl` etc. are the canonical packet definitions. |
| 3 | **Legacy `de1app` (this repo)** | local: `/Users/adrianmaceiras/code/de1app` | Tcl/Tk | see repo `LICENSE` [REPO] | Production, shipping | **Primary porting source.** Battle-tested codecs & shot logic. |
| 4 | **`documentation/` in this repo** | `de1app/documentation/de1_app_core_shot_cycle_overview.md` etc. | Markdown | repo `LICENSE` | Current | **Primary** narrative spec for the shot state machine. |
| 5 | Decent **`john_de1` / DE1 firmware** & GATT tables | Decent-published **[VERIFY]** | C / docs | **[VERIFY]** | Authoritative | Source of MMR register map & characteristic UUIDs. |
| 6 | Community Rust DE1 projects | none confirmed **[VERIFY]** | Rust | — | **None known mature** [KNOWN] | If one exists, it is a candidate peer/competitor — check before starting. |
| 7 | Decentralized / community DE1 apps (JS/Swift/Kotlin) | various **[VERIFY]** | JS/TS, Swift, Kotlin | mixed **[VERIFY]** | mixed, mostly small | Low — partial protocol coverage; useful only for spot-checks. |
| 8 | **Visualizer.coffee** upload API | `visualizer.coffee/api/shots/upload` [REPO — endpoint hard-coded in plugin] | (server) | n/a (service) | Live service | Low for core; relevant in Phase 4 (`01-feasibility.md` roadmap). |

### Notes on the table

- **#1 pyDE1 URL:** Jeff Kletsky's GitHub handle is **`jeffsf`** [KNOWN]. The repo is most
  likely `github.com/jeffsf/pyDE1`. It was also published to **PyPI as `pyDE1`** [KNOWN].
  There may be a companion repo for the database/visualizer-bridge component. **[VERIFY all
  three.]**
- **#2 / #5 Decent docs:** Decent Espresso has historically published the DE1 BLE/GATT
  description and MMR map. As of training cutoff this lived in Decent-controlled repos and on
  `decentespresso.com`; the exact, current canonical URL is **[VERIFY]**. The `de1plus/`
  source in *this* repo is itself a faithful, continuously-updated encoding of that spec, so
  the repo is not blocked on locating the external doc — but the external doc is the
  tie-breaker when `binary.tcl` and pyDE1 disagree.
- **#6 Rust:** As of the training cutoff there was **no well-known, mature Rust DE1 crate**.
  `btleplug` (the cross-platform Rust BLE crate) is sometimes used for DE1 hobby scripts, but
  that is a *transport* crate, not a DE1 protocol library — and `01-feasibility.md` §5 already
  rules out `btleplug` for Android. **[VERIFY]** with a fresh crates.io / GitHub search; if a
  real Rust DE1 protocol crate now exists, it changes the build-vs-reuse calculus and should
  be evaluated before Phase 1.

---

## 3. pyDE1 architecture & mapping to `de1-core`

> This section is **[KNOWN]** unless marked otherwise. It describes pyDE1's design at the
> level the assistant is confident about; **module names and file paths should be confirmed
> against the actual repo** before being used to plan work.

### 3.1 What pyDE1 is

pyDE1 is a **headless Python 3 service** (a daemon, not a library and not a GUI). It runs on a
host near the machine (originally a Raspberry Pi), connects to the DE1 and to a scale over
BLE, drives shots, records them, and exposes a **control/observation API** (HTTP REST plus an
MQTT event stream) so that *other* front-ends — including web UIs — can be thin clients. It
also handles **uploading shots to Visualizer**.

So pyDE1 already embodies the "core vs UI separation" philosophy — the UI is a *different
process* talking over HTTP/MQTT. That is conceptually close to our Rust-core + native-shell
split, but the seam is a **network API**, not an **FFI byte/event contract**.

### 3.2 How it is structured (approximate)

pyDE1 separates concerns into roughly these areas [KNOWN — exact package layout **[VERIFY]**]:

- **BLE / transport layer** — built on **`bleak`** (cross-platform Python BLE). GATT
  characteristic handles, notification subscriptions, connection/reconnection logic.
- **DE1 device model** — a `DE1` object representing the machine: its characteristics, MMR
  read/write, firmware-version awareness, state requests.
- **Packet / characteristic codecs** — classes per characteristic that
  encode/decode the binary payloads (the fixed-point formats — analogous to `binary.tcl`'s
  `U8P4` / `U16P8` / `S32P16` etc.).
- **Shot / flow state machine** — tracking `State` and `SubState`, the flow phases, and
  the `ShotSampleUpdate` / `StateUpdate` event stream.
- **Stop-at-weight / stop-at-volume** — logic that watches scale weight / estimated volume
  and issues the stop.
- **Scale abstraction** — a scale base class plus concrete drivers (notably Acaia, Atomax /
  Skale; fewer scales than the legacy Tcl app's ~12).
- **Profile model** — load/normalize DE1 profiles (the JSON v2 frame-based format), and
  upload them to the machine frame-by-frame.
- **Shot recording / database** — persisting shots (SQLite) for later upload.
- **API surface** — an `asyncio` HTTP server + MQTT publisher; this is pyDE1's "outbound"
  layer and the part most foreign to `de1-core`.
- **Supervisor / lifecycle** — process management, config, logging.

### 3.3 Is it sans-IO? — No.

pyDE1 is **IO-coupled by design**. Its protocol objects directly `await` `bleak` calls; its
shot recorder writes SQLite inline; its API layer owns an HTTP/MQTT server. There is *logical*
layering (codecs are separable from transport), but there is **no sans-IO core** you can lift
out wholesale. Extracting a pure state machine from pyDE1 would itself be a refactor.

`de1-core`'s contract (from `01-feasibility.md` §5) is the opposite: synchronous, no async
runtime, `on_de1_notify(uuid, &bytes)` in / `CoreEvent::WriteCharacteristic{..}` out. pyDE1
shows you *what* logic is needed; it does not hand you a *shape* you can reuse.

### 3.4 Module mapping — pyDE1 area → planned `de1-core` module

| pyDE1 area (approx.) | `de1-core` module (from `01-feasibility.md` §5) | Notes |
|----------------------|--------------------------------------------------|-------|
| Characteristic codec classes | **packet codecs** (binary fixed-point ↔ typed structs) | Highest-value read. Cross-check field offsets & scaling vs `binary.tcl`. Re-implement in Rust; do not translate. |
| `DE1` device object + MMR read/write | **DE1 connection model + MMR registers** | pyDE1's MMR handling is a good model for the request/response "maprequest" pattern. |
| Shot / flow state machine, `State`/`SubState` | **shot state machine (SAW/SAV, frames, timers)** | Compare against `documentation/de1_app_core_shot_cycle_overview.md` — same author, should agree; any disagreement is a real spec question. |
| Stop-at-weight / stop-at-volume | part of **shot state machine** | In `de1-core` this is driven by `on_tick` + scale-weight events, not by `await`. |
| Profile model / frame upload | **profile model + (de)serialization** | pyDE1 targets JSON v2 only; legacy Tcl-dict profiles are this repo's concern, not pyDE1's. |
| Scale base class + drivers | **scale protocol codecs** | pyDE1 covers fewer scales than `de1plus/`. Legacy Tcl is the better scale-codec source. |
| Shot recording / SQLite | **history model** | `de1-core` is sans-IO: it emits `ShotComplete(ShotRecord)`; the *shell* persists. Different boundary. |
| HTTP / MQTT API | **event bus (domain events out)** | Conceptually parallel — both are an "outbound events" layer — but the transport differs (network vs FFI). |
| BLE (`bleak`) transport, supervisor, config | **(not in `de1-core`)** | Lives in the Android Kotlin shell. Explicitly out of the Rust core. |

**Takeaway:** the codec classes and the shot state machine are the two areas worth reading
closely. Everything below the API line maps cleanly to a `de1-core` module; everything from
the API line up (HTTP/MQTT, `bleak`, SQLite, supervisor) has **no `de1-core` counterpart** —
it is shell/host concern.

### 3.5 Coverage assessment

| Capability | pyDE1 | Legacy Tcl `de1plus/` | Best source for the rewrite |
|------------|-------|------------------------|------------------------------|
| DE1 GATT characteristics | Good [KNOWN] | Complete (`binary.tcl`) | **Tcl** (canonical), pyDE1 to cross-check |
| MMR register map | Good [KNOWN] | Complete | Tcl + Decent spec; pyDE1 to cross-check |
| Shot/flow state machine | Good, clean | Complete, DUI-entangled | Tcl logic + **pyDE1 for a clean structure** + the shot-cycle doc |
| SAW / SAV | Yes | Yes | Either; logic is small |
| Profile handling (JSON v2) | Yes | Yes | Either |
| Profile handling (legacy Tcl-dict) | **No** | Yes | **Tcl only** — pyDE1 never needed it |
| Scale support | ~2–4 scales | ~12 scales | **Tcl** (breadth); pyDE1 for the abstraction shape |
| Shot recording | SQLite | `.shot` files | Neither directly — `de1-core` emits events, shell persists |
| Firmware update over BLE | Limited / **[VERIFY]** | Yes | Out of MVP scope (`01-feasibility.md`) |
| Visualizer upload | **Yes** | Yes (plugin, see §5) | Either; trivial HTTP |

---

## 4. Decent Espresso's official protocol / firmware / GATT documentation

[REPO] The single most authoritative artifact we already control is the **`de1plus/` Tcl
source in this repository** — specifically:

- `de1plus/binary.tcl` — the fixed-point encodings and packet (de)serializers.
- `de1plus/de1_comms.tcl`, `de1plus/de1_de1.tcl`, `de1plus/machine.tcl` — characteristic
  wiring, MMR access, state handling.
- `documentation/de1_app_core_shot_cycle_overview.md` — narrative spec of the shot lifecycle.
- `documentation/de1_app_plugin_development_overview.md`, `decent_user_interface.md`.

[KNOWN / VERIFY] **Externally**, Decent Espresso has historically published:

- The **DE1 BLE/GATT specification** — the characteristic UUID table (`A001`–`A012`+, MMR via
  the "maprequest" characteristic) and packet field layouts. This has appeared both in
  Decent-controlled GitHub repositories and on `decentespresso.com`. **[VERIFY the current
  canonical URL.]**
- The **DE1 firmware** and an **MMR (Memory-Mapped Register) map** describing each register's
  address, size, and meaning.
- Developer notes / forum posts on `decentespresso.com` describing protocol changes across
  firmware versions.

**Action:** locate and archive the current canonical Decent GATT + MMR documents (see §7).
Until then, `binary.tcl` *is* the working spec — it tracks firmware updates (this repo's HEAD
commit is literally "DE1 firmware v1352"), so it is effectively a continuously-maintained
encoding of Decent's spec. The external doc's role is **tie-breaker**, not blocker.

---

## 5. Visualizer.coffee shot-upload format

[REPO] Fully observable from `de1plus/plugins/visualizer_upload/plugin.tcl` (v1.3, author
Johanna Schander). Key facts:

- **Service:** `visualizer.coffee` (formerly referred to as visualizer.com-style).
- **Upload endpoint:** `POST https://visualizer.coffee/api/shots/upload`.
- **Auth:** HTTP **Basic auth** — `Authorization: Basic base64(username:password)`.
- **Body:** `multipart/form-data`, one part named `file`, filename `file.shot`,
  `Content-Type: application/octet-stream`. The payload is the contents of a **`.shot`
  file** — exactly what `::shot::create` produces in the legacy app (a Tcl-dict / list-format
  shot record: profile, espresso elapsed/pressure/flow/temperature series, metadata).
- **Success response:** HTTP 200 with a JSON body containing an `id` field; the shot is then
  browsable at `https://visualizer.coffee/shots/<id>`.
- **Failure handling:** 401 → bad credentials; the plugin retries non-200 responses up to 20
  times with ~1 s backoff.
- **Download side (also in the plugin):**
  - `GET /api/shots/<id>/download` (full) or `?essentials=1` (essentials only)
  - `GET /api/shots/shared?code=<code>` (download by 4-char share code)
  - `GET /api/shots/shared` (list last shared, authenticated)
- **Upload preconditions enforced client-side:** auto-upload toggle on; shot ≥ ~6 s; series
  long enough; beverage type not `cleaning`/`calibrate`.

**Relevance to the rewrite:** **low for `de1-core`, deferred to Phase 4** (matches
`01-feasibility.md` roadmap). It is plain HTTP multipart — it belongs in the **Android shell**
(or a small `de1-visualizer` side-crate), not in the sans-IO core. The one core-adjacent
concern: the upload payload is the **`.shot` record**, so `de1-core`'s `ShotRecord` /
history model must be able to serialize to a format the Visualizer accepts. Decide later
whether to emit the legacy `.shot` dict format (maximizes Visualizer compatibility) or a new
JSON format (and convert at the shell). **[VERIFY]** whether `visualizer.coffee/api/shots/
upload` also accepts a JSON body — the current plugin only sends the legacy `.shot` format.

---

## 6. Other reference implementations

[KNOWN / VERIFY — all of this needs a fresh online check; see §7]

- **Rust:** No mature, well-known Rust DE1 *protocol* library as of the training cutoff.
  Hobby scripts using `btleplug` exist but are transport demos, not libraries. **If a real
  Rust DE1 crate now exists, evaluate it before Phase 1** — it could be a peer to collaborate
  with or a head start. **[VERIFY: crates.io search "de1" / "decent espresso"; GitHub.]**
- **JavaScript/TypeScript:** There have been browser/Web-Bluetooth DE1 experiments and
  community web dashboards (often paired with pyDE1's API). Useful only for spot-checking
  characteristic handling; partial coverage. **[VERIFY.]**
- **Swift / iOS:** Community iOS DE1 apps have existed; coverage and licensing vary and are
  generally not public/complete. Low value as a reference. **[VERIFY.]**
- **Kotlin / Android:** No known mature open Kotlin DE1 protocol library independent of this
  app. (If found, highly relevant — it would be a direct model for the shell's BLE layer.)
  **[VERIFY.]**
- **Go / others:** Occasional hobby projects; not known to be authoritative. **[VERIFY.]**
- **Decent's own DE1 firmware** and any **`de1-...`** Decent repos — authoritative for the
  MMR map and characteristic semantics; see §4. **[VERIFY current repo names.]**

**Conclusion:** outside pyDE1 and this repo's own Tcl, there is **no third implementation of
comparable completeness** that the assistant can confirm. The "three independent sources"
plan in `01-feasibility.md` §8 = **Tcl (`binary.tcl`) + Decent's GATT spec + pyDE1**. That
holds. Do not expect a ready-made Rust crate to short-circuit the work.

---

## 7. Follow-up actions

Concrete, ordered. Items 1–4 are **online verifications** that this offline run could not do.

1. **Confirm pyDE1's repo, license, and status.** Open `github.com/jeffsf/pyDE1` (and
   `pypi.org/project/pyDE1`): record the exact **license** (expected GPL-3.0 — confirm, and
   check for any differently-licensed files), the **last commit / last release date**, and
   **issue/PR activity**. *This gates whether pyDE1 may even be read alongside `de1-core`
   development without contamination risk — see item 5.*
2. **Locate Decent's canonical GATT + MMR documentation.** Find the current authoritative
   Decent-published characteristic table and MMR register map (GitHub `decentespresso` org
   and/or `decentespresso.com`). Archive a copy into `rewrite/` or link it from
   `02-ble-protocol.md`.
3. **Search for an existing Rust DE1 crate/project.** crates.io + GitHub for "de1", "decent
   espresso", "decent scale". If a mature one exists, write a one-page evaluation before
   Phase 1 — it changes build-vs-reuse.
4. **Confirm Jeff Kletsky's authorship link.** Verify that the author of pyDE1 is the same
   person credited for `documentation/de1_app_core_shot_cycle_overview.md`. If so, the
   shot-cycle doc and pyDE1's state machine should be treated as one consistent model.
5. **Set a clean-room policy for `de1-core`.** Decide and document explicitly:
   `de1-core` is implemented **from `binary.tcl` + Decent's GATT spec**, MIT/Apache-2.0
   licensed; **pyDE1 (GPLv3) may be *read* to resolve ambiguities but no code is copied or
   line-translated.** Put this in `CONTRIBUTING` / the crate README so the boundary is on
   record. (If, instead, the project chooses to ship `de1-core` itself under GPLv3, that
   reopens the option of porting from pyDE1 — but it also forces the whole Android binary to
   GPLv3. This is a deliberate licensing decision for a human to make, not a default.)
6. **Use pyDE1 as a cross-check during Phase 1.** When implementing each packet codec and the
   shot state machine, diff your understanding against (a) `binary.tcl`, (b) Decent's spec,
   (c) pyDE1. Log every three-way disagreement as a protocol question in `02-ble-protocol.md`.
7. **Defer Visualizer to Phase 4.** Keep `de1-core`'s `ShotRecord` serialization flexible
   enough to emit a Visualizer-acceptable payload; verify whether the upload endpoint accepts
   JSON or only the legacy `.shot` format. Implement the actual upload in the shell.
8. **Confirm pyDE1's scale coverage** and which scales it supports, to inform the
   `de1-core` scale-codec abstraction (research bead #8 in `01-feasibility.md`). Legacy Tcl
   remains the breadth source (~12 scales).

---

## 8. Bottom line

| Question | Answer |
|----------|--------|
| Is pyDE1 worth **porting** from? | **No.** GPLv3 (license-incompatible with a permissive `de1-core`) and IO-coupled async architecture (not sans-IO). Line-translating it would make `de1-core` a GPLv3 derivative. |
| Is pyDE1 worth **referencing**? | **Yes — strongly.** Best clean-room model of the shot state machine and packet codecs; same author as our shot-cycle doc. Read it; write your own Rust. |
| Should pyDE1 be **ignored**? | No. |
| What is the primary spec? | The legacy Tcl `de1plus/` (esp. `binary.tcl`) + Decent's published GATT/MMR docs. pyDE1 is the third cross-check. |
| Any ready-made Rust DE1 crate? | **None confirmed** (offline run — **must verify online**). Plan to build `de1-core` from scratch. |
| Visualizer upload? | Plain HTTP multipart `POST /api/shots/upload` (Basic auth, `.shot` payload). Shell concern, Phase 4. |
| What must a human verify? | pyDE1 license/status/URL, Decent's canonical GATT/MMR doc location, existence of any Rust DE1 crate. See §7 items 1–4. |
