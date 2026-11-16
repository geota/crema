#!/usr/bin/env bash
# Builds the history store's wasm (core/de1-history-wasm) into
# web/src/lib/history-db/wasm/: `pnpm wasm:history`, which `pnpm build` runs
# since the app's history Worker loads it (#310 W1).
#
# sqlite-wasm-rs compiles SQLite's C, so this needs a clang with a wasm32
# backend and an llvm-ar. Set CC_wasm32_unknown_unknown and
# AR_wasm32_unknown_unknown, or let this find one: Homebrew's or zerobrew's
# LLVM on macOS (Apple's clang has no wasm32 backend), the newest clang-<n> on
# Linux. core/de1-history-wasm/README.md has the setup.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -z "${CC_wasm32_unknown_unknown:-}" ]; then
  cc=""; ar=""
  for prefix in "$(brew --prefix llvm 2>/dev/null || true)" /opt/zerobrew/opt/llvm /opt/homebrew/opt/llvm /usr/local/opt/llvm; do
    if [ -n "$prefix" ] && [ -x "$prefix/bin/clang" ] && [ -x "$prefix/bin/llvm-ar" ]; then
      cc="$prefix/bin/clang"; ar="$prefix/bin/llvm-ar"; break
    fi
  done
  if [ -z "$cc" ]; then
    for v in 22 21 20 19 18 17; do
      if command -v "clang-$v" >/dev/null && command -v "llvm-ar-$v" >/dev/null; then
        cc="$(command -v "clang-$v")"; ar="$(command -v "llvm-ar-$v")"; break
      fi
    done
  fi
  if [ -z "$cc" ] && [ "$(uname)" != "Darwin" ] && command -v clang >/dev/null && command -v llvm-ar >/dev/null; then
    cc="$(command -v clang)"; ar="$(command -v llvm-ar)"
  fi
  if [ -z "$cc" ]; then
    echo "history-wasm-build: no clang with a wasm32 backend; see core/de1-history-wasm/README.md" >&2
    exit 1
  fi
  export CC_wasm32_unknown_unknown="$cc" AR_wasm32_unknown_unknown="$ar"
fi

cd "$here/../web"
exec wasm-pack build ../core/de1-history-wasm --target web --profile history-wasm \
  --out-dir ../../web/src/lib/history-db/wasm --out-name de1_history_wasm
