# Contributing to Crema

Issues and pull requests are welcome. This page covers the setup, the dev loop, tests, hooks and CI. The code layout is in the [README](README.md#code-layout); the Android build is in [`android/README.md`](android/README.md); the web deploy is in [`DEPLOY.md`](DEPLOY.md).

## What the code aims for

- **Type-driven design**: make illegal states unrepresentable.
- **Sans-IO core**: protocol and domain logic in Rust, tested without hardware. The shells own the UI and the Bluetooth radio.
- **Behavioural fidelity**: bytes, timing and state transitions match the DE1 firmware and the reference apps (`de1app`, `decaid`, Decenza); structure is free to evolve.
- **Tight, idiomatic code**: no speculative abstractions.

## Prerequisites

- **Rust** 1.95+ (`rust-version` in `core/Cargo.toml`; CI pins 1.95.0), the `wasm32-unknown-unknown` target and `wasm-pack` (`cargo install wasm-pack`).
- **Node.js** 24 (see `.nvmrc`).
- **[pnpm](https://pnpm.io/)** 11: the repo pins it in `packageManager`, so `corepack enable` picks the right version.
- A browser with [Web Bluetooth](https://caniuse.com/web-bluetooth): Chrome, Edge or Opera. Brave ships it off: open `brave://flags/#brave-web-bluetooth-api`, set it to **Enabled** and relaunch.

## Run the web app

```bash
git clone https://github.com/geota/crema.git
cd crema/web
pnpm install
pnpm wasm           # the Rust core as wasm (re-run after core changes)
pnpm wasm:history   # the SQLite history store for the Worker
pnpm dev
```

Open `http://localhost:5173` and click **Connect** to pair a DE1. The DE1 may show up as `nRF5x` in the browser's device picker. No machine handy? Settings → Advanced → **Replay a capture** plays a recorded session (for example `core/de1-app/tests/fixtures/*.jsonl`) through Brew and into History.

### Visualizer and Google Drive (optional)

Visualizer sign-in needs a public Doorkeeper app from <https://visualizer.coffee/oauth/applications>; Drive backup needs a Google OAuth client ID.

```bash
cp web/.env.example web/.env.local
# set VITE_VISUALIZER_CLIENT_ID and VITE_GOOGLE_DRIVE_CLIENT_ID
```

### Production build

```bash
pnpm build   # wasm + history wasm + static site in web/build/
```

## Tests

```bash
# Rust core
cd core
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings

# Web shell
cd web
pnpm check   # svelte-check
pnpm test    # vitest
```

Some web tests need the test build of the core first: `pnpm wasm:test` (and `pnpm wasm:history:test` for the history store). Run `pnpm wasm` again after any core change, or the tests use a stale bundle.

Markdown and links: `just docs` (rumdl, tombi) and `just links` (lychee).

## Git hooks

`pnpm install` points git at `.githooks/` (or run `scripts/install-hooks.sh`). The pre-push hook mirrors CI: `cargo fmt --check`, clippy, `cargo test`, the built-in profile id check, then `pnpm install --frozen-lockfile`, `pnpm wasm`, `pnpm check` and `pnpm build`. It is slow; let it finish.

## CI

| Workflow | When | What |
|---|---|---|
| `ci.yml` | Push and PR to `main` | Rust fmt, clippy, tests, built-in ids and typeshare bindings; web check and build; advisory `cargo audit` / `pnpm audit` (also weekly). |
| `security-lockfile.yml` | Daily | Patches advisories in transitive web dependencies (`scripts/fix-transitive-advisories.mjs`) on one rolling PR from `security/transitive-fixes`, opened by the repo's security bot app. |
| `nightly.yml` | Push to `main` | Builds the APK and republishes the `nightly` prerelease. |
| `release.yml` | `v*.*.*` tag | Builds the APKs and the PWA, publishes the GitHub Release, deploys the web app. |
| `pr-title.yml` | PRs | Checks the PR title prefix. |

## Releases

Releases go through a PR: `scripts/cut-release.sh X.Y.Z -m "…"` opens the release-notes PR; after it merges, `scripts/cut-release.sh --tag X.Y.Z` tags `main` and `release.yml` does the rest. Each user-visible change gets a [CHANGELOG](CHANGELOG.md) entry that says which shells it reaches.
