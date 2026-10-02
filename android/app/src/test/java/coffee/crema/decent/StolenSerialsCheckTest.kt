package coffee.crema.decent

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stolen-serial fetch + cache wrapper. The list rules are pinned in core
 * (`de1_domain::stolen_serials`); these cover the shell's half — the daily
 * cadence, caching only real lists, staying silent on failure — with core's
 * rules faked so the JVM needs no native library.
 */
class StolenSerialsCheckTest {
    private val day = 24L * 60 * 60 * 1000

    private class MemStore : StolenSerialsCacheStore {
        var cache: StolenSerialsCache? = null
        override suspend fun read() = cache
        override suspend fun write(cache: StolenSerialsCache) { this.cache = cache }
    }

    /** Fake rules: a body "ok:76,317" is a list of those serials. */
    private val rules = StolenSerialRules(
        url = { "https://example.test/stolen.json" },
        refreshDue = { last, now -> last == null || now - last >= day },
        listIsValid = { it.startsWith("ok:") },
        serialOnList = { serial, body ->
            body.removePrefix("ok:").split(',').any { it == serial.toString() }
        },
    )

    private var clock = 1_000_000L

    @Test
    fun `flags a listed serial and caches the list`() = runBlocking {
        val store = MemStore()
        var fetches = 0
        val check = StolenSerialsCheck(store, { fetches++; "ok:76,317" }, { clock }, rules)
        assertTrue(check.isStolen(317u))
        assertFalse(check.isStolen(6262u))
        assertEquals(1, fetches)
        assertEquals("ok:76,317", store.cache?.body)
    }

    @Test
    fun `refetches at most once a day`() = runBlocking {
        var fetches = 0
        val check = StolenSerialsCheck(MemStore(), { fetches++; "ok:76" }, { clock }, rules)
        check.isStolen(76u)
        clock += day - 1
        check.isStolen(76u)
        assertEquals(1, fetches)
        clock += 1
        check.isStolen(76u)
        assertEquals(2, fetches)
    }

    @Test
    fun `failures are silent and keep the cached list`() = runBlocking {
        var fail = false
        val check = StolenSerialsCheck(
            MemStore(),
            { if (fail) error("offline") else "ok:76" },
            { clock },
            rules,
        )
        assertTrue(check.isStolen(76u))
        fail = true
        clock += day
        assertTrue(check.isStolen(76u))
    }

    @Test
    fun `never caches an error page or a failed status`() = runBlocking {
        val store = MemStore()
        assertFalse(StolenSerialsCheck(store, { "404: Not Found" }, { clock }, rules).isStolen(76u))
        assertNull(store.cache)
        assertFalse(StolenSerialsCheck(store, { null }, { clock }, rules).isStolen(76u))
        assertNull(store.cache)
    }
}
