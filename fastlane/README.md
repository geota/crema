# F-Droid / IzzyOnDroid store listing

This is the [Fastlane `supply` metadata layout](https://docs.fastlane.tools/actions/supply/#metadata-structure)
that F-Droid and IzzyOnDroid's tooling read directly from the repo — the same
convention `fastlane/metadata/android/<locale>/...` most F-Droid apps use.

## Single source of truth: the Play listing

`title.txt`, `short_description.txt`, `full_description.txt`, and `images/`
are **symlinks** into `android/distribution/play-listing/en-US/` — that
directory stays the one place this copy is edited. Nothing here is a real
file except `changelogs/`.

## `changelogs/`

Unlike Play's `android/distribution/whatsnew/` (a single *current* file,
overwritten each release — see that directory's own README), Fastlane wants
one **frozen, permanent** file per release, named by the exact `versionCode`
(the `M*1e8 + m*1e6 + p*1e4` scheme from `.github/workflows/release.yml`,
e.g. `v0.0.4` → `40000.txt`). Never symlink a `changelogs/<code>.txt` to the
mutable `whatsnew-en-US` file — it needs to keep reading correctly for that
released version after `whatsnew-en-US` moves on to the next one.

**Before cutting a release tag**, after updating
`android/distribution/whatsnew/whatsnew-en-US` for Play, copy that same text
into a new `changelogs/<versionCode>.txt` here.
