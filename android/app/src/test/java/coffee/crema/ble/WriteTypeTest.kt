package coffee.crema.ble

import kotlin.test.Test
import kotlin.test.assertEquals

/** The transport picks the write type from the core's
 *  `command_write_no_response` flag (issue 04: Skale, gen-1 Acaia, Timemore). */
class WriteTypeTest {

    @Test
    fun `a flagged write goes without response`() {
        assertEquals(BleWriteType.WITHOUT_RESPONSE, writeTypeFor(withoutResponse = true))
    }

    @Test
    fun `an unflagged write keeps the stack default`() {
        // DEFAULT = Nordic chooses from the characteristic's properties, the
        // legacy WRITE_TYPE_DEFAULT behaviour every other write relies on.
        assertEquals(BleWriteType.DEFAULT, writeTypeFor(withoutResponse = false))
    }
}
