package coffee.crema.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // Final review 05 N-4a: directions, background sync and the profile flag are held too.
    @Test
    fun noticeNamesEveryFieldTheCoreCanHold() {
        val notice = heldSharingNotice(GUARDED_SHARING_FIELDS)!!
        for (label in listOf(
            "upload privacy", "tasting notes in uploads", "auto-upload", "profile in uploads",
            "background sync", "shot sync direction", "bean sync direction", "roaster sync direction",
        )) {
            assertTrue("missing $label in: $notice", label in notice)
        }
    }
}
