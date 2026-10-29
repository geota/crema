package coffee.crema.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Review 05 P3-1: the restore notice names every sharing setting a restore kept. */
class RestoreGuardTest {
    @Test
    fun noNoticeWhenNothingWasHeld() {
        assertNull(heldSharingNotice(emptyList()))
        assertNull(heldSharingNotice(listOf("somethingElse")))
    }

    @Test
    fun noticeNamesEachHeldField() {
        assertEquals(
            "Kept this device's Visualizer upload privacy; change them in Settings › Sharing if you want the backup's.",
            heldSharingNotice(listOf("privacy")),
        )
        assertEquals(
            "Kept this device's Visualizer upload privacy, tasting notes in uploads and auto-upload; " +
                "change them in Settings › Sharing if you want the backup's.",
            heldSharingNotice(listOf("privacy", "includeNotes", "autoUpload")),
        )
    }
}
