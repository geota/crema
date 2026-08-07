#!/usr/bin/env bash
# Cut a new Crema release: write this version's release notes everywhere
# they need to live, commit them, and tag.
#
#   scripts/cut-release.sh 0.0.6 -m "Bean search, water/maintenance polish, BLE reconnect hardening."
#
# Or omit -m to write the note in $EDITOR (falls back to vi), seeded with the
# previous whatsnew entry as a starting point.
#
# Keep the note SHORT and general -- a few lines / bullets on what changed for
# users, not an exhaustive commit-by-commit changelog. Play's whatsnew has a
# hard 500-char cap (enforced below); CHANGELOG.md and the fastlane changelog
# entry reuse the exact same text, so it has to read fine in all three places.
#
# What it does, in order:
#   1. Validates the version + note (500-char cap, no empty note, tag not already used).
#   2. Overwrites android/distribution/whatsnew/whatsnew-en-US (the Play "current" file).
#   3. Freezes the SAME text into fastlane/metadata/android/en-US/changelogs/<versionCode>.txt
#      -- F-Droid/IzzyOnDroid want a permanent per-release file, not a mutable "current" one
#      (see fastlane/README.md).
#   4. Inserts a "## [X.Y.Z] — <date>" section at the top of CHANGELOG.md --
#      release.yml's awk extractor reads this section verbatim as the GitHub
#      Release body, so a version with no section here just falls back to
#      GitHub's auto-generated commit list instead.
#   5. Commits all three.
#   6. Creates the annotated tag vX.Y.Z on that commit.
#
# Does NOT push -- review the commit + tag, then:
#   git push origin main && git push origin vX.Y.Z

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

VERSION="${1:-}"
if [[ -z "$VERSION" || "$VERSION" == -* ]]; then
  echo "Usage: scripts/cut-release.sh X.Y.Z [-m \"note\"]" >&2
  exit 1
fi
shift

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "error: version must be X.Y.Z (got '$VERSION')" >&2
  exit 1
fi

if git rev-parse "v$VERSION" >/dev/null 2>&1; then
  echo "error: tag v$VERSION already exists" >&2
  exit 1
fi

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

WHATSNEW=android/distribution/whatsnew/whatsnew-en-US

if [[ -z "$NOTE" ]]; then
  TMP="$(mktemp)"
  cp "$WHATSNEW" "$TMP"
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

# versionCode: M*1e8 + m*1e6 + p*1e4 -- must match release.yml's scheme
# exactly, or a Play/F-Droid/Izzy install won't line up with what CI ships.
IFS='.' read -r MAJ MIN PAT <<< "$VERSION"
VERSION_CODE=$(( 10#$MAJ * 100000000 + 10#$MIN * 1000000 + 10#$PAT * 10000 ))

printf '%s\n' "$NOTE" > "$WHATSNEW"

CHANGELOG_ENTRY="fastlane/metadata/android/en-US/changelogs/${VERSION_CODE}.txt"
mkdir -p "$(dirname "$CHANGELOG_ENTRY")"
printf '%s\n' "$NOTE" > "$CHANGELOG_ENTRY"

# Insert a new "## [X.Y.Z] — <date>" section right before the most recent
# existing entry -- release.yml's awk extractor reads this section verbatim
# as the GitHub Release body.
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

m = re.search(r"^## \[", text, re.MULTILINE)
if m:
    text = text[: m.start()] + entry + text[m.start() :]
else:
    text = text.rstrip("\n") + "\n\n" + entry

text = text.rstrip("\n") + "\n" + link

with open(path, "w") as f:
    f.write(text)
PYEOF

git add "$WHATSNEW" "$CHANGELOG_ENTRY" CHANGELOG.md
git commit -m "docs: release notes for v${VERSION}"

git tag -a "v${VERSION}" -m "Crema ${VERSION}"

cat <<EOF

Committed release notes and tagged v${VERSION} (versionCode ${VERSION_CODE}).

Review, then push:
  git push origin main
  git push origin v${VERSION}
EOF
