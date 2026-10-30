package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.NotificationSource
import coffee.crema.core.exportCremaJsonl
import coffee.crema.core.importCremaJsonl
import coffee.crema.history.StoredShot
import coffee.crema.visualizer.storedShotFromBackupJson
import coffee.crema.visualizer.wireShotJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F10 PR 3: Android records shots through the core's assembly
 * (`CremaBridge.takeStoredShot`) — the core's full record, its grinder rule
 * and stop switches — like web, and its shot exports are the core's formats.
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
        // The flat model reads frame 0 as "unknown" (null); the wire writes 0.
        assertEquals(listOf(0, 2, 2, 3), shot.samples.take(4).map { it.frameNumber ?: 0 })
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
        val shot = recorded(vm, before).copy(tags = listOf("dial-in"))
        // The all-shots export: the core's crema/v1 JSONL, read back by the core.
        val envelope = buildJsonObject { put("shots", JsonArray(listOf(wireShotJson(shot, forBackup = true)))) }
        val text = exportCremaJsonl(envelope.toString(), 0L, "test")
        val plan = coreJson.parseToJsonElement(importCremaJsonl(text)).jsonObject
        val back = (plan["shots"] as JsonArray).map {
            storedShotFromBackupJson(it.jsonObject["storedShot"] as JsonObject, coreJson)!!
        }
        assertEquals(1, back.size)
        assertEquals(shot.id, back[0].id)
        assertEquals(shot.samples.size, back[0].samples.size)
        assertEquals(listOf("dial-in"), back[0].tags)
        assertTrue(text.lines().first().contains("crema/v1"))
    }
}
