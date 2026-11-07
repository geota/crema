package coffee.crema.persist

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Only a decode failure can quarantine. Anything else thrown on the way says
 * nothing about the file, so it stays, saves are refused, and there's a way out.
 */
class LoadOutcomeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val json = Json
    private fun target() = File(tmp.root, "shots.json")
    private fun decodeList(text: String): List<Int> = json.decodeFromString(text)
    private fun bak() = SafeFile.backupOf(target())
    private fun tmpFile() = File(tmp.root, "shots.json.tmp")

    @Before fun reset() {
        SafeFile.resetForTest()
        Stores.resetForTest()
        KeptAsideNotices.drain()
        SafeFile.openAllForTest()
    }

    @After fun cleanup() {
        target().setReadable(true)
        SafeFile.resetForTest()
        Stores.resetForTest()
    }

    private fun assertLeftInPlace(text: String) {
        assertEquals(text, target().readText())
        assertEquals(emptyList(), SafeFile.keptAside(target()))
    }

    @Test
    fun anOomFromDecodeLeavesTheFileInPlace() {
        target().writeText("[1,2]")
        val outcome = SafeFile.load(target()) { throw OutOfMemoryError("Java heap space") }
        assertIs<SafeLoad.Unavailable>(outcome)
        assertLeftInPlace("[1,2]")
        assertTrue(SafeFile.isBlocked(target()))
    }

    @Test
    fun anIoErrorFromReadLeavesTheFileInPlace() {
        target().writeText("[1,2]")
        target().setReadable(false)
        assertIs<SafeLoad.Unavailable>(SafeFile.load(target(), decode = ::decodeList))
        target().setReadable(true)
        assertLeftInPlace("[1,2]")
    }

    @Test
    fun anythingElseFromDecodeIsNotCorruption() {
        target().writeText("[1,2]")
        assertIs<SafeLoad.Unavailable>(SafeFile.load(target()) { throw IllegalStateException("keystore") })
        assertLeftInPlace("[1,2]")
    }

    @Test
    fun aDecodeFailureIsCorrupt() {
        for (bad in listOf<(String) -> Any>(
            { throw SerializationException("bad") },
            { json.parseToJsonElement(it).jsonObject },
            { it.toInt() },
        )) {
            target().writeText("[1,2]")
            assertIs<SafeLoad.Corrupt>(SafeFile.load(target(), decode = bad))
            assertFalse(target().exists())
        }
    }

    @Test
    fun aSaveToAnUnavailableStoreIsRefused() {
        target().writeText("[1,2]")
        SafeFile.load(target()) { throw OutOfMemoryError() }
        assertFailsWith<IOException> { SafeFile.write(target(), "[]") }
        assertFalse(saveStore(target(), "shot history", "[]"))
        assertEquals("[1,2]", target().readText())
    }

    @Test
    fun aBackupIsNotTakenOverAFileThatMayBeFine() {
        target().writeText("[1,2]")
        bak().writeText("[1]")
        assertIs<SafeLoad.Unavailable>(SafeFile.load(target()) { throw OutOfMemoryError() })
        assertLeftInPlace("[1,2]")
        assertEquals("[1]", bak().readText())
    }

    @Test
    fun aRecoveryCopyThatCantBeReadAlsoRefuses() {
        tmpFile().writeText("[1,2,3]")
        assertIs<SafeLoad.Unavailable>(SafeFile.load(target()) { throw OutOfMemoryError() })
        assertTrue(SafeFile.isBlocked(target()))
        assertEquals("[1,2,3]", tmpFile().readText())
    }

    @Test
    fun aLoadThatWorksLiftsTheRefusal() {
        target().writeText("[1,2]")
        SafeFile.load(target()) { throw OutOfMemoryError() }
        assertEquals(SafeLoad.Loaded(listOf(1, 2)), SafeFile.load(target(), decode = ::decodeList))
        SafeFile.write(target(), "[3]")
        assertEquals("[3]", target().readText())
    }

    @Test
    fun setAsideNeedsNoReadAndLiftsTheRefusal() {
        target().writeText("[1,2]")
        target().setReadable(false)
        SafeFile.load(target(), decode = ::decodeList)

        val kept = assertNotNull(SafeFile.setAside(target()))
        assertEquals(kept, SafeFile.keptAside(target()).single())
        assertFalse(target().exists())
        assertFalse(SafeFile.isBlocked(target()))
        kept.setReadable(true)
        assertEquals("[1,2]", kept.readText())

        SafeFile.write(target(), "[]")
        assertEquals("[]", target().readText())
    }

    @Test
    fun setAsideGetsPastACopyThatCantBeReadEither() {
        target().writeText("[1,2]")
        bak().writeText("[1]")
        SafeFile.load(target()) { throw OutOfMemoryError() }
        assertNotNull(SafeFile.setAside(target()))
        assertIs<SafeLoad.Unavailable>(SafeFile.load(target()) { throw OutOfMemoryError() })
        assertNotNull(SafeFile.setAside(target()))
        assertEquals(SafeLoad.Absent, SafeFile.load(target(), decode = ::decodeList))
        assertEquals(2, SafeFile.keptAside(target()).size)
    }

    @Test
    fun aFailedSetAsideKeepsTheRefusal() {
        val dir = tmp.newFolder("ro")
        val t = File(dir, "prefs.json").apply { writeText("[1]") }
        SafeFile.load(t) { throw OutOfMemoryError() }
        dir.setWritable(false)
        try {
            assertEquals(null, SafeFile.setAside(t))
            assertTrue(SafeFile.isBlocked(t))
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    fun setAsideIsANoOpUnlessSavesAreRefused() {
        target().writeText("[1,2]")
        bak().writeText("[1]")
        SafeFile.load(target(), decode = ::decodeList)
        assertEquals(null, SafeFile.setAside(target()), "a double tap once it's been lifted")
        assertEquals("[1,2]", target().readText())
        assertEquals("[1]", bak().readText())
    }

    @Test
    fun maxKeptNeverPrunesASetAside() {
        target().writeText("[1,2]")
        SafeFile.load(target()) { throw OutOfMemoryError() }
        val kept = assertNotNull(SafeFile.setAside(target()))
        repeat(SafeFile.MAX_KEPT + 2) {
            target().writeText("{bad")
            SafeFile.load(target(), decode = ::decodeList)
        }
        assertTrue(kept.exists())
        assertEquals(SafeFile.MAX_KEPT + 1, SafeFile.keptAside(target()).size)
    }

    @Test
    fun aSetAsideGoesWithEraseAllData() {
        target().writeText("[1]")
        SafeFile.load(target()) { throw OutOfMemoryError() }
        val kept = assertNotNull(SafeFile.setAside(target()))
        SafeFile.erase(target())
        assertFalse(kept.exists())
    }

    /** A token's copy mustn't linger: signing in again replaces what it held. */
    @Test
    fun aSetAsideCredentialGoesAtTheNextSignIn() {
        target().writeText("{}")
        SafeFile.load(target()) { throw OutOfMemoryError() }
        val kept = assertNotNull(SafeFile.setAside(target()))
        SafeFile.openSaves(target())
        SafeFile.writeCredential(target(), "{}", "sign-in")
        assertFalse(kept.exists())
    }

    @Test
    fun aSetAsideLeavesTheStoreClosedForItsReload() {
        target().writeText("[1]")
        SafeFile.load(target()) { throw OutOfMemoryError() }
        SafeFile.setAside(target())
        assertTrue(SafeFile.savesHeld(target()))
        assertFalse(saveStore(target(), "shot history", "[]"))
    }

    @Test
    fun aPeekChangesNothingWhateverItFinds() {
        target().writeText("[1,2]")
        SafeFile.load(target()) { throw OutOfMemoryError() }
        Stores.record(target(), "shot history", SafeLoad.Unavailable("x"))

        assertEquals(SafeLoad.Loaded(listOf(1, 2)), SafeFile.peek(target(), ::decodeList))
        assertTrue(SafeFile.isBlocked(target()), "only a real load lifts a refusal")
        assertEquals(StoreHealth.UNAVAILABLE, Stores.health(target()))
    }

    @Test
    fun aPeekLeavesADamagedFileForTheLoadToFind() {
        target().writeText("{bad")
        bak().writeText("[1]")
        assertEquals(SafeLoad.Loaded(listOf(1), recoveredFrom = "shots.json.bak"), SafeFile.peek(target(), ::decodeList))
        assertEquals("{bad", target().readText())
        assertEquals(emptyList(), SafeFile.keptAside(target()))
        assertEquals(StoreHealth.LOADING, Stores.health(target()))
    }

    @Test
    fun aPeekReportsWhatItCannotRead() {
        assertEquals(SafeLoad.Absent, SafeFile.peek(target(), ::decodeList))
        target().writeText("{bad")
        assertIs<SafeLoad.Corrupt>(SafeFile.peek(target(), ::decodeList))
        target().writeText("[1]")
        target().setReadable(false)
        assertIs<SafeLoad.Unavailable>(SafeFile.peek(target(), ::decodeList))
    }

    @Test
    fun aLoadDoesNotOpenAStoreBeingReloaded() {
        SafeFile.resetForTest()
        target().writeText("[1]")
        SafeFile.load(target(), decode = ::decodeList)
        SafeFile.closeSaves(target())
        SafeFile.load(target(), decode = ::decodeList)
        assertFalse(SafeFile.savesOpen(target()))
        SaveFloor.resetForTest()
        assertFalse(saveStore(target(), "shot history", "[]"), "refused, and quietly: this doesn't throw")
        assertEquals("[1]", target().readText())
        SafeFile.openSaves(target())
        assertTrue(saveStore(target(), "shot history", "[]"))
    }

    @Test
    fun anOldSessionCannotOpenWhatTheNewOneHasNotLoaded() {
        SafeFile.resetForTest()
        val old = SafeFile.startSession()
        SafeFile.startSession()
        SafeFile.openSaves(target(), old)
        assertFalse(SafeFile.savesOpen(target()))
    }

    @Test
    fun aFileThatCannotBeStatedIsNotAbsent() {
        val dir = tmp.newFolder("locked")
        val t = File(dir, "shots.json").apply { writeText("[1]") }
        dir.setExecutable(false)
        try {
            assertIs<SafeLoad.Unavailable>(SafeFile.load(t, decode = ::decodeList))
            assertTrue(SafeFile.isBlocked(t))
        } finally {
            dir.setExecutable(true)
        }
        assertEquals("[1]", t.readText())
    }

    @Test
    fun aNewSessionHasNoHealthFromTheLastOne() {
        target().writeText("[1]")
        loadStore(target(), "shot history", decode = ::decodeList)
        assertEquals(StoreHealth.OK, Stores.health(target()))
        SafeFile.startSession()
        assertEquals(StoreHealth.LOADING, Stores.health(target()))
    }

    @Test
    fun healthFollowsTheLoad() {
        assertEquals(StoreHealth.LOADING, Stores.health(target()))
        loadStore(target(), "shot history", decode = ::decodeList)
        assertEquals(StoreHealth.ABSENT, Stores.health(target()))
        target().writeText("[1]")
        loadStore(target(), "shot history", decode = ::decodeList)
        assertEquals(StoreHealth.OK, Stores.health(target()))
        loadStore(target(), "shot history") { throw OutOfMemoryError() }
        assertEquals(StoreHealth.UNAVAILABLE, Stores.health(target()))
        target().writeText("{oops")
        loadStore(target(), "shot history", decode = ::decodeList)
        assertEquals(StoreHealth.DAMAGED, Stores.health(target()))
    }

    @Test
    fun aRefusedStoreShowsUntilItLoads() {
        target().writeText("[1]")
        loadStore(target(), "shot history") { throw OutOfMemoryError() }
        assertEquals(listOf(RefusedStore("shot history", target())), Stores.refused.value)
        loadStore(target(), "shot history") { throw OutOfMemoryError() }
        assertEquals(1, Stores.refused.value.size)
        loadStore(target(), "shot history", decode = ::decodeList)
        assertEquals(emptyList(), Stores.refused.value)
    }

    @Test
    fun aCorruptFileThatCantBeMovedShowsToo() {
        val dir = tmp.newFolder("ro2")
        val t = File(dir, "prefs.json").apply { writeText("{bad") }
        dir.setWritable(false)
        try {
            loadStore(t, "settings", decode = ::decodeList)
        } finally {
            dir.setWritable(true)
        }
        assertEquals(listOf("settings"), Stores.refused.value.map { it.what })
    }

    @Test
    fun aRebuildableCacheDoesNotShow() {
        target().writeText("[1]")
        loadStore(target(), null) { throw OutOfMemoryError() }
        assertEquals(emptyList(), Stores.refused.value)
        assertEquals(StoreHealth.LOADING, Stores.health(target()))
    }

    @Test
    fun oneShotsTelemetryIsNoStoresHealth() {
        target().writeText("[1]")
        loadItem(target(), "shot telemetry", false) { throw OutOfMemoryError() }
        assertEquals(emptyList(), Stores.refused.value)
        assertTrue(SafeFile.isBlocked(target()))
    }

    @Test
    fun eraseEndsTheRefusal() {
        target().writeText("[1]")
        loadStore(target(), "shot history") { throw OutOfMemoryError() }
        assertTrue(eraseStore(target()))
        assertEquals(emptyList(), Stores.refused.value)
    }

    @Test
    fun aStoreBackFromItsBackupSaysSo() {
        target().writeText("[1,")
        bak().writeText("[1]")
        assertEquals(SafeLoad.Loaded(listOf(1), recoveredFrom = "shots.json.bak"), loadStore(target(), "shot history", decode = ::decodeList))
        val notices = KeptAsideNotices.drain()
        assertEquals(
            "Your last change to your shot history couldn't be read, so the version before it was restored.",
            KeptAsideNotices.message(notices),
        )
        assertEquals("[1,", notices.single().keptAt?.readText())
    }

    @Test
    fun aCompleteTempFileLosesNothingSoItSaysNothing() {
        target().writeText("[1,")
        tmpFile().writeText("[1,2]")
        assertEquals(SafeLoad.Loaded(listOf(1, 2), recoveredFrom = "shots.json.tmp"), loadStore(target(), "shot history", decode = ::decodeList))
        assertEquals(emptyList(), KeptAsideNotices.drain())
    }

    @Test
    fun aSetAsideSaysSo() {
        assertEquals(
            "Your bean library file was set aside, and you can share it from here.",
            KeptAsideNotices.message(listOf(KeptAside("bean library", File("x"), setAside = true))),
        )
    }

    @Test
    fun theSnackbarCarriesBothKindsOfNotice() {
        val f = File("x")
        assertEquals(
            "Couldn't read your settings; the damaged file was kept aside. " +
                "Your last change to your bean library couldn't be read, so the version before it was restored.",
            KeptAsideNotices.message(listOf(KeptAside("settings", f), KeptAside("bean library", null, olderCopy = true))),
        )
    }
}
