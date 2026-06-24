#!/usr/bin/env bash
# Regenerate the Kotlin + TypeScript type definitions for the JSON `CoreOutput`
# surface from the `#[typeshare]`-annotated Rust types.
#
# Requires the typeshare CLI: `cargo install typeshare-cli`.
# Run from anywhere; output lands in `core/bindings/`.
#
# The output is *also* copied into each shell — both copies are generated
# artifacts, never hand-edited:
#   • web     → `web/src/lib/core/crema-core.ts`
#   • Android → `android/app/src/main/java/coffee/crema/core/CremaCoreTypes.kt`
#     (typeshare.toml already emits `package coffee.crema.core`, so it's a plain cp)
# so a new Rust field reaches both shells the moment this runs. CI re-runs this
# and `git diff --exit-code`s the results, so a stale copy fails the build
# (see .github/workflows/ci.yml).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p bindings
typeshare --lang kotlin     --config-file typeshare.toml --output-file bindings/crema-core.kt .
typeshare --lang typescript --config-file typeshare.toml --output-file bindings/crema-core.ts .
cp bindings/crema-core.ts ../web/src/lib/core/crema-core.ts
cp bindings/crema-core.kt ../android/app/src/main/java/coffee/crema/core/CremaCoreTypes.kt
echo "Generated bindings/crema-core.{kt,ts}; synced → web (.ts) + Android (CremaCoreTypes.kt)"
