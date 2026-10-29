package coffee.crema.ui.beans

import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Final cohort review 2026-10-28, 06 #15: `shotRowSummary` built a
 * SimpleDateFormat per row per recomposition. The formatter is now cached per
 * thread and rebuilt only when the locale or time zone changes.
 */
class ShotRowDateFormatTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun theFormatterIsReusedForTheSameLocaleAndZone() {
        assertSame(shotRowDateFormat(Locale.UK, utc), shotRowDateFormat(Locale.UK, utc))
    }

    @Test
    fun aLocaleOrZoneChangeGetsAFreshFormatter() {
        val uk = shotRowDateFormat(Locale.UK, utc)
        val french = shotRowDateFormat(Locale.FRANCE, utc)
        assertNotSame(uk, french)
        // 2024-03-15T12:00:00Z
        val noon = Date(1_710_504_000_000)
        assertEquals("15 Mar", shotRowDateFormat(Locale.UK, utc).format(noon))
        assertEquals("15 mars", shotRowDateFormat(Locale.FRANCE, utc).format(noon))
        // The same instant is already the 16th in Auckland.
        val auckland = TimeZone.getTimeZone("Pacific/Auckland")
        assertEquals("16 Mar", shotRowDateFormat(Locale.UK, auckland).format(noon))
        assertEquals("15 Mar", shotRowDateFormat(Locale.UK, utc).format(noon))
    }
}
