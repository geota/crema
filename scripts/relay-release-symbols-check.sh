#!/usr/bin/env bash
# AC-54 (relay v4 spec §2.11): release artifacts carry no deterministic relay
# test constructors (`*_for_tests` / `*ForTests`, feature `test-seeds`).
#
#   relay-release-symbols-check.sh ci        build and check the release de1-ffi
#                                            library, its Kotlin bindings and the
#                                            production wasm bundle, with positive
#                                            controls (CI job release-symbols-check)
#   relay-release-symbols-check.sh apk APK BINDINGS_DIR
#                                            check a built APK's libde1_ffi.so and
#                                            its generated Kotlin bindings
#                                            (release and nightly workflows)
#
# Run from the repository root. Release builds use `-p de1-ffi --lib` so no
# dev-dependency (de1-relay's test-seeds self-dependency) takes part.
set -euo pipefail

PATTERN='for_?tests'
root="$(cd "$(dirname "$0")/.." && pwd)"

fail() {
  echo "::error::$*" >&2
  exit 1
}

# Defined dynamic symbols of a shared library (ELF or Mach-O).
symbols() {
  local lib="${1:?library path}"
  case "$lib" in
    *.dylib) nm -gU "$lib" ;;
    *) nm -D --defined-only "$lib" ;;
  esac
}

check_library() {
  local lib="${1:?library path}"
  [ -f "$lib" ] || fail "missing library $lib"
  local hits
  hits="$(symbols "$lib" | grep -iE "$PATTERN" || true)"
  [ -z "$hits" ] || fail "test constructors exported by $lib:"$'\n'"$hits"
  echo "ok: no test constructors in $(basename "$lib") ($(symbols "$lib" | grep -c relay) relay symbols)"
}

check_bindings() {
  local dir="${1:?bindings dir}"
  [ -d "$dir" ] || fail "missing bindings dir $dir"
  local hits
  hits="$(grep -rniE "$PATTERN" "$dir" || true)"
  [ -z "$hits" ] || fail "test constructors in the Kotlin bindings:"$'\n'"$hits"
  grep -rq "class RelayHubConnection" "$dir" || fail "no relay bindings in $dir (wrong directory?)"
  echo "ok: no test constructors in the Kotlin bindings ($dir)"
}

# Export names of a wasm module, one per line.
wasm_exports() {
  node -e '
    const fs = require("fs");
    const m = new WebAssembly.Module(fs.readFileSync(process.argv[1]));
    for (const e of WebAssembly.Module.exports(m)) console.log(e.name);
  ' "${1:?wasm path}"
}

check_wasm() {
  local dir="${1:?bundle dir}"
  local wasm="$dir/de1_wasm_bg.wasm"
  [ -f "$wasm" ] || fail "missing $wasm"
  local hits
  hits="$( { wasm_exports "$wasm"; cat "$dir/de1_wasm.js" "$dir/de1_wasm.d.ts"; } | grep -iE "$PATTERN" || true)"
  [ -z "$hits" ] || fail "test constructors in the production wasm bundle:"$'\n'"$hits"
  wasm_exports "$wasm" | grep -q "relaykeys_generate" || fail "no relay exports in $wasm (wrong bundle?)"
  echo "ok: no test constructors in the production wasm bundle ($dir)"
}

case "${1:-}" in
  ci)
    cd "$root/core"
    lib_name="$(case "$(uname)" in Darwin) echo libde1_ffi.dylib ;; *) echo libde1_ffi.so ;; esac)"
    target_dir="$(cargo metadata --format-version 1 --no-deps | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).target_directory))')"

    # Positive control first: with test-seeds the check must find them.
    cargo build -p de1-ffi --lib --features test-seeds
    found="$(symbols "$target_dir/debug/$lib_name" | grep -ciE "$PATTERN" || true)"
    [ "$found" -ge 4 ] || fail "positive control: expected the test constructors in a test-seeds build, found $found"
    echo "ok: positive control sees $found test-constructor symbols in a test-seeds build"

    # The release library and its bindings.
    cargo build --release -p de1-ffi --lib
    check_library "$target_dir/release/$lib_name"
    out="$(mktemp -d)"
    cargo run -q -p de1-ffi --bin uniffi-bindgen -- generate --no-format \
      --library "$target_dir/release/$lib_name" --language kotlin --out-dir "$out"
    check_bindings "$out"

    # The production wasm bundle (no test-seeds) and the test bundle as control.
    prod="$(mktemp -d)"
    wasm-pack build de1-wasm --release --target web --out-dir "$prod" --out-name de1_wasm
    check_wasm "$prod"
    testb="$(mktemp -d)"
    wasm-pack build de1-wasm --release --target web --out-dir "$testb" --out-name de1_wasm -- --features test-seeds
    found="$(wasm_exports "$testb/de1_wasm_bg.wasm" | grep -ciE "$PATTERN" || true)"
    [ "$found" -ge 3 ] || fail "positive control: expected test constructors in the test-seeds wasm, found $found"
    echo "ok: positive control sees $found test-constructor exports in the test-seeds wasm"
    ;;
  apk)
    apk="${2:?APK path}"
    bindings="${3:?bindings dir}"
    [ -f "$apk" ] || fail "missing APK $apk"
    work="$(mktemp -d)"
    unzip -q -o "$apk" 'lib/*/libde1_ffi.so' -d "$work"
    shopt -s nullglob
    libs=("$work"/lib/*/libde1_ffi.so)
    [ "${#libs[@]}" -gt 0 ] || fail "no libde1_ffi.so in $apk"
    for lib in "${libs[@]}"; do
      check_library "$lib"
    done
    check_bindings "$bindings"
    ;;
  *)
    sed -n '2,16p' "$0"
    exit 2
    ;;
esac
