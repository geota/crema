# de1-history-wasm

The SQLite history's store for the web Worker (#310, PLAN C4). `de1-history`'s
store over `rusqlite` and `sqlite-wasm-rs`, on OPFS through `sqlite-wasm-vfs`'s
`sahpool`, with JSON-in, JSON-out `#[wasm_bindgen]` methods that mirror the
FFI's (C3). Nothing in the app loads it yet; W1 adds the Worker.

- `src/pools.rs`: the one module that installs the two pools (`crema-index`,
  capacity 6; `crema-db`, capacity 16) with `clear_on_init(false)`, grows a full
  pool and retries once, and never deletes. `tests/sources.rs` fails CI on any
  deleting call there and on any use of `sahpool` elsewhere.
- `src/db.rs`: `HistoryDb`, one store on one file.
- `src/repair.rs`: Repair storage for a pool that won't install (S5).

## Building it

`sqlite-wasm-rs` compiles SQLite's C with `cc`, so the build needs a clang
with a wasm32 backend and an `llvm-ar`. Apple's clang has no wasm32 backend.

**macOS:** install LLVM (`zb install llvm`, or `brew install llvm`), then point
`cc` at it:

```sh
export CC_wasm32_unknown_unknown="$(brew --prefix llvm)/bin/clang"
export AR_wasm32_unknown_unknown="$(brew --prefix llvm)/bin/llvm-ar"
```

With zerobrew the prefix is `/opt/zerobrew/opt/llvm`. Measured with LLVM 23.1.

**Linux:** the distribution's clang works (`apt install clang llvm`), as
`clang-<n>` and `llvm-ar-<n>`. CI uses the runner image's; the `history-wasm`
job prints which.

Then, from `web/`: `pnpm wasm:history`. It writes
`web/src/lib/history-db/wasm/` (gitignored) under the `history-wasm` profile:
`release` at `opt-level = "z"`, then `wasm-opt -Oz`. CI fails the build at 1 MB
gzip.

## Tests

```sh
cd core
export CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_RUNNER=wasm-bindgen-test-runner
# C2's store suite on wasm32, in Node, on memvfs:
cargo test -p de1-history --target wasm32-unknown-unknown --tests
# This crate's surface in Node, then the pools in headless Chrome, in a
# dedicated Worker, on real OPFS:
CHROMEDRIVER=/path/to/chromedriver \
  cargo test -p de1-history-wasm --target wasm32-unknown-unknown
# The source checks, natively (also in `cargo test --workspace`):
cargo test -p de1-history-wasm
```

`wasm-bindgen-test-runner` comes with `wasm-bindgen-cli`, whose version must
equal the `wasm-bindgen` in `core/Cargo.lock`
(`cargo binstall wasm-bindgen-cli@<that version>`). The chromedriver must match
the installed Chrome (`npx @puppeteer/browsers install chromedriver@<version>`).
