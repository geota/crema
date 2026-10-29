package coffee.crema.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backup build is off the main thread (cohort review 2026-10-25, Android
 * P1-2): the snapshot reads live state on the caller's thread, the heavy
 * serialise + export + zip runs on the given dispatcher. Before the fix the
 * whole build ran inline on Main, including the daily Drive auto-backup at
 * launch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupOffMainTest {

    @Test
    fun snapshotOnCallerBuildOnTheBackgroundDispatcher() {
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "backup-worker") }
        val dispatcher = worker.asCoroutineDispatcher()
        try {
            // Thread names carry a " @coroutine#N" suffix in debug mode; compare the base.
            fun here() = Thread.currentThread().name.substringBefore(" @")
            var snapshotThread = ""
            var buildThread = ""
            val caller = here()
            val out = runBlocking {
                snapshotThenBuild(
                    snapshot = { snapshotThread = here(); "state" },
                    dispatcher = dispatcher,
                ) { snap ->
                    buildThread = here()
                    "$snap-built"
                }
            }
            assertEquals("state-built", out)
            assertEquals(caller, snapshotThread)
            assertEquals("backup-worker", buildThread)
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun nothingToBackUpSkipsTheBuild() {
        var built = false
        val out = runBlocking {
            snapshotThenBuild<String, String>(
                snapshot = { null },
                dispatcher = kotlinx.coroutines.Dispatchers.Default,
            ) { built = true; it }
        }
        assertNull(out)
        assertTrue(!built)
    }

    @Test
    fun zipCarriesTheJsonlAndOneEntryPerPhoto() {
        val bytes = zipBackupBytes(
            "{\"kind\":\"header\"}\n",
            sequenceOf("bean-1" to byteArrayOf(1, 2, 3), "bean-2" to byteArrayOf(4)),
        )
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                entries[e.name] = zis.readBytes()
            }
        }
        assertEquals(listOf("backup.jsonl", "images/bean-1", "images/bean-2"), entries.keys.toList())
        assertEquals("{\"kind\":\"header\"}\n", entries.getValue("backup.jsonl").toString(Charsets.UTF_8))
        assertEquals(listOf<Byte>(1, 2, 3), entries.getValue("images/bean-1").toList())
    }
}
