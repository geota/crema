# Extension / Plugin Architecture — Discussion

> Status: **SHELVED** — 2026-05-16. Decision: **no plugin architecture.**
>
> The 24 legacy plugins re-bucket into native features or outright drops (see §0). Three
> were genuine external integrations: **Visualizer upload** and a **local API** are planned
> as later *native features* (not as a plugin SDK); MQTT is not currently planned.
>
> Crucially, neither Visualizer nor the local API needs a plugin architecture or an internal
> event bus. When wanted, both are built as **ordinary features in the Kotlin shell** —
> behind a settings toggle, not via any extension interface. Visualizer is an HTTP client
> call on `ShotComplete`. The local API is a Ktor server started by the shell that collects
> the core's event stream and re-broadcasts over WebSocket; its wire schema is versioned at
> *that* network boundary, defined when it is built. The rule that holds: I/O (HTTP, sockets)
> never enters the sans-IO `de1-core` crate. The only preparation needed now is to design
> `CoreEvent` / `ShotRecord` to be **complete**, since both features just re-expose them.
>
> **What is kept is not a plugin mechanism.** The sans-IO Rust core necessarily emits a
> typed event/effect stream to its shell — that is the core's own API, justified by
> *testability* (replay recorded traces, assert on outputs), not extensibility. No internal
> pub/sub bus, no event-schema versioning, and no speculative extension traits are built.
> Versioning is applied only to *persisted data* (history files, settings, profile JSON).
>
> §1–§6 below are retained as a record. If the external integrations (Visualizer, MQTT,
> local API) are ever wanted, the local-API option in §4 bolts on without touching the core.

---

## 0. Decision & legacy-plugin disposition

The fact that something was a "plugin" in the Tcl app is an architecture artifact — plugins
were that app's only clean extension seam — not a statement that the feature is optional.
Each feature is re-bucketed on its own merits:

| Legacy plugin | Disposition |
|---|---|
| DYE (Describe Your Espresso) | **Native core** — shot journaling, part of History |
| visualizer_upload | **Deferred native feature — planned** (fires on `ShotComplete`, HTTP POST) |
| mqtt | External integration — not currently planned |
| web_api | **Deferred native feature — planned** as a shell-side local-API server |
| A_Flow, D_Flow | Native, later — graphical curve editor (form editor is MVP) |
| auto_load_profile, D_Scheduler | Native, later |
| Graphical_Flow_Calibrator, ro_filter_monitor | Native, later (maintenance) |
| DPx_Screen_Saver | Native, later — or use Android display timeout |
| decentscale_off, DPx_Steam_Stop, history_exclusion_filter | Native — small behaviors/settings |
| log_upload | Native "share diagnostics", or drop |
| example, SDB, hazard, invert_grind_size_adjustment, skip_first_step_notice, log_debug, old_lcd_disable, keyboard_control, print_the_shot | **Drop** — dev-only, legacy hardware, site-specific, or replaced by good native defaults |

Result: ~9 dropped, ~11 become native features, 3 external integrations deferred. The plugin
*mechanism* is not needed.

---

## 2. What the old Tcl plugin system did

So we know what we're replacing. The Tcl app has 24 plugins (`de1plus/plugins/`). Each is a
Tcl namespace with a `preload`/`main` lifecycle, optional `settings.tdb`, and it registers
callbacks on a global event bus. Plugins run **in-process, unsandboxed**, and can do
anything — including building their own UI pages via DUI.

That model is powerful, and unportable: it depends on Tcl, on DUI, and on shared mutable
global state (`::settings`, `::de1`). None of the 24 plugins survive a rewrite as code.
But their *intent* tells us what extension categories real users want.

### The 24 plugins, categorized

| Category | Examples | What it needs |
|---|---|---|
| **Shot observers / exporters** | `visualizer_upload`, `mqtt`, `print_the_shot`, `log_upload`, `web_api` | Read-only stream of shot events; outbound network. *No UI, no machine control.* |
| **Profile sources / automation** | `auto_load_profile`, `D_Scheduler`, `history_exclusion_filter` | React to events, choose/load a profile, schedule actions. |
| **Behaviour tweaks** | `skip_first_step_notice`, `invert_grind_size_adjustment`, `DPx_Steam_Stop`, `hazard` | Small interceptors on commands/state transitions. |
| **Profile editors** | `D_Flow`, `A_Flow` (under `profile_editors/`) | Heavy, bespoke **UI**. |
| **Scale / device add-ons** | `decentscale_off`, `ro_filter_monitor`, `keyboard_control` | Device-specific glue. |
| **Cosmetic / display** | `DPx_Screen_Saver`, `old_lcd_disable` | Pure UI. |

**Key observation:** the categories split cleanly into two kinds, and they have *very*
different costs:

- **Headless extensions** — react to events, export data, automate, tweak behaviour. No UI.
  This is most of the list and most of the value (Visualizer, MQTT, scheduling, automation).
- **UI extensions** — add or replace screens (profile editors, screen savers).

Headless extensions are cheap to support across a boundary. UI extensions across an FFI or
sandbox boundary are *hard* — that is the expensive part, and it is a small minority of the
real demand.

