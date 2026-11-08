package coffee.crema.drive

import android.app.Application
import android.content.ContextWrapper
import coffee.crema.core.BackupCounts
import coffee.crema.security.SecretBox
import coffee.crema.security.SecretCipher
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The daily backup's retention against a fake Drive: what it uploads, what it
 * deletes, and above all what an update doesn't delete. Also the picker, as it
 * lists and restores from the same Drive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DriveRetentionTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val dir = Files.createTempDirectory("drive-retention").toFile()
    private val context = object : ContextWrapper(null) {
        override fun getFilesDir() = dir
    }
    private val box = SecretBox(
        object : SecretCipher {
            override fun seal(plain: ByteArray) = ByteArray(12) + plain
            override fun open(sealed: ByteArray) = sealed.copyOfRange(12, sealed.size)
        },
    )
    private val store = DriveStore(context, json, box)
    private val tokens = DriveTokenSet(accessToken = "t", refreshToken = "r", expiresAt = Long.MAX_VALUE)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val notices = CopyOnWriteArrayList<String>()
    private val logged = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** Drive's files, newest first, as `id` to `name`. */
    private val drive = CopyOnWriteArrayList<Pair<String, String>>()
    private val trashed = CopyOnWriteArrayList<String>()
    private val pageTokens = CopyOnWriteArrayList<String>()
    private var garbledList = false
    private var uploadFails = false
    private var emptyBody = false
    private val restored = CopyOnWriteArrayList<Pair<String, Boolean>>()
    private var listFails = false
    private var loopingToken = false
    private var trashFails = false

    private val now = System.currentTimeMillis()
    private val day = 24L * 60 * 60 * 1000

    private fun modified(index: Int) = Instant.ofEpochMilli(now - index * day).toString()

    @Before fun start() {
        server.createContext("/upload/drive/v3/files") { x ->
            val body = x.requestBody.readBytes().decodeToString()
            if (uploadFails) {
                x.respond(500, "{}")
                return@createContext
            }
            val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(body)!!.groupValues[1]
            drive.add(0, "new" to name)
            x.respond(200, """{"id":"new","name":"$name"}""")
        }
        server.createContext("/drive/v3/files") { x ->
            if (x.requestMethod == "PATCH") {
                if (trashFails) {
                    x.respond(500, "{}")
                    return@createContext
                }
                val id = x.requestURI.path.substringAfterLast('/')
                assertEquals("""{"trashed":true}""", x.requestBody.readBytes().decodeToString())
                trashed += id
                drive.removeIf { it.first == id }
                x.respond(200, """{"id":"$id"}""")
                return@createContext
            }
            assertEquals("GET", x.requestMethod, "nothing is deleted for good")
            if (garbledList) {
                x.respond(200, "<html>a proxy's page</html>")
                return@createContext
            }
            if (emptyBody) {
                x.respond(200, "{}")
                return@createContext
            }
            if (x.requestURI.query.orEmpty().contains("alt=media")) {
                x.respond(200, x.requestURI.path.substringAfterLast('/'))
                return@createContext
            }
            if (listFails) {
                x.respond(500, "{}")
                return@createContext
            }
            if (loopingToken) {
                Regex("pageToken=([^&]+)").find(x.requestURI.query.orEmpty())?.let { pageTokens += it.groupValues[1] }
                x.respond(200, """{"files":[],"nextPageToken":"same"}""")
                return@createContext
            }
            val token = Regex("pageToken=(\\d+)").find(x.requestURI.query.orEmpty())?.groupValues?.get(1)
            token?.let { pageTokens += it }
            val start = token?.toInt() ?: 0
            val page = drive.drop(start).take(PAGE)
            val rows = page.withIndex().joinToString(",") { (i, f) ->
                """{"id":"${f.first}","name":"${f.second}","modifiedTime":"${modified(start + i)}"}"""
            }
            val next = (start + PAGE).takeIf { it < drive.size }?.let { ""","nextPageToken":"$it"""" }.orEmpty()
            x.respond(200, """{"files":[$rows]$next}""")
        }
        server.start()
        driveApiRoot = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() {
        server.stop(0)
        driveApiRoot = "https://www.googleapis.com"
        scope.cancel()
    }

    private fun com.sun.net.httpserver.HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    /** The label an automatic name holds, for this install or another. */
    private fun autoName(i: Int, label: String = "tablet~$TAG", counts: String = "-s100c100") =
        "crema-backup-auto-$label-2026${"%04d".format(1000 - i)}-0800$counts.crema.zip"

    private fun seed(count: Int, label: String = "tablet~$TAG", counts: String = "-s100c100", from: Int = 0) {
        val id = label.substringBefore('~')
        for (i in from until from + count) drive += "$id$i" to autoName(i, label, counts)
    }

    private fun picker() = DriveSync(
        store, json, scope, "cid",
        notify = { notices += it },
        backupZip = { null },
        backupFileName = { "b" },
        applyRestore = { bytes, wipe, _ -> restored += bytes.decodeToString() to wipe },
        refreshToken = { error("no refresh expected") },
    ).also {
        runBlocking {
            store.load()
            store.save(DriveState(tokens = tokens))
            it.load()
        }
    }

    private fun DriveSync.finish() = runBlocking { withTimeout(10_000) { job.children.toList().joinAll() } }

    /** One daily run, on a device whose last automatic backup was [lastAuto]. */
    private fun dailyBackup(
        lastAuto: Long? = now - 2 * day,
        keep: Int? = null,
        built: BackupCounts = BackupCounts(100u, 100u),
        tag: String? = TAG,
        blocked: String? = null,
        newestDamage: Long? = null,
        damageSeen: Long? = null,
        damage: Map<String, Long> = newestDamage?.let { mapOf(LIB to it) } ?: emptyMap(),
        seenByStore: Map<String, Long> = damageSeen?.let { mapOf(LIB to it) } ?: emptyMap(),
        preDamage: String? = null,
        pinReleased: Boolean = false,
        lastSuccess: Long? = null,
        seen: (DriveSync) -> Unit = {},
    ): DriveState = runBlocking {
        store.load()
        store.save(
            DriveState(
                tokens = tokens, lastAutoBackupAtMs = lastAuto, lastBackupSuccessAtMs = lastSuccess,
                autoBackupKeep = keep, autoBackupTag = tag, damageSeen = seenByStore, preDamageCopy = preDamage, pinReleased = pinReleased,
            ),
        )
        val sync = DriveSync(
            store, json, scope, "cid",
            notify = { notices += it },
            log = { logged += it },
            backupZip = { BackupZip(byteArrayOf(1), built) },
            backupFileName = { "crema-backup-tablet-20261106-0800.crema.zip" },
            applyRestore = { _, _, _ -> },
            refreshToken = { error("no refresh expected") },
            autoBackupBlocked = { blocked },
            damageByStore = { damage },
        )
        sync.load()
        sync.autoBackupIfDue(true)
        withTimeout(10_000) { job.children.toList().joinAll() }
        seen(sync)
        store.load()
    }

    @Test
    fun theNameCarriesTheCountsAndTheTag() {
        dailyBackup(built = BackupCounts(240u, 238u))
        assertEquals("crema-backup-auto-tablet~$TAG-20261106-0800-s240c238.crema.zip", drive.first().second)
    }

    @Test
    fun theFirstBackupMakesATagAndKeepsIt() {
        val first = dailyBackup(tag = null)
        val tag = assertNotNull(first.autoBackupTag)
        assertTrue(drive.first().second.startsWith("crema-backup-auto-tablet~$tag-"), drive.first().second)
        assertTrue(tag != newAutoBackupTag(), "tags differ")
    }

    @Test
    fun twoDevicesOfOneModelLeaveEachOtherAlone() {
        // Newest first, this install's two, then the other tablet's twelve.
        seed(2)
        seed(12, "tablet~other1", from = 2)
        dailyBackup(keep = 1)
        assertEquals(setOf("tablet0", "tablet1"), trashed.toSet())
    }

    @Test
    fun anUpdateDeletesNothing() {
        // Today's Drive: names with no tag and no counts, from before this.
        seed(12, "tablet", counts = "")
        seed(4, "phone", counts = "", from = 12)
        val state = dailyBackup(lastAuto = now - 2 * day, tag = null)
        assertEquals(emptyList(), trashed.toList())
        assertEquals(10, state.autoBackupKeep)
    }

    @Test
    fun aNewInstallKeepsThree() {
        seed(12)
        val state = dailyBackup(lastAuto = null)
        assertEquals(3, state.autoBackupKeep)
        assertEquals((2..11).map { "tablet$it" }.toSet(), trashed.toSet())
    }

    @Test
    fun anInstallThatBackedUpBeforeKeepsTen() {
        seed(12)
        assertEquals(10, dailyBackup(lastAuto = now - 2 * day).autoBackupKeep)
        assertEquals(setOf("tablet9", "tablet10", "tablet11"), trashed.toSet())
        // A manual backup counts too.
        assertEquals(10, dailyBackup(lastAuto = null, lastSuccess = now - day).autoBackupKeep)
    }

    @Test
    fun aDamagedStoreOrARestoreSkipsTheDailyBackupUntilTheNextLaunch() {
        seed(3)
        var paused: String? = null
        val state = dailyBackup(
            lastAuto = now - 2 * day,
            blocked = "your bean library couldn't be read",
            seen = { paused = it.state.value.autoBackupPaused },
        )
        assertTrue(drive.none { it.first == "new" }, "nothing uploaded")
        assertEquals(emptyList(), trashed.toList())
        assertEquals(now - 2 * day, state.lastAutoBackupAtMs, "not stamped: the next launch tries again")
        assertEquals("Daily backup paused: your bean library couldn't be read.", paused)
        // Once it's back, the next launch backs up.
        dailyBackup(lastAuto = now - 2 * day, seen = { paused = it.state.value.autoBackupPaused })
        assertTrue(drive.any { it.first == "new" })
        assertEquals(null, paused)
    }

    @Test
    fun aNewDamagePinsTheNewestCopyFromBeforeIt() {
        // Same counts every day: a bean library that loaded empty can't be seen.
        seed(8)
        val state = dailyBackup(keep = 1, newestDamage = now - day / 2)
        assertEquals("tablet0", state.preDamageCopy)
        assertEquals(mapOf(LIB to now - day / 2), state.damageSeen)
        assertEquals((1..7).map { "tablet$it" }.toSet(), trashed.toSet())
    }

    @Test
    fun thePinnedCopyStaysThroughTheThinDaysAfter() {
        seed(8)
        val state = dailyBackup(keep = 1, newestDamage = now - 9 * day, damageSeen = now - 9 * day, preDamage = "tablet6")
        assertEquals("tablet6", state.preDamageCopy, "an old damage isn't seen again")
        assertTrue("tablet6" !in trashed, "$trashed")
        assertTrue("tablet5" in trashed && "tablet7" in trashed, "$trashed")
    }

    @Test
    fun aDamageLongSinceFixedDoesNotHideANewOne() {
        // Review Q2: a `.corrupt-` copy from half a year ago is still on disk,
        // and the bean library is damaged again today.
        seed(8)
        val state = dailyBackup(keep = 1, newestDamage = now - day / 2, damageSeen = now - 180 * day)
        assertEquals("tablet0", state.preDamageCopy)
        assertTrue("tablet0" !in trashed, "$trashed")
        // With nothing new, nothing is pinned: the old damage was dealt with.
        val calm = dailyBackup(keep = 1, newestDamage = now - 180 * day, damageSeen = now - 180 * day)
        assertEquals(null, calm.preDamageCopy)
    }

    @Test
    fun aDeviceClockAheadOfDrivesStillKeepsTheGoodCopy() {
        // Review Q3: the damage is stamped 3 h ahead of Drive's time, so every
        // copy, today's thin one too, looks older than it. The pin is by id.
        seed(8)
        val state = dailyBackup(keep = 1, newestDamage = now + 3 * 60 * 60 * 1000)
        assertEquals("tablet0", state.preDamageCopy)
        assertTrue("tablet0" !in trashed, "$trashed")
    }

    @Test
    fun aNewDamageThatCantBeListedWaitsForTheNextLaunch() {
        seed(3)
        listFails = true
        val state = dailyBackup(lastAuto = now - 2 * day, newestDamage = now - day / 2)
        assertTrue(drive.none { it.first == "new" }, "nothing uploaded")
        assertEquals(now - 2 * day, state.lastAutoBackupAtMs, "not stamped")
        assertEquals(emptyMap(), state.damageSeen)
    }

    @Test
    fun aListingThatKeepsFailingSaysSoInSettings() {
        seed(3)
        listFails = true
        var paused: String? = null
        dailyBackup(newestDamage = now - day / 2, seen = { paused = it.state.value.autoBackupPaused })
        assertEquals("Daily backup paused: couldn't check Google Drive.", paused)
    }

    @Test
    fun aSecondDamageSoonAfterKeepsThePinOnTheGoodCopy() {
        // Review fix check: damage A pinned tablet3; the copies since are thin.
        // Damage B, in another store, mustn't move the slot onto one of them.
        seed(8)
        val state = dailyBackup(
            keep = 1,
            preDamage = "tablet3",
            seenByStore = mapOf(LIB to now - 4 * day),
            damage = mapOf(LIB to now - 4 * day, PROFILES to now - day / 2),
        )
        assertEquals("tablet3", state.preDamageCopy)
        assertTrue("tablet3" !in trashed, "$trashed")
        assertEquals(now - day / 2, state.damageSeen[PROFILES])
    }

    @Test
    fun aRestoreAfterThePinLetsANewDamagePinYesterdaysCopy() {
        // REVIEW-366: the user restored from the pin, and the copies are good
        // again; a damage on day 10 must keep the newest of those, not the pin.
        seed(8)
        val state = dailyBackup(
            keep = 1,
            preDamage = "tablet3",
            pinReleased = true,
            seenByStore = mapOf(LIB to now - 4 * day),
            damage = mapOf(LIB to now - 4 * day, PROFILES to now - day / 2),
        )
        assertEquals("tablet0", state.preDamageCopy)
        assertEquals(false, state.pinReleased, "the new pin is held again")
    }

    /** A restore finished, having written [written], from Drive file [from]: is the pin released? */
    private fun released(written: Set<String>, from: DriveBackupRow? = null): Boolean = runBlocking {
        store.load()
        store.save(
            DriveState(
                tokens = tokens, preDamageCopy = "tablet3", pinStores = setOf(LIB, PROFILES),
                preDamageCopyAtMs = 5_000L, autoBackupTag = TAG,
            ),
        )
        val sync = DriveSync(
            store, json, scope, "cid",
            notify = {}, backupZip = { null }, backupFileName = { "b" }, applyRestore = { _, _, _ -> },
            refreshToken = { error("no refresh expected") },
        )
        sync.load()
        sync.restoreCompleted(written, from)
        withTimeout(10_000) { job.children.toList().joinAll() }
        store.load().pinReleased
    }

    private fun row(id: String, name: String, at: Long) =
        DriveBackupRow(id, at, coffee.crema.core.BackupFileInfo(device = null, automatic = true, counts = null), name)

    @Test
    fun aRestoreThatWroteEveryDamagedStoreReleasesThePin() {
        assertEquals(true, released(setOf(LIB, PROFILES, "prefs.json")))
    }

    @Test
    fun aRestoreThatMissedADamagedStoreKeepsThePin() {
        // REVIEW-370: a settings-only file, or a Merge that added no profile.
        assertEquals(false, released(setOf("prefs.json")))
        assertEquals(false, released(setOf(LIB)))
    }

    @Test
    fun theRestoredPinOrANewerOwnCopyReleasesItWhateverItWrote() {
        assertEquals(true, released(emptySet(), row("tablet3", "x", 5_000L)))
        assertEquals(true, released(emptySet(), row("tablet9", "crema-backup-auto-tablet~$TAG-20261107-0800-s1c1.crema.zip", 6_000L)))
        // Older than the pin, or another device's: no.
        assertEquals(false, released(emptySet(), row("tablet8", "crema-backup-auto-tablet~$TAG-20261101-0800-s1c1.crema.zip", 4_000L)))
        assertEquals(false, released(emptySet(), row("phone1", "crema-backup-auto-phone~other1-20261107-0800-s1c1.crema.zip", 6_000L)))
    }

    @Test
    fun aPinOlderThanThirtyDaysGivesWayToANewDamage() {
        seed(40)
        val state = dailyBackup(
            keep = 1,
            preDamage = "tablet35",
            seenByStore = mapOf(LIB to now - 36 * day),
            damage = mapOf(LIB to now - day / 2),
        )
        assertEquals("tablet0", state.preDamageCopy)
    }

    @Test
    fun aDamageStampedEarlierByAClockSetBackStillCounts() {
        // Review low item: the clock went back, so the profiles' quarantine has
        // an earlier time than the library's from before. Per store, it's new.
        seed(8)
        val state = dailyBackup(
            keep = 1,
            seenByStore = mapOf(LIB to now),
            damage = mapOf(LIB to now, PROFILES to now - 5 * day),
        )
        assertEquals("tablet0", state.preDamageCopy)
    }

    @Test
    fun withNoDamageThatCopyGoesLikeAnyOther() {
        seed(8)
        dailyBackup(keep = 1)
        assertEquals((0..7).map { "tablet$it" }.toSet(), trashed.toSet())
    }

    @Test
    fun aFailedFirstUploadSetsNothing() {
        uploadFails = true
        val state = dailyBackup(lastAuto = null)
        assertEquals(null, state.autoBackupKeep)
    }

    @Test
    fun aChosenNumberStays() {
        seed(5)
        val state = dailyBackup(keep = 2)
        assertEquals(2, state.autoBackupKeep)
        assertEquals(4, trashed.size)
    }

    @Test
    fun anotherDevicesAndManualCopiesAreLeftAlone() {
        seed(5, "phone~$TAG")
        drive += "manual" to "crema-backup-tablet-20260101-0800.crema.zip"
        dailyBackup(keep = 1)
        assertEquals(emptyList(), trashed.toList())
    }

    @Test
    fun aReplyThatWontParseTrashesNothing() {
        seed(12)
        garbledList = true
        dailyBackup(keep = 3)
        assertEquals(emptyList(), trashed.toList())
    }

    @Test
    fun aReplyWithNoFilesIsNotAnEmptyDrive() {
        seed(12)
        emptyBody = true
        val state = dailyBackup()
        assertEquals(null, state.autoBackupKeep, "a keep chosen from no listing")
    }

    @Test
    fun theFullestCopyOnALaterPageIsStillSeen() {
        // Newest first: 150 small copies from another device fill the first page.
        seed(5, counts = "-s10c10")
        seed(PAGE + 20, "phone~$TAG")
        drive += "fullest" to autoName(900, counts = "-s300c300")
        drive += "older" to autoName(901, counts = "-s9c9")
        dailyBackup(keep = 1)
        assertTrue(pageTokens.isNotEmpty(), "only the first page was read")
        assertTrue("older" in trashed, "$trashed")
        assertTrue("fullest" !in trashed, "$trashed")
    }

    @Test
    fun aShrinkSaysSoAndKeepsTheFullCopy() {
        seed(4, counts = "-s240c238")
        dailyBackup(keep = 1, built = BackupCounts(12u, 12u))
        assertTrue(
            "Today's backup has far fewer shots than your last backup (12, was 240). Older copies are being kept." in notices,
            "$notices",
        )
        assertTrue("tablet0" !in trashed, "$trashed")
    }

    @Test
    fun aListThatFailsDeletesNothing() {
        seed(12)
        listFails = true
        dailyBackup(keep = 3)
        assertEquals(emptyList(), trashed.toList())
        assertTrue(logged.any { it.startsWith("Drive backup prune failed") }, "a stalled prune shows")
    }

    @Test
    fun aTrashThatFailsIsLogged() {
        seed(5)
        trashFails = true
        dailyBackup(keep = 1)
        assertTrue(logged.any { it.startsWith("Drive backup prune failed") }, "$logged")
    }

    @Test
    fun aPruneThatWorksLogsNothing() {
        seed(5)
        dailyBackup(keep = 1)
        assertEquals(emptyList(), logged.toList())
    }

    @Test
    fun thePickerShowsEveryBackupWhateverMadeIt() {
        drive += "daily" to "crema-backup-auto-Pixel-Tablet~abc123-20261103-0800-s240c238.crema.zip"
        drive += "old" to "crema-backup-auto-Pixel-20261102-0800.crema.zip"
        drive += "manual" to "crema-backup-Pixel-20261101-0800.crema.zip"
        drive += "web" to "crema-backup-web-20261031T0800.crema.zip"
        val sync = picker()
        sync.openBackupPicker()
        sync.finish()

        val rows = assertNotNull(sync.state.value.backups)
        assertEquals(
            listOf(
                "Pixel-Tablet · 240 shots, 238 with curves · Daily",
                "Pixel · Daily",
                "Pixel · Manual",
                "web · Manual",
            ),
            rows.map { it.subtitle() },
        )
        assertEquals(Instant.parse(modified(0)).toEpochMilli(), rows.first().modifiedMs)
    }

    @Test
    fun thePickerReadsEveryPage() {
        seed(PAGE + 20)
        val sync = picker()
        sync.openBackupPicker()
        sync.finish()
        assertEquals(PAGE + 20, sync.state.value.backups?.size)
    }

    @Test
    fun aBackupOlderThanTheNewestCanBeRestored() {
        seed(3)
        val sync = picker()
        sync.openBackupPicker()
        sync.finish()
        sync.restoreBackup("tablet2", wipe = false)
        sync.finish()
        assertEquals(listOf("tablet2" to false), restored.toList())
        assertEquals(null, sync.state.value.backups, "the picker closes")
    }

    @Test
    fun replaceReachesTheRestoreAsAWipe() {
        seed(1)
        val sync = picker()
        sync.restoreBackup("tablet0", wipe = true)
        sync.finish()
        assertEquals(listOf("tablet0" to true), restored.toList())
    }

    @Test
    fun anEmptyDriveSaysSoAndOpensNothing() {
        val sync = picker()
        sync.openBackupPicker()
        sync.finish()
        assertEquals(null, sync.state.value.backups)
        assertTrue("No Crema backups found in Google Drive" in notices, "$notices")
    }

    @Test
    fun aListThatFailsSaysSoAndOpensNothing() {
        listFails = true
        val sync = picker()
        sync.openBackupPicker()
        sync.finish()
        assertEquals(null, sync.state.value.backups)
        assertTrue(notices.any { it.startsWith("Couldn't list your Google Drive backups") }, "$notices")
    }

    @Test
    fun aListingThatGoesRoundFailsRatherThanSpinning() {
        loopingToken = true
        val sync = picker()
        sync.openBackupPicker()
        sync.finish()
        assertEquals(null, sync.state.value.backups)
        assertTrue(notices.any { it.startsWith("Couldn't list your Google Drive backups") }, "$notices")
    }

    @Test
    fun aRepeatedPageTokenStopsAtOnce() {
        loopingToken = true
        val e = runCatching { runBlocking { driveListBackups("t", json) } }.exceptionOrNull()
        assertTrue(e?.message?.contains("went round") == true, "$e")
        assertEquals(1, pageTokens.size, "one page after the first, not $DRIVE_LIST_MAX_PAGES")
    }

    @Test
    fun disconnectingClosesThePicker() {
        seed(1)
        val sync = picker()
        sync.openBackupPicker()
        sync.finish()
        assertNotNull(sync.state.value.backups)
        sync.disconnect(quiet = true)
        sync.finish()
        assertEquals(null, sync.state.value.backups)
    }

    @Test
    fun dismissingThePickerClosesIt() {
        seed(1)
        val sync = picker()
        sync.openBackupPicker()
        sync.finish()
        sync.closeBackupPicker()
        assertEquals(null, sync.state.value.backups)
    }

    private companion object {
        const val LIB = "beanLibrary.json"
        const val PROFILES = "customProfiles.json"
        const val PAGE = 100
        const val TAG = "abc123"
    }
}
