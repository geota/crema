# Local dev commands (`just --list`). Local only: no CI job or git hook runs these.
set shell := ["bash", "-euo", "pipefail", "-c"]

[private]
default:
    @just --list

# Markdown and TOML: lint and format check (rumdl, tombi)
docs:
    rc=0; rumdl check . || rc=1; tombi format --check || rc=1; tombi lint || rc=1; exit $rc

# Markdown and TOML: apply the safe fixes and formatting
docs-fix:
    rumdl fmt .
    tombi format

# Links in Markdown: web URLs and #anchors (lychee; rumdl already checks links to local files)
links:
    lychee .

# Rust coverage of core/ (HTML under core/target/llvm-cov/html); not de1-model or fuzz
[working-directory: 'core']
coverage:
    rustup component add llvm-tools-preview
    cargo llvm-cov --workspace --html

# Rust mutation testing of core/ code changed since origin/main (cargo-mutants)
[working-directory: 'core']
mutants:
    mkdir -p target
    git diff --relative origin/main... -- '*.rs' > target/mutants.diff
    cargo mutants --in-diff target/mutants.diff
