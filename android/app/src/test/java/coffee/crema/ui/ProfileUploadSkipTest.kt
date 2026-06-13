package coffee.crema.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the issue-11 upload-skip decision. The end-to-end skip
 * (FFI fingerprint + BLE upload + Espresso) needs a DE1 we don't have; this
 * pins the decision boundary that gates it.
 */
class ProfileUploadSkipTest {
    @Test
    fun `skips when the fingerprint matches a ready DE1`() {
        assertTrue(shouldSkipProfileUpload("abc", "abc", de1Ready = true))
    }

    @Test
    fun `does not skip when the fingerprint differs`() {
        assertFalse(shouldSkipProfileUpload("abc", "xyz", de1Ready = true))
    }

    @Test
    fun `does not skip when the DE1 is not ready (cache may be stale)`() {
        assertFalse(shouldSkipProfileUpload("abc", "abc", de1Ready = false))
    }

    @Test
    fun `does not skip when the fingerprint could not be computed`() {
        assertFalse(shouldSkipProfileUpload(null, "abc", de1Ready = true))
    }

    @Test
    fun `does not skip when nothing has been uploaded to this connection yet`() {
        assertFalse(shouldSkipProfileUpload("abc", null, de1Ready = true))
    }
}
