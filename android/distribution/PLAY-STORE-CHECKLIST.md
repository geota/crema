# Google Play submission checklist — Crema

Legend: **✅ done / automated** · **☐ your action** · **⚠️ decide / verify**

Most of the engineering is already in place — signed AAB build, Play-publish CI
step, listing text, hosted privacy policy. The remainder is mostly Play Console
work + credentials only you can create. Drafts for the Console forms are below.

---

## 0. Prerequisites (one-time)
- ☐ **Play Developer account** ($25 one-off): <https://play.google.com/console/signup>
- ✅ Listing title **"Crema — for Decent Espresso"** (launcher label stays plain "Crema") — disambiguates from the other Play "Crema" apps (e.g. `com.cremasocial.crema`); the **"for X"** framing keeps it a third-party client, not implying Decent authorship. Package id **`dev.maceiras.crema`** (reverse-DNS of a domain you own) — permanent once published.

## 1. Signing & keys
- ✅ Release `signingConfig` wired in `android/app/build.gradle.kts` (reads `KEYSTORE_*` env or `local.properties`).
- ✅ `release.yml` builds a **signed AAB** (`:app:bundleRelease`) when the keystore secrets exist.
- ☐ **Generate an upload keystore** (store it forever — keep a backup):
  ```sh
  keytool -genkeypair -v -keystore upload.keystore -alias crema-upload \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Crema"
  base64 -i upload.keystore | tr -d '\n'   # value → GitHub secret KEYSTORE_BASE64
  ```
  Add repo **secrets**: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS=crema-upload`, `KEY_PASSWORD`.
- ☐ **Enroll in Play App Signing** (Console → Test and release → App integrity). Google holds the real signing key; you upload signed with the upload key above. Strongly recommended (recover if you ever lose the upload key).

## 2. Build config — ✅ audited, Play-ready
- ✅ `applicationId = dev.maceiras.crema` (permanent after publish). `namespace` stays `coffee.crema` (the R/BuildConfig source package) — AGP decouples the published id from the code package, so no source refactor.
- ✅ `minSdk 31`, `targetSdk 36`, `compileSdk 36`; AAB output; versionCode/Name from the `vX.Y.Z` tag.
- ✅ Manifest is clean: `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT`, `INTERNET` only — no location, no `debuggable`, no cleartext; BLE required.
- ⚠️ **Verify `targetSdk = 36` is a STABLE released API** at submission time — Play rejects apps targeting a *preview* SDK. If Android 16 (API 36) is still preview, set `targetSdk`/`compileSdk = 35`.
- ⚠️ Optional: enable R8 (`isMinifyEnabled = true` + `isShrinkResources = true`) for a smaller AAB. Currently off (see the note at `build.gradle.kts`). Not blocking; if you enable it, test a release build end-to-end (Compose + UniFFI keep-rules).

## 3. Store listing — text — ✅ prepared in `fastlane/metadata/android/en-US/`
- ✅ `title.txt` "Crema — for Decent Espresso" (27/30) · `short_description.txt` (74/80, leads with "Decent DE1") · `full_description.txt` (863/4000, with the "not affiliated with Decent" disclaimer).
- ✅ Release notes: `android/distribution/whatsnew/whatsnew-en-US` (374/500).
- ⚠️ **Trademark**: listing title uses the **"for Decent Espresso"** framing — marks it a third-party client (not implying Decent authorship/endorsement), the recognized safe-harbor pattern; the launcher label stays plain **"Crema"**. Avoid "Decent Crema" / "Crema - Decent Espresso" (no "for" → reads as first-party). Keep the "not affiliated with Decent" disclaimer in the full description. If review still flags it, attest you're a compatible third-party client for the DE1's public Bluetooth protocol.

## 4. Store listing — graphics
- ✅ **App icon 512×512** — `brand/out/icon-512.png` (and the in-app adaptive icon is set).
- ✅ **Feature graphic 1024×500** — auto-generated → `fastlane/metadata/android/en-US/images/featureGraphic.png`. Replace if you want a more polished design.
- ⚠️ **Phone screenshots** — 6 starter shots curated into `fastlane/metadata/android/en-US/images/phoneScreenshots/` from the validation run. **Review + refresh**: they're emulator captures (1280×2856); confirm clean data, 24-bit PNG (no alpha), and current Play size rules (320–3840 px). 2 minimum, up to 8.
- ☐ Tablet screenshots (optional but recommended — the app has a real tablet layout): capture a few on a tablet emulator → `…/images/tenInchScreenshots/`.
- ☐ Promo video (optional, YouTube URL).

## 5. Play Console — app setup
- ☐ **Create app**: Console → Create app → name "Crema", App, Free, accept declarations.
- ☐ **First AAB upload (manual)**: cut a release (§8) and upload the AAB to **Internal testing** by hand once — Play usually requires a manual first upload before the service-account API can publish.
- ☐ **Store listing**: paste the prepared text + upload icon/feature-graphic/screenshots.
- ☐ **Privacy policy URL**: `https://crema.maceiras.dev/privacy` (✅ already hosted; `/terms` too).
- ☐ Contact email + (optional) website `https://crema.maceiras.dev`.

