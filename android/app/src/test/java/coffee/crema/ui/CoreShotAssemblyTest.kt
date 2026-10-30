package coffee.crema.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.NotificationSource
import coffee.crema.core.ShotBean
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.visualizer.shotHistoryJsonl
import coffee.crema.visualizer.shotsFromCremaJsonl
import coffee.crema.visualizer.storedShotFromBackupJson
import coffee.crema.visualizer.wireShotJson
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F10 PR 3: Android records shots through the core's assembly
 * (`CremaBridge.takeStoredShot`) — the core's full record, its grinder rule
 * and stop switches — like web, and its shot exports are the core's formats.
 *
 * The history export is the core's `crema/v1` JSONL, and moves between the
 * shells. The fixtures live in `core/de1-domain/tests/fixtures/shots/`:
 * `android-shots.crema.jsonl` was written by [shotHistoryJsonl] from a shot
 * recorded here ([recordedShot]), and `web-shots.crema.jsonl` by web's
 * `shotHistoryJsonl` (`web/src/lib/history/shot-cross-shell.vitest.ts`), which
 * also imports this one. Regenerate with `CREMA_REGEN_SHOT_FIXTURES=1 ./gradlew
 * :app:testDebugUnitTest --tests coffee.crema.ui.CoreShotAssemblyTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class CoreShotAssemblyTest {

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    /** The core's own test `ShotSample`; byte 17 is the frame number. */
    private fun sampleIn(frame: Int): ByteArray = intArrayOf(
        0x12, 0x34, 0x90, 0x00, 0x28, 0x00, 0x5C, 0x80, 0x00, 0x58, 0x00, 0x5A, 0x00, 0x5D, 0x00,
        0x60, 0x40, frame, 0x96,
    ).map { it.toByte() }.toByteArray()

    /** Run [raw] through the VM's own core-output path (applied on the core lane). */
    private fun apply(vm: MainViewModel, raw: String) {
        MainViewModel::class.java.getDeclaredMethod("onCoreOutputJson", String::class.java)
            .apply { isAccessible = true }
            .invoke(vm, raw)
        VmHarness.idle()
    }

    /** A pour of [count] samples 250 ms apart through frames 0 → 2 → 3, then Idle. */
    private fun pour(vm: MainViewModel, count: Int) {
        val bridge = VmHarness.bridge(vm)
        apply(vm, bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(4, 5), 1_000uL))
        for (i in 1..count) {
            val frame = listOf(0, 2, 2, 3)[minOf(i - 1, 3)]
            apply(vm, bridge.onNotification(NotificationSource.DE1_SHOT_SAMPLE, sampleIn(frame), (1_000 + 250 * i).toULong()))
        }
        apply(vm, bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(2, 0), (1_000 + 250 * (count + 1)).toULong()))
    }

    private fun recorded(vm: MainViewModel, before: Int): StoredShot {
        VmHarness.idleUntil("the shot to be recorded", 5_000) { vm.ui.value.history.size > before }
        return vm.ui.value.history.first()
    }

    @Test
    fun `a shot is recorded through the core assembly`() {
        val vm = VmHarness.build(store)
        VmHarness.awaitPrefsLoaded(vm)
        vm.setGrinderModel("EK43")
        vm.setBaristaName("Sam")
        VmHarness.idle()
        val before = vm.ui.value.history.size
        pour(vm, 48) // 12 s: a shot, not an aborted pull
        val shot = recorded(vm, before)
        assertEquals(listOf(0, 2, 2, 3), shot.samples.take(4).map { it.frameNumber })
        assertEquals("EK43", shot.grinderModel, "the core's rule: no bean → the Settings grinder")
        assertEquals("Sam", shot.barista)
        assertNotNull(shot.stopOnWeight, "the core's stop switches")
        assertNotNull(shot.autoTare)
    }

    @Test
    fun `the stored series is the full record`() {
        val vm = VmHarness.build(store)
        VmHarness.awaitPrefsLoaded(vm)
        val before = vm.ui.value.history.size
        pour(vm, 260) // 65 s of samples: past the old 200-point cut
        val shot = recorded(vm, before)
        assertEquals(260, shot.samples.size, "no 200-point cut on capture")
        // A backup round trip (the restore and capture adapter) keeps it whole.
        val back = storedShotFromBackupJson(wireShotJson(shot, forBackup = true), coreJson)
        assertEquals(260, back?.samples?.size, "no cut on restore")
    }

    @Test
    fun `the grinder model is frozen at capture`() {
        val vm = VmHarness.build(store)
        VmHarness.awaitPrefsLoaded(vm)
        vm.setGrinderModel("EK43")
        VmHarness.idle()
        val before = vm.ui.value.history.size
        pour(vm, 48)
        val shot = recorded(vm, before)
        vm.setGrinderModel("Niche Zero")
        VmHarness.idle()
        assertEquals("EK43", vm.ui.value.history.first().grinderModel)
        // The upload / export uses the frozen one, not today's Settings.
        assertEquals("EK43", (wireShotJson(shot, "Niche Zero")["grinderModel"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `a shot export re-imports`() {
        val vm = VmHarness.build(store)
        VmHarness.awaitPrefsLoaded(vm)
        val before = vm.ui.value.history.size
        pour(vm, 48)
        val recorded = recorded(vm, before)
        // The History screen's export: the core's crema/v1 JSONL, read back by the core.
        val exported = vm.shotsJson(null)!!
        assertTrue(exported.lines().first().contains("crema/v1"))
        val again = shotsFromCremaJsonl(exported, coreJson).single()
        assertEquals(recorded.id, again.id)
        assertEquals(recorded.samples, again.samples)
        // Edits ride along.
        val shot = recorded.copy(tags = listOf("dial-in"))
        val back = shotsFromCremaJsonl(shotHistoryJsonl(listOf(shot), null, 0L, "test"), coreJson)
        assertEquals(1, back.size)
        assertEquals(shot.id, back[0].id)
        assertEquals(shot.samples.size, back[0].samples.size)
        assertEquals(listOf("dial-in"), back[0].tags)
    }

    // ── Frame 0, and the history between the shells ──────────────────────

    private val fixtures = File("../../core/de1-domain/tests/fixtures/shots")

    /** A shot recorded through the core, with the edits a user makes afterwards. */
    private fun recordedShot(vm: MainViewModel): StoredShot {
        vm.setGrinderModel("EK43")
        vm.setBaristaName("Sam")
        VmHarness.idle()
        val before = vm.ui.value.history.size
        pour(vm, 48)
        return recorded(vm, before).copy(
            tags = listOf("dial-in"),
            bean = ShotBean(name = "Gesha", roasterName = "Onyx", roastedOn = "2026-10-01", roastLevel = 3u),
        )
    }

    /** Import [text] the way the History screen does (a picked document). */
    private fun pickAndImport(vm: MainViewModel, name: String, text: String): String {
        val file = File.createTempFile("f10-", "-$name").apply { writeText(text); deleteOnExit() }
        val before = vm.ui.value.userMessages.size
        vm.importShots(Uri.fromFile(file))
        VmHarness.idleUntil("the import of $name") { vm.ui.value.userMessages.size > before }
        return vm.ui.value.userMessages.last()
    }

    @Test
    fun `regenerates the Android shot fixture when asked`() {
        if (System.getenv("CREMA_REGEN_SHOT_FIXTURES") != "1") return
        val vm = VmHarness.build(store)
        VmHarness.awaitPrefsLoaded(vm)
        fixtures.mkdirs()
        File(fixtures, "android-shots.crema.jsonl")
            .writeText(shotHistoryJsonl(listOf(recordedShot(vm)), null, 1_790_000_000_000, "fixture"))
    }

    @Test
    fun `frame 0 is the first frame, not unknown`() {
        val vm = VmHarness.build(store)
        VmHarness.awaitPrefsLoaded(vm)
        val shot = recordedShot(vm)
        val frames = listOf(0, 2, 2, 3)
        assertEquals(frames, shot.samples.take(4).map { it.frameNumber }, "the core's record")
        // The backup / restore adapter.
        val restored = storedShotFromBackupJson(wireShotJson(shot, forBackup = true), coreJson)!!
        assertEquals(frames, restored.samples.take(4).map { it.frameNumber }, "a restore")
        // The history export and its import.
        val back = shotsFromCremaJsonl(shotHistoryJsonl(listOf(shot), null, 0L, "test"), coreJson).single()
        assertEquals(frames, back.samples.take(4).map { it.frameNumber }, "the history export")
        // The per-shot series file.
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        kotlinx.coroutines.runBlocking { HistoryStore(app, coreJson).save(listOf(shot)) }
        val loaded = kotlinx.coroutines.runBlocking { HistoryStore(app, coreJson).load() }.single()
        assertEquals(frames, loaded.samples.take(4).map { it.frameNumber }, "the history store")
        // Unknown stays null, never 0.
        val legacy = shot.copy(samples = shot.samples.map { it.copy(frameNumber = null) })
        assertEquals(null, legacy.samples.first().frameNumber)
    }

    @Test
    fun `a web shot export imports on Android`() {
        val vm = VmHarness.build(store)
        VmHarness.awaitPrefsLoaded(vm)
        val text = File(fixtures, "web-shots.crema.jsonl").readText()
        assertEquals("Imported 1 shot(s)", pickAndImport(vm, "web-shots.crema.jsonl", text))
        val shot = vm.ui.value.history.first()
        assertEquals(48, shot.samples.size)
        assertEquals(listOf(0, 2, 2, 3), shot.samples.take(4).map { it.frameNumber }, "DE1 frames count from 0")
        assertEquals("EK43", shot.grinderModel)
        assertEquals("Sam", shot.barista)
        assertEquals(listOf("dial-in"), shot.tags)
        assertEquals("Gesha", shot.bean?.name)
        assertEquals("Onyx", shot.bean?.roasterName)
        // Re-importing the same file adds nothing.
        assertEquals("Imported 0 shot(s)", pickAndImport(vm, "web-shots.crema.jsonl", text))
    }

    @Test
    fun `the committed Android shot export imports on Android`() {
        val vm = VmHarness.build(store)
        VmHarness.awaitPrefsLoaded(vm)
        val text = File(fixtures, "android-shots.crema.jsonl").readText()
        assertTrue(text.lines().first().contains("crema/v1"))
        assertEquals("Imported 1 shot(s)", pickAndImport(vm, "android-shots.crema.jsonl", text))
        val shot = vm.ui.value.history.first()
        assertEquals(48, shot.samples.size)
        assertEquals(listOf(0, 2, 2, 3), shot.samples.take(4).map { it.frameNumber })
        assertEquals("EK43", shot.grinderModel)
        assertEquals(listOf("dial-in"), shot.tags)
    }
}