**Design consequence:** plan to support **headless extensions** as the extension story.
UI extensions stay compiled-in (or are not supported). Don't let the hard 10% block the easy 90%.

---

## 3. The foundation to build now (MVP)

### 3.1 A versioned, typed event bus in `de1-core`

The core already emits `CoreEvent`s (see `01-feasibility.md` §5). Make that schema a
**first-class, deliberately-designed, versioned contract**, not an incidental enum:

- Stable names and field semantics; documented units.
- An explicit schema version. Adding a variant is backward-compatible (consumers ignore
  unknown variants); changing one is a version bump.
- Covers: connection state, machine state/substate, `ShotSample`, shot lifecycle
  (start/frame-change/complete with the full record), scale weight, profile load, errors.

### 3.2 A typed command API

The inbound side, symmetrically: `load_profile`, `request_state`, `wake`/`sleep`,
`tare_scale`, `set_saw_target`, etc. Also stable and versioned.

### 3.3 Trait-based extension points

Inside the core, define the seams where compiled-in modules plug in:

```rust
trait ShotObserver  { fn on_event(&mut self, ev: &CoreEvent); }   // exporters, loggers
trait ProfileSource { fn profiles(&self) -> Vec<ProfileRef>; }     // profile providers
// ... a small, named set — not an open-ended framework
```

For the MVP these have exactly one or two implementors each, compiled in. That is enough
to keep the code modular and to *prove the seams hold* before exposing them externally.

### Cost of doing this now: ~nothing.
It is good architecture regardless of plugins — it is just "don't entangle features with
the core". The payoff is that the plugin mechanism later becomes additive.

---

## 4. The mechanism options (defer the choice to Phase 4)

When you do want third parties (or future-you) to extend without recompiling, here are the
realistic mechanisms, all layered on the §3 event bus.

### Option 1 — Compiled-in modules
Rust modules in the core, or Kotlin modules in the shell.
- ➕ Zero new infrastructure; full power; type-safe.
- ➖ Recompile to add; no third-party story; no isolation.
- *This is the MVP state. Fine indefinitely for personal use.*

### Option 2 — Out-of-process local API ⭐ likely best for headless
The core/shell exposes a **localhost API** — a WebSocket streaming the event bus + an
HTTP/RPC endpoint for commands. Plugins are *separate programs* (any language) that connect.
- ➕ **Language-agnostic** — the existing DE1 community writes Python/Tcl; they could keep doing so.
- ➕ **Crash-isolated** — a buggy plugin cannot take down a shot in progress.
- ➕ Maps the schema once; every plugin is just a client.
- ➕ The old `web_api` plugin already proves the appetite; Visualizer and MQTT are *already*
  network services — under this model they become ordinary clients, almost for free.
- ➖ No UI extension; plugin must run as a process/service (a companion app, or a server
  somewhere on the network); auth/security surface to think about.

### Option 3 — Embedded scripting (Rhai or Lua via `mlua`)
A script runtime inside the Rust core; plugins are scripts reacting to events.
- ➕ Dynamic, no recompile, low barrier for small automations; in-process, no network.
- ➖ A second language to support; limited (no real UI); sandboxing is partial.

### Option 4 — WASM components (`wasmtime`)
Plugins compiled to WASM, loaded by the core, capability-sandboxed.
- ➕ Strong sandbox; language-agnostic; in-process; matches the "core owns the contract" design.
- ➖ Most infrastructure to build; WASM ↔ host interface design; still no easy UI; heaviest option.

### Comparison

| | Compiled-in | Local API | Scripting | WASM |
|---|---|---|---|---|
| Recompile to add | yes | no | no | no |
| 3rd-party / any language | no | **yes** | no | yes |
| Crash isolation | no | **yes** | partial | yes |
| In-process (no network) | yes | no | yes | yes |
| UI extensions | yes | no | no | no |
| Infra cost to build | none | low–med | medium | high |
| Reuses Visualizer/MQTT as clients | — | **yes** | — | — |

---

## 5. Recommendation

1. **MVP:** do §3 only — versioned event bus, command API, trait extension points,
   compiled-in modules. Treat the **schema design** as the real deliverable.
2. **Phase 4, when extensibility is actually wanted:** default to **Option 2 (local API)**
   for headless extensions. It is language-agnostic (fits the existing community), crash-
   isolated (a shot is safety-relevant — never let a plugin abort one), low-infra, and it
   turns Visualizer/MQTT into ordinary clients. Choose **Option 4 (WASM)** instead only if
   a no-network, in-process, sandboxed model becomes a hard requirement.
3. **UI extensions:** not supported via the plugin boundary. Profile editors and similar
   stay first-party / compiled-in. Revisit only if there is real demand.

---

## 6. Open questions → research bead

- Exact event/command **schema** design — the concrete enums, versioning rules, units.
  This is worth its own doc once `02-ble-protocol.md` lands (the schema mirrors the protocol).
- Local-API **security**: localhost-only vs. LAN; auth; can a plugin send machine commands
  (control) or only observe? Machine control from a plugin has safety implications.
- Whether to keep any **wire compatibility** with the old `web_api` plugin's API so existing
  community tools work unmodified.
