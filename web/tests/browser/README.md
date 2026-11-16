# Browser tests: the history store's web gate (#310 PLAN W0)

Playwright tests of the history wasm (`core/de1-history-wasm`, built by
`pnpm wasm:history`) in a dedicated module Worker on real OPFS through
`sahpool`, in real browsers. They are what stays of W0: SPEC §1 picks
`sqlite-wasm-rs` over the official build (fallback C) only if these pass.

```sh
cd web
pnpm wasm:history                      # needs a clang for wasm32: core/de1-history-wasm/README.md
pnpm exec playwright install chromium webkit
pnpm test:browser                      # type-checks, builds the harness, runs Chromium and WebKit
W0_KILLS=20 pnpm test:browser durability --project webkit
```

| Spec | What it checks | Browsers |
| --- | --- | --- |
| `durability.spec.ts` | A kill inside a write transaction, `W0_KILLS` times (200), for `truncate` and `exclusiveWal`: all of it or none, nothing acknowledged lost. Picks the journal mode (`src/lib/history-db/journal.ts`) | Chromium (`Page.crash`), WebKit (`Worker.terminate`) |
| `ownership.spec.ts` | A second tab can't lock or install; `pause` with a connection open is `filesInUse`; the lock and handles move; after an unplanned close the next install retries and wins | Chromium, WebKit |
| `frozen.spec.ts` | A frozen owner (CDP `Page.setWebLifecycleState`) keeps its lock and handles; the other tab goes read-only after 5 s and journals a new shot; the thawed owner drains it | Chromium, over a bare CDP connection (`cdp.ts`) |
| `failed-start.spec.ts` | Held handles, a missing pool directory, a corrupt or foreign `history.db`, an invalid slot header: each ends in SPEC §8's health state, every file byte-identical | Chromium, WebKit |
| `connections.spec.ts` | One connection per file (S4); two files in one pool and across two, with the snapshot and flip copies; SQLite ≥ 3.54 and equal to the native pin | Chromium, WebKit |
| `capacity.spec.ts` | A full `crema-db` adds capacity and the write goes through (S5) | Chromium, WebKit |
| `w1.spec.ts` | The app's `HistoryDbClient` and Worker (W1): either tab answers, a reload and a closed owner move ownership, in-flight requests retry, the last action wins; a frozen owner leaves the other tab read-only with a journaled shot, which the thawed owner drains; stage 1 never installs `crema-db`; `stop` lets the pool go; no file goes missing | Chromium, WebKit (the frozen owner: Chromium, bare CDP) |
| `w2.spec.ts` | The stage-1 index (W2): a `crema-history` of 20,000 B1 rows, the app's client and Worker build the index by a full diff and keep it; the History list's reads through the Worker timed against B1's budgets at 20,000 rows (gated locally; on CI a step-summary table, gated only with `HISTORY_BENCH_STRICT=1`) | Chromium (project `bench`) |
| `r1w.spec.ts` | The backup seam's SQLite source (R1w, M3): a restore into `history.db` from a tab that doesn't own it, the owner tab killed between batches and a shot captured in a third tab meanwhile, finishes through the next owner; no row of the backup is deleted, the plan's removes and their details are gone, the new shot stays | Chromium, WebKit |
| `bench.spec.ts` | The wasm under 1 MB gzip; B1's browser bench at 5,000, 20,000 and 100,000 rows, the first-page lines gated | Chromium (project `bench`, alone, after the others) |

## How it's put together

- `w1/`: a page running the app's own `src/lib/history-db/` client and Worker (W1), driven as `window.h1`.
  On stage 1 it makes an empty, migrated `crema-history` first: the owner's index follows it (W2).
- `w2/`: the same client and Worker over a `crema-history` it seeds with B1's rows, timed as `window.h2` (W2).
- `r1w/`: the same client and Worker under the backup seam's SQLite source (`src/lib/history-source/`, R1w), driven as `window.r1w`. Its page bundles app modules whose types reach the whole app, so `tsc -p tests/browser` leaves it out and `pnpm check` types those modules.
- `harness/`: a test page and its Worker. The Worker loads the production wasm and
  forwards calls (`window.w0.call(op, args)`), holds Web Locks, and reads or plants
  raw OPFS files. Throwaway, like the smallest stage-2 client and boot in
  `harness/client.ts` and `fixtures.ts` (`bootStage2`): W1 writes the real ones.
- `../../bench/history-db.html`: B1's browser bench, the twin of
  `core/de1-history/benches/history`, on the same rows (`bench/rows.ts` is a port of
  its generator; `bench.spec.ts` checks it against the fixture). Open it by hand at
  `http://127.0.0.1:4318/bench/history-db.html?rows=5000,20000&auto=1` after
  `vite preview -c tests/browser/vite.config.ts`.
- `vite.config.ts` builds both into `.build/`; Playwright's `webServer` serves it on :4318.
- Every test gets a fresh persistent profile in a temp dir. Tests also keep apart by
  pool generation (`gen()`), because some browsers share one OPFS between profiles:
  on macOS, Playwright's WebKit keeps it in `~/Library/WebKit/org.webkit.Playwright`.
  Nothing here deletes a pool, so that directory grows; remove it by hand to reclaim
  the space.

## Android Chrome

The `android-chrome` project runs the same specs in Chrome on a device or emulator,
over CDP, in Chrome's own profile:

```sh
emulator -avd <a Play Store image> -no-window &
adb wait-for-device
adb reverse tcp:4318 tcp:4318                                 # the harness, at 127.0.0.1:4318 on the device
adb forward tcp:9222 localabstract:chrome_devtools_remote     # Chrome's DevTools socket
adb shell am start -n com.android.chrome/com.google.android.apps.chrome.Main about:blank
W0_ANDROID=1 pnpm test:browser
```

Differences there:
- Android Chrome ignores `Page.crash` for a background tab, so the unplanned
  handover in `ownership.spec.ts` closes the owner tab instead.
  `durability.spec.ts` crashes its one tab, which is in front.
- An emulator is slow and short of memory, so its bench numbers say little;
  B1's budgets are the reference tablet's.

Results from a real tablet are on the owner's hardware checklist.
