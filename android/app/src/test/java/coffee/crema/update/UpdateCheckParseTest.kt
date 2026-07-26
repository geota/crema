package coffee.crema.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the nightly-version parse against the release's real asset layout
 * (apk + `.apk.idsig` + `.apk.sha256`). The GitHub API's asset order is
 * not contractual — taking `assets[0]` once parsed the `.idsig` name and
 * prompted users on the latest nightly with an "update" to the very build
 * they were running.
 */
class UpdateCheckParseTest {
    private val apk = "crema-nightly-0.0.4-nightly.25+g9eabfed.apk"
    private val idsig = "$apk.idsig"
    private val sha256 = "$apk.sha256"

    @Test
    fun `picks the apk regardless of asset order`() {
        val want = "0.0.4-nightly.25+g9eabfed"
        assertEquals(want, nightlyVersionFromAssets(listOf(apk, idsig, sha256)))
        assertEquals(want, nightlyVersionFromAssets(listOf(idsig, apk, sha256)))
        assertEquals(want, nightlyVersionFromAssets(listOf(sha256, idsig, apk)))
    }

    @Test
    fun `sidecar-only or empty asset lists parse to nothing`() {
        assertNull(nightlyVersionFromAssets(listOf(idsig, sha256)))
        assertNull(nightlyVersionFromAssets(emptyList()))
    }

    @Test
    fun `foreign assets never masquerade as a version`() {
        assertNull(nightlyVersionFromAssets(listOf("mapping.txt", "notes.apk.txt")))
    }
}

/**
 * The "you're on the latest" contract. The nightly buildType used to append
 * `-nightly` on top of CI's already-nightly versionName, so the installed name
 * never equalled the release asset it came from: every check reported an
 * update to the running build and the About row never said "latest". These
 * pin both halves — the canonicalisation that rescues builds already in the
 * field, and the newer-not-merely-different comparison.
 */
class UpdateVersionCompareTest {
    private val nightly42 = "0.0.5-nightly.42+gabc1234"
    private val doubled42 = "$nightly42-nightly"
    private val nightly43 = "0.0.5-nightly.43+gdef5678"

    @Test
    fun `the doubled nightly marker is the same build as the release asset`() {
        assertEquals(nightly42, canonicalVersion(doubled42))
        assertTrue(isSameBuild(doubled42, nightly42))
        assertFalse(isNewerBuild(doubled42, nightly42))
    }

    @Test
    fun `canonicalisation keeps a single nightly marker`() {
        assertEquals(nightly42, canonicalVersion(nightly42))
        // A local `assembleNightly` (versionName defaults to "0.1") keeps its
        // off-train marker — stripping it would claim to be a stable build.
        assertEquals("0.1-nightly", canonicalVersion("0.1-nightly"))
    }

    @Test
    fun `a newer nightly is an update, an older or equal one is not`() {
        assertTrue(isNewerBuild(nightly42, nightly43))
        assertFalse(isNewerBuild(nightly43, nightly42))
        assertFalse(isNewerBuild(nightly42, nightly42))
        // Same commit count, different sha (a re-run of the same commit) is
        // not a new build.
        assertFalse(isNewerBuild(nightly42, "0.0.5-nightly.42+g0000000"))
    }

    @Test
    fun `a nightly on a newer base semver wins over a higher commit count`() {
        assertTrue(isNewerBuild("0.0.5-nightly.90+gaaaaaaa", "0.1.0-nightly.3+gbbbbbbb"))
        assertFalse(isNewerBuild("0.1.0-nightly.3+gbbbbbbb", "0.0.5-nightly.90+gaaaaaaa"))
    }

    @Test
    fun `stable releases compare by semver, not string order`() {
        assertTrue(isNewerBuild("0.0.9", "0.0.10"))
        assertFalse(isNewerBuild("0.0.10", "0.0.9"))
        assertFalse(isNewerBuild("1.2.3", "1.2.3"))
        assertTrue(isSameBuild("1.2.3", "1.2.3"))
    }

    @Test
    fun `a missing published version is never an update`() {
        assertFalse(isNewerBuild(nightly42, null))
        assertFalse(isSameBuild(nightly42, null))
    }

    @Test
    fun `an unorderable pair still reports a real difference`() {
        // A local dev build ("0.1") against a published nightly: we can't order
        // them, but withholding the notice would hide a genuine update.
        assertTrue(isNewerBuild("0.1-nightly", nightly42))
    }
}
