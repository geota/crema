#!/usr/bin/env bash
# Cut a Crema release in two steps: the release notes go through a PR like
# every other change, and the tag is created only after that PR merges.
#
#   1. scripts/cut-release.sh 0.0.8 -m "Short user-facing note."
#        Writes this version's notes on a release/X.Y.Z branch, pushes it and
#        opens a PR "docs(release): vX.Y.Z". Omit -m to write the note in
#        $EDITOR (falls back to vi), seeded with the previous whatsnew entry.
#
#   2. Review and merge that PR, then:
#      scripts/cut-release.sh --tag 0.0.8
#        Checks main contains the notes, creates the annotated tag vX.Y.Z on
#        main and pushes it. The tag push triggers .github/workflows/release.yml
#        (signed APK, GitHub Release, web deploy).
#
# Why two steps: a rebase/squash merge gives the notes commit a new SHA, so the
# tag can't be created on the branch. A workflow can't create it either — a
# tag pushed with GITHUB_TOKEN doesn't trigger release.yml, and the bot app is
# blocked from v* tags by the release-tags ruleset. Tagging stays yours.
#
# Keep the note SHORT and general -- a few lines / bullets on what changed for
# users, not an exhaustive commit-by-commit changelog. Play's whatsnew has a
# hard 500-char cap (enforced below); CHANGELOG.md and the fastlane changelog
# entry reuse the exact same text, so it has to read fine in all three places.
#
# Step 1 writes, in a throwaway worktree off origin/main (your checkout is left
# alone):
#   - android/distribution/whatsnew/whatsnew-en-US (the Play "current" file);
#   - fastlane/metadata/android/en-US/changelogs/<versionCode>.txt -- the same
#     text, frozen per release for F-Droid/IzzyOnDroid (see fastlane/README.md);
#   - a "## [X.Y.Z] — <date>" section in CHANGELOG.md: directly under
#     "## [Unreleased]" when that section exists (its entries become this
#     version's detail, below the note, and Unreleased is left empty), else
#     above the most recent version. release.yml's awk extractor reads this
#     section verbatim as the GitHub Release body.
#
# Both steps push with SKIP_CI_CHECKS=1: step 1 only touches release-notes
# files and CI runs on the PR; step 2 tags a main commit that CI already ran on.

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

usage() {
  echo "Usage: scripts/cut-release.sh X.Y.Z [-m \"note\"]   (step 1: notes PR)" >&2
  echo "       scripts/cut-release.sh --tag X.Y.Z          (step 2: tag after merge)" >&2
  exit 1
}

MODE=notes
if [[ "${1:-}" == "--tag" ]]; then
  MODE=tag
  shift
fi

VERSION="${1:-}"
if [[ -z "$VERSION" || "$VERSION" == -* ]]; then
  usage
fi
shift

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "error: version must be X.Y.Z (got '$VERSION')" >&2
  exit 1
fi

TAG="v$VERSION"
BRANCH="release/$VERSION"

