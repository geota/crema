#!/usr/bin/env bash
# Regenerate the Kotlin + TypeScript type definitions for the JSON `CoreOutput`
# surface from the `#[typeshare]`-annotated Rust types.
#
# Requires the typeshare CLI: `cargo install typeshare-cli`.
# Run from anywhere; output lands in `core/bindings/`.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p bindings
typeshare --lang kotlin     --config-file typeshare.toml --output-file bindings/crema-core.kt .
typeshare --lang typescript --config-file typeshare.toml --output-file bindings/crema-core.ts .
echo "Generated bindings/crema-core.{kt,ts}"
