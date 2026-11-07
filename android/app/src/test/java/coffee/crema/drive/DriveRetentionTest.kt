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
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** Drive's files, newest first, as `id` to `name`. */
    private val drive = CopyOnWriteArrayList<Pair<String, String>>()
    private val deleted = CopyOnWriteArrayList<String>()
    private val pageTokens = CopyOnWriteArrayList<String>()
    private val restored = CopyOnWriteArrayList<Pair<String, Boolean>>()
    private var listFails = false

    private val now = System.currentTimeMillis()
    private val day = 24L * 60 * 60 * 1000

    private fun modified(index: Int) = Instant.ofEpochMilli(now - index * day).toString()

    @Before fun start() {
        server.createContext("/upload/drive/v3/files") { x ->
            val body = x.requestBody.readBytes().decodeToString()
            val name = Regex(""""name"\s*:\s*"([^"]+)"""").find(body)!!.groupValues[1]
            drive.add(0, "new" to name)
            x.respond(200, """{"id":"new","name":"$name"}""")
        }
        server.createContext("/drive/v3/files") { x ->
            if (x.requestMethod == "DELETE") {
                val id = x.requestURI.path.substringAfterLast('/')
                deleted += id
                drive.removeIf { it.first == id }
                x.sendResponseHeaders(204, -1)
                x.close()
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

    private fun autoName(i: Int, label: String = "tablet", counts: String = "-s100c100") =
        "crema-backup-auto-$label-2026${"%04d".format(1000 - i)}-0800$counts.crema.zip"

    private fun seed(count: Int, label: String = "tablet", counts: String = "-s100c100", from: Int = 0) {
        for (i in from until from + count) drive += "$label$i" to autoName(i, label, counts)
    }

    private fun picker() = DriveSync(
        store, json, scope, "cid",
        notify = { notices += it },
        backupZip = { null },
        backupFileName = { "b" },
        applyRestore = { bytes, wipe -> restored += bytes.decodeToString() to wipe },
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
    ): DriveState = runBlocking {
        store.load()
        store.save(DriveState(tokens = tokens, lastAutoBackupAtMs = lastAuto, autoBackupKeep = keep))
        val sync = DriveSync(
            store, json, scope, "cid",
            notify = { notices += it },
            backupZip = { BackupZip(byteArrayOf(1), built) },
            backupFileName = { "crema-backup-tablet-20261106-0800.crema.zip" },
            applyRestore = { _, _ -> },
            refreshToken = { error("no refresh expected") },
        )
        sync.load()
        sync.autoBackupIfDue(true)
        withTimeout(10_000) { job.children.toList().joinAll() }
        store.load()
    }

    @Test
    fun theNameCarriesTheCounts() {
        dailyBackup(built = BackupCounts(240u, 238u))
        assertEquals("crema-backup-auto-tablet-20261106-0800-s240c238.crema.zip", drive.first().second)
    }

    @Test
    fun anUpdateDeletesNothingTheOldRuleKept() {
        // Today's Drive: the newest 10, in names with no counts, across two devices.
        seed(6, counts = "")
        seed(4, "phone", counts = "", from = 6)
        val state = dailyBackup(lastAuto = now - 2 * day)
        assertEquals(emptyList(), deleted.toList())
        assertEquals(10, state.autoBackupKeep)
    }

    @Test
    fun anExistingInstallStaysAtTen() {
        seed(12)
        val state = dailyBackup(lastAuto = now - 2 * day)
        assertEquals(10, state.autoBackupKeep)
        assertEquals(setOf("tablet9", "tablet10", "tablet11"), deleted.toSet())
    }

    @Test
    fun aNewInstallStartsAtThree() {
        assertEquals(3, dailyBackup(lastAuto = null).autoBackupKeep)
    }

    @Test
    fun aReinstallDeletesNoneOfItsOldCopies() {
        seed(9, counts = "")
        val state = dailyBackup(lastAuto = null)
        assertEquals(10, state.autoBackupKeep)
        assertEquals(emptyList(), deleted.toList())
    }

    @Test
    fun aChosenNumberStays() {
        seed(5)
        val state = dailyBackup(keep = 2)
        assertEquals(2, state.autoBackupKeep)
        assertEquals(4, deleted.size)
    }

    @Test
    fun anotherDevicesAndManualCopiesAreLeftAlone() {
        seed(5, "phone")
        drive += "manual" to "crema-backup-tablet-20260101-0800.crema.zip"
        dailyBackup(keep = 1)
        assertEquals(emptyList(), deleted.toList())
    }

    @Test
    fun theFullestCopyOnALaterPageIsStillSeen() {
        // Newest first: 150 small copies from another device fill the first page.
        seed(5, counts = "-s10c10")
        seed(PAGE + 20, "phone")
        drive += "fullest" to autoName(900, counts = "-s300c300")
        drive += "older" to autoName(901, counts = "-s9c9")
        dailyBackup(keep = 1)
        assertTrue(pageTokens.isNotEmpty(), "only the first page was read")
        assertTrue("older" in deleted, "$deleted")
        assertTrue("fullest" !in deleted, "$deleted")
    }

    @Test
    fun aShrinkSaysSoAndKeepsTheFullCopy() {
        seed(4, counts = "-s240c238")
        dailyBackup(keep = 1, built = BackupCounts(12u, 12u))
        assertTrue(
            "Today's backup has far fewer shots than yesterday's (12, was 240). Older copies are being kept." in notices,
            "$notices",
        )
        assertTrue("tablet0" !in deleted, "$deleted")
    }

    @Test
    fun aListThatFailsDeletesNothing() {
        seed(12)
        listFails = true
        val state = runCatching { dailyBackup(keep = 3) }
        assertEquals(emptyList(), deleted.toList(), "$state")
    }

    @Test
    fun thePickerShowsEveryBackupWhateverMadeIt() {
        drive += "daily" to "crema-backup-auto-Pixel-Tablet-20261103-0800-s240c238.crema.zip"
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
    fun dismissingThePickerClosesIt() {
        seed(1)
        val sync = picker()
        sync.openBackupPicker()
        sync.finish()
        sync.closeBackupPicker()
        assertEquals(null, sync.state.value.backups)
    }

    private companion object {
        const val PAGE = 100
    }
}
