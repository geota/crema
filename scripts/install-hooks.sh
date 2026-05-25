#!/usr/bin/env bash
# Wire .githooks/ into this clone's git config. Idempotent.
#
# Run once after cloning:
#   scripts/install-hooks.sh
#
# To uninstall: `git config --unset core.hooksPath`

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

git config core.hooksPath .githooks
chmod +x .githooks/* 2>/dev/null || true

echo "✓ core.hooksPath → .githooks (pre-push gates active)"
echo "  bypass once with: SKIP_CI_CHECKS=1 git push"
