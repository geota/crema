#!/usr/bin/env bash
# The history store's wasm stays under 1 MB gzip (#310 PLAN C4). Prints its
# raw and gzip sizes, to the step summary in CI, and fails above the cap.
#
#   history-wasm-size.sh PATH/TO/de1_history_wasm_bg.wasm
set -euo pipefail

wasm="${1:?path to the .wasm}"
cap=$((1024 * 1024))
[ -f "$wasm" ] || { echo "::error::no $wasm"; exit 1; }
raw="$(wc -c < "$wasm" | tr -d ' ')"
gz="$(gzip -9 -c "$wasm" | wc -c | tr -d ' ')"
line="de1_history_wasm_bg.wasm: ${raw} bytes raw, ${gz} bytes gzip -9 (cap ${cap})"
echo "$line"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "### history wasm size" >> "$GITHUB_STEP_SUMMARY"
  echo "$line" >> "$GITHUB_STEP_SUMMARY"
fi
if [ "$gz" -gt "$cap" ]; then
  echo "::error::the history wasm is ${gz} bytes gzip, over the 1 MB cap"
  exit 1
fi