# versionCode: M*1e8 + m*1e6 + p*1e4 -- must match release.yml's scheme
# exactly, or a Play/F-Droid/Izzy install won't line up with what CI ships.
IFS='.' read -r MAJ MIN PAT <<< "$VERSION"
VERSION_CODE=$(( 10#$MAJ * 100000000 + 10#$MIN * 1000000 + 10#$PAT * 10000 ))
WHATSNEW=android/distribution/whatsnew/whatsnew-en-US
CHANGELOG_ENTRY="fastlane/metadata/android/en-US/changelogs/${VERSION_CODE}.txt"

tag_exists() {
  git rev-parse -q --verify "refs/tags/$TAG" >/dev/null ||
    [[ -n "$(git ls-remote --tags origin "refs/tags/$TAG")" ]]
}

# Fetch main only: a plain `git fetch --tags` trips over the rolling `nightly`
# tag ("would clobber existing tag").
git fetch --quiet origin main

if tag_exists; then
  echo "error: tag $TAG already exists" >&2
  exit 1
fi

# ── Step 2: tag main after the notes PR has merged ───────────────────────────
if [[ "$MODE" == tag ]]; then
  [[ $# -eq 0 ]] || usage
  # No `grep -q`: it exits on the first match, git show gets SIGPIPE, and
  # pipefail would then read a match as a failure.
  if ! git show origin/main:CHANGELOG.md | grep "^## \[$VERSION\] " >/dev/null; then
    echo "error: origin/main's CHANGELOG.md has no '## [$VERSION]' section -- merge the release PR first" >&2
    exit 1
  fi
  if ! git cat-file -e "origin/main:$CHANGELOG_ENTRY" 2>/dev/null; then
    echo "error: origin/main has no $CHANGELOG_ENTRY -- merge the release PR first" >&2
    exit 1
  fi
  COMMIT="$(git rev-parse origin/main)"
  git tag -a "$TAG" -m "Crema $VERSION" "$COMMIT"
  SKIP_CI_CHECKS=1 git push origin "refs/tags/$TAG"
  echo
  echo "Tagged $TAG on main ($(git rev-parse --short "$COMMIT")) and pushed it -- release.yml is running:"
  echo "  https://github.com/geota/crema/actions/workflows/release.yml"
  exit 0
fi

# ── Step 1: release notes on a branch + PR ───────────────────────────────────
NOTE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -m)
      NOTE="${2:?-m requires a note}"
      shift 2
      ;;
    *)
      echo "error: unknown arg '$1'" >&2
      exit 1
      ;;
  esac
done

if git rev-parse -q --verify "refs/heads/$BRANCH" >/dev/null ||
  [[ -n "$(git ls-remote --heads origin "refs/heads/$BRANCH")" ]]; then
  echo "error: branch $BRANCH already exists (locally or on origin)" >&2
  exit 1
fi

if [[ -z "$NOTE" ]]; then
  TMP="$(mktemp)"
  git show "origin/main:$WHATSNEW" > "$TMP"
  "${EDITOR:-vi}" "$TMP"
  NOTE="$(cat "$TMP")"
  rm -f "$TMP"
fi

# Trailing newline from a heredoc/editor shouldn't count against the cap.
NOTE="$(printf '%s' "$NOTE" | sed -e '$a\' )"
NOTE_LEN=$(printf '%s' "$NOTE" | wc -c | tr -d ' ')

if [[ -z "$NOTE" ]]; then
  echo "error: empty release note" >&2
  exit 1
fi
if [[ "$NOTE_LEN" -gt 500 ]]; then
  echo "error: whatsnew note is $NOTE_LEN chars, Play's hard limit is 500" >&2
  exit 1
fi

# Work in a throwaway worktree on origin/main so your checkout is untouched.
WORKTREE="$(mktemp -d)"
cleanup() { git worktree remove --force "$WORKTREE" >/dev/null 2>&1 || true; }
trap cleanup EXIT
git worktree add --quiet --no-track -b "$BRANCH" "$WORKTREE" origin/main
cd "$WORKTREE"

printf '%s\n' "$NOTE" > "$WHATSNEW"
mkdir -p "$(dirname "$CHANGELOG_ENTRY")"
printf '%s\n' "$NOTE" > "$CHANGELOG_ENTRY"

python3 - "$VERSION" "$NOTE" <<'PYEOF'
import re
import sys
from datetime import date

version, note = sys.argv[1], sys.argv[2]
path = "CHANGELOG.md"
with open(path) as f:
    text = f.read()

entry = f"## [{version}] — {date.today().isoformat()}\n\n{note}\n\n"
link = f"[{version}]: https://github.com/geota/crema/releases/tag/v{version}\n"

# With a "## [Unreleased]" section, the new version goes directly under its
# heading: everything collected under Unreleased becomes this version's detail
# (below the note), and Unreleased is left empty for the next cycle. Without
# one, insert before the most recent version section.
unreleased = re.search(r"^## \[Unreleased\][^\n]*\n+", text, re.MULTILINE)
if unreleased:
    text = text[: unreleased.end()] + entry + text[unreleased.end() :]
    text = text[: unreleased.start()] + "## [Unreleased]\n\n" + text[unreleased.end() :]
else:
    m = re.search(r"^## \[", text, re.MULTILINE)
    if m:
        text = text[: m.start()] + entry + text[m.start() :]
    else:
        text = text.rstrip("\n") + "\n\n" + entry

if link not in text:
    text = text.rstrip("\n") + "\n" + link

with open(path, "w") as f:
    f.write(text)
PYEOF

git add "$WHATSNEW" "$CHANGELOG_ENTRY" CHANGELOG.md
git commit --quiet -m "docs(release): v${VERSION}"
SKIP_CI_CHECKS=1 git push --quiet origin "refs/heads/$BRANCH:refs/heads/$BRANCH"

BODY="Release notes for **v${VERSION}** (versionCode ${VERSION_CODE}): Play whatsnew, the fastlane changelog entry and the \`## [${VERSION}]\` section of CHANGELOG.md (the GitHub Release body).

> $(printf '%s' "$NOTE" | sed 's/^/> /' | sed '1s/^> //')

After merging, tag it:

\`\`\`
scripts/cut-release.sh --tag ${VERSION}
\`\`\`"

if command -v gh >/dev/null 2>&1; then
  gh pr create --base main --head "$BRANCH" --title "docs(release): v${VERSION}" --body "$BODY"
else
  echo "Pushed $BRANCH. Open a PR into main titled 'docs(release): v${VERSION}'."
fi

cat <<EOF

Release notes for v${VERSION} are on $BRANCH (PR above). After it merges:
  scripts/cut-release.sh --tag ${VERSION}
EOF
