#!/usr/bin/env bash
# Regenerate the Kotlin + TypeScript type definitions for the JSON `CoreOutput`
# surface from the `#[typeshare]`-annotated Rust types.
#
# Requires the typeshare CLI: `cargo install typeshare-cli`.
# Run from anywhere; output lands in `core/bindings/`.
#
# The TypeScript output is *also* copied into the web shell at
# `web/src/lib/core/crema-core.ts` — that file is a generated artifact, never
# hand-edited, so a new Rust field reaches the web types the moment this runs.
# CI re-runs this and `git diff --exit-code`s the results, so a stale copy fails
# the build (see .github/workflows/ci.yml). The Android shell consumes the
# Kotlin types from `bindings/` directly.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p bindings
typeshare --lang kotlin     --config-file typeshare.toml --output-file bindings/crema-core.kt .
typeshare --lang typescript --config-file typeshare.toml --output-file bindings/crema-core.ts .
cp bindings/crema-core.ts ../web/src/lib/core/crema-core.ts
echo "Generated bindings/crema-core.{kt,ts}; synced crema-core.ts → web/src/lib/core/"
