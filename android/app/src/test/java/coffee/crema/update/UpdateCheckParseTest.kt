package coffee.crema.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
