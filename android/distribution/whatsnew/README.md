# Play Store release notes ("What's new")

Files here become the **user-facing "What's new"** text on the Google Play
listing. They're uploaded by the `Publish AAB to Google Play` step in
`.github/workflows/release.yml` — via `r0adkll/upload-google-play`'s
`whatsNewDirectory: android/distribution/whatsnew`. No Fastlane involved; it's
just a directory + one workflow input.

## Convention

- **One file per locale**, named `whatsnew-<locale>` — **no file extension**
  (e.g. `whatsnew-en-US`, `whatsnew-de-DE`, `whatsnew-es-ES`).
- Locale codes must be Play's BCP-47 form (`en-US`, not `en`).
- **≤ 500 characters** each — Play's hard limit; the upload fails if longer.
- Plain text. `•` bullets render well in the Play "What's new" panel.

## Workflow

**Edit the relevant `whatsnew-*` file before cutting a release tag.** The notes
are read at upload time, so whatever is committed on the tagged commit ships.
This is intentionally a single *current* file per locale — there's no
per-versionCode history (unlike Fastlane's `changelogs/<versionCode>.txt`).

These are **end-user** notes, deliberately separate from the GitHub Release
notes, which `release.yml` auto-generates from commits for developers /
sideloaders.

To add a language, drop in another `whatsnew-<locale>` file.

**Also update `fastlane/README.md`'s sibling step** — F-Droid/IzzyOnDroid read
a separate, permanent `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`
per release (not this mutable "current" file). Copy this text there too
before tagging.
