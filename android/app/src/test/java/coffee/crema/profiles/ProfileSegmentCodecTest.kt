package coffee.crema.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Pure-JVM tests for the profile-segment JSON codec (issue #23 follow-up):
 * `volumeLimitMl` is an Int on the wire, but pre-2026-07 saves carry floats
 * (`100.0`) from when the model typed it as Float — those files must keep
 * decoding, and re-saves must come out as plain integers.
 */
class ProfileSegmentCodecTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `legacy float volume limits decode to ints`() {
        val seg = json.decodeFromString(ProfileSegment.serializer(), """{"id":"s1","volumeLimitMl":100.0}""")
        assertEquals(100, seg.volumeLimitMl)
        // Fractional values round rather than fail (parity with the core's lenient_uint).
        val frac = json.decodeFromString(ProfileSegment.serializer(), """{"id":"s1","volumeLimitMl":99.6}""")
        assertEquals(100, frac.volumeLimitMl)
    }

    @Test
    fun `int absent and null volume limits decode`() {
        assertEquals(
            100,
            json.decodeFromString(ProfileSegment.serializer(), """{"id":"s1","volumeLimitMl":100}""").volumeLimitMl,
        )
        assertNull(json.decodeFromString(ProfileSegment.serializer(), """{"id":"s1"}""").volumeLimitMl)
        assertNull(json.decodeFromString(ProfileSegment.serializer(), """{"id":"s1","volumeLimitMl":null}""").volumeLimitMl)
    }

    @Test
    fun `volume limits encode as plain integers`() {
        val out = json.encodeToString(ProfileSegment.serializer(), ProfileSegment(id = "s1", volumeLimitMl = 100))
        assertTrue("\"volumeLimitMl\":100" in out, out)
        assertFalse("100.0" in out, out)
    }
}
