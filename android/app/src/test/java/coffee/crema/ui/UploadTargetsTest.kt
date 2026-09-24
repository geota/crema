package coffee.crema.ui

import coffee.crema.history.StoredShot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-row Upload menu entry over every cloud destination — mirrors the web's upload-targets.vitest.ts. */
class UploadTargetsTest {
    private fun viz(enabled: Boolean = true, uploaded: Boolean = false, url: String? = null) =
        UploadTarget(UploadTargetId.Visualizer, "Visualizer", enabled, uploaded, url)
    private fun dec(enabled: Boolean = true, uploaded: Boolean = false, url: String? = null) =
        UploadTarget(UploadTargetId.Decent, "Decent", enabled, uploaded, url)

    @Test
    fun `one dimmed row when nothing is enabled`() {
        val e = uploadMenuEntry(listOf(viz(enabled = false), dec(enabled = false)))
        assertFalse(e.enabled)
        assertEquals("Upload shot", e.title)
        assertTrue(e.targets.isEmpty())
    }

    @Test
    fun `names only the enabled destinations still missing the shot`() {
        assertEquals("Upload to Visualizer + Decent", uploadMenuEntry(listOf(viz(), dec())).title)
        assertEquals("Upload to Decent", uploadMenuEntry(listOf(viz(uploaded = true), dec())).title)
        assertEquals("Upload to Visualizer", uploadMenuEntry(listOf(viz(), dec(enabled = false))).title)
        assertEquals(listOf(UploadTargetId.Decent), uploadMenuEntry(listOf(viz(uploaded = true), dec())).targets.map { it.id })
    }

    @Test
    fun `re-uploads to every enabled destination once the shot is everywhere`() {
        val e = uploadMenuEntry(listOf(viz(uploaded = true), dec(enabled = false, uploaded = true)))
        assertTrue(e.reupload)
        assertEquals("Re-upload to Visualizer", e.title)
        assertEquals(listOf(UploadTargetId.Visualizer), e.targets.map { it.id })
    }

    /** A destination with Decent-like rules: id-bound, a 5 s backlog floor, a public link only with a real id. */
    private class FakeDest(
        override val id: UploadTargetId,
        override val enabled: Boolean = true,
        private val boundId: (StoredShot) -> String?,
        private val minMs: Long = 0,
        private val publicLink: Boolean = true,
    ) : UploadDestination {
        override val autoUpload = true
        override var onUploadOutcome: ((String, UploadOutcome) -> Unit)? = null
        override fun isUploaded(shot: StoredShot) = boundId(shot) != null
        override fun viewUrl(shot: StoredShot) = boundId(shot)?.let { "https://x/${id.name}/$it" }
        override fun shareUrl(shot: StoredShot) = if (publicLink && boundId(shot)?.startsWith("uploaded:") == false) viewUrl(shot) else null
        override fun inBacklog(shot: StoredShot) = boundId(shot) == null && shot.durationMs >= minMs
        override fun maybeAutoUpload(shot: StoredShot, fullSamples: List<TelemetrySample>?) = false
        override fun pushShot(shot: StoredShot, replace: Boolean) = false
        override suspend fun uploadUnsentNow(shots: List<StoredShot>) = DrainResult()
        override suspend fun setAutoUploadNow(enabled: Boolean) {}
    }

    private val vizDest = FakeDest(UploadTargetId.Visualizer, boundId = { it.visualizerId })
    private fun decDest(enabled: Boolean = true) = FakeDest(UploadTargetId.Decent, enabled, boundId = { it.decentId }, minMs = 5_000)

    @Test
    fun `view rows only for destinations that hold the shot, built from the stored ids`() {
        assertEquals(listOf(UploadTargetId.Visualizer), viewableTargets(listOf(viz(uploaded = true, url = "v"), dec(uploaded = true), dec())).map { it.id })
        val shot = StoredShot(id = "s", completedAtMs = 0, durationMs = 30_000, visualizerId = "v1", decentId = "77", machineSerial = "6262")
        val targets = uploadTargetsFor(shot, listOf(vizDest, decDest(enabled = false)))
        assertEquals("https://x/Visualizer/v1", targets[0].viewUrl)
        assertEquals("https://x/Decent/77", targets[1].viewUrl)
        assertEquals("Re-upload to Visualizer", uploadMenuEntry(targets).title)
    }

    @Test
    fun `a placeholder Decent id can be viewed but not shared`() {
        val shot = StoredShot(id = "s", completedAtMs = 0, durationMs = 30_000, decentId = "uploaded:1")
        val targets = uploadTargetsFor(shot, listOf(vizDest, decDest()))
        assertEquals(listOf(UploadTargetId.Decent), viewableTargets(targets).map { it.id })
        assertTrue(shareableTargets(targets).isEmpty())
    }

    @Test
    fun `a short shot is uploadable by hand but never missing from Decent`() {
        val flush = StoredShot(id = "f", completedAtMs = 0, durationMs = 3_000, visualizerId = "v1")
        val targets = uploadTargetsFor(flush, listOf(vizDest, decDest()))
        assertTrue(targets[1].enabled)
        assertFalse(targets[1].counted)
        assertEquals(UploadPip.Uploaded, uploadPipFor(targets))
        assertEquals("Upload to Decent", uploadMenuEntry(targets).title)
        // The backlog count agrees with the pip.
        assertEquals(0, missingUploadTotal(listOf(flush), listOf(vizDest, decDest())))
        val real = StoredShot(id = "r", completedAtMs = 0, durationMs = 30_000)
        assertEquals(1, missingUploadTotal(listOf(flush, real), listOf(vizDest, decDest())))
        assertEquals(
            mapOf(UploadTargetId.Visualizer to 1, UploadTargetId.Decent to 1),
            missingUploadCounts(listOf(flush, real), listOf(vizDest, decDest())),
        )
    }
}