## 6. Play Console — compliance forms (drafts below)
- ☐ **Data safety** — see draft.
- ☐ **Content rating** (IARC) — see draft.
- ☐ **Target audience**: 18+ (or "13+"); not directed at children.
- ☐ **Ads**: No ads.
- ☐ **App access**: *All functionality available without special access* — Visualizer sign-in is optional and everything works signed-out, so no test credentials needed.
- ☐ Government / financial / health / news app: **No** to all.

### Data safety — draft
Crema is local-first. **Collects/shares user data: only the optional Visualizer upload.**
- **Bluetooth (DE1 + scale):** device telemetry stays **on-device** — not collected or sent to the developer.
- **Visualizer (opt-in, user-initiated):** if the user signs in to visualizer.coffee, the app uploads **shot data** (telemetry + optional bean/profile metadata) to Visualizer over HTTPS.
  - Data types: *App activity* (shot history) — collected **and** shared with a third party (Visualizer), **only on the user's action**, for *App functionality*.
  - The OAuth token is stored on-device (not sent to the developer).
- No location, no ads/analytics SDKs, no personal identifiers collected by the app.
- **Encrypted in transit:** Yes. **Deletion:** user-controlled (sign out / delete in their Visualizer account).
- Form top-level: *Does your app collect or share user data?* → **Yes** (cover only the Visualizer path).

### Content rating — draft (IARC)
- App category: **Food & Drink** (or Tools/Reference). Not a game.
- Violence / sexual / profanity / controlled substances / gambling / horror: **None**.
- Shares the user's own shots to their own Visualizer account (not in-app social UGC).
- Expected: **Everyone / PEGI 3**.

## 7. Release tracks & CI publishing
- ✅ `release.yml` uploads the AAB to the **internal** track as a **draft** on a `vX.Y.Z` tag, when `PLAY_SERVICE_ACCOUNT_JSON` is set (`r0adkll/upload-google-play`, `whatsNewDirectory` wired).
- ☐ **Service account** for CI publishing:
  1. Google Cloud Console → IAM → create a service account → create a **JSON key**.
  2. Play Console → Users & permissions → invite that service-account email → grant **Release to testing tracks** (or Admin).
  3. Add the JSON as repo secret `PLAY_SERVICE_ACCOUNT_JSON`.
- ☐ Promote internal → closed (alpha/beta) → production in the Console after testing.

## 8. Cut the first release
- ☐ Confirm secrets: `KEYSTORE_BASE64` (+ password/alias/key) and, for auto-publish, `PLAY_SERVICE_ACCOUNT_JSON`.
- ☐ Tag & push: `git tag v0.1.0 && git push origin v0.1.0` → `release.yml` builds the signed AAB + APK + PWA, and (if the service account is set) drafts it to Internal testing.
- ☐ In the Console: finish the listing + all compliance forms, add testers, then **send for review**.

---

## What I automated / prepared this pass
- ✅ Audited `build.gradle.kts` + `AndroidManifest.xml` → Play-ready (flagged the two ⚠️: confirm targetSdk 36 is stable; optional R8).
- ✅ Verified the fastlane listing text is within Play limits.
- ✅ Generated the 1024×500 **feature graphic** into the fastlane structure.
- ✅ Curated **6 phone screenshots** into the fastlane structure (starter set — review before submitting).
- ✅ Wrote this checklist + **Data Safety** and **content-rating** drafts + the **keystore** and **service-account** commands.

> The whole `fastlane/metadata/android/en-US/` tree is also `fastlane supply`-compatible, so once the keystore + service account exist you can push the *entire* listing (text + graphics + screenshots) from CI, not just the AAB — say the word and I'll wire a `supply` step into `release.yml`.
