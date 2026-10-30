package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.CremaBridge
import coffee.crema.core.ProfileSyncStatus
import coffee.crema.core.builtinCremaProfiles
import coffee.crema.core.effectiveProfileFingerprint
import coffee.crema.settings.AppPrefs
import coffee.crema.ui.RemoteFixture.Companion.writePrefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * F6 validation, Android: the shot start uploads the dialled profile (a start
 * that drops the Quick-Controls override fails here), and deleting or hiding
 * the active profile clears the override like any other profile change.
 * A real [MainViewModel] under Robolectric with the host-built core.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ShotStartOverridesTest {
    private val store = ViewModelStore()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    private fun build(): MainViewModel {
        writePrefs(AppPrefs(stopOnWeight = false))
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        VmHarness.awaitStartupLoaded(vm)
        return vm
    }

    /** The VM's core, found by type rather than by field name. */
    private fun bridgeOf(vm: MainViewModel): CremaBridge {
        val field = MainViewModel::class.java.declaredFields.single { it.type == CremaBridge::class.java }
        field.isAccessible = true
        return field.get(vm) as CremaBridge
    }

    /** A built-in whose dial can rise 2 °C above its own brew temperature. */
    private fun pickProfile(vm: MainViewModel): BrewDial {
        for (p in vm.ui.value.profiles.filter { it.segments.size in 2..20 }) {
            vm.setActiveProfile(p.id)
            VmHarness.idle()
            val dial = vm.brewDial() ?: continue
            if (dial.max >= dial.anchor + 2) return dial
        }
        error("no suitable built-in profile")
    }

    /** The full library JSON of built-in [id], from the core. */
    private fun builtinJson(id: String): String =
        json.parseToJsonElement(builtinCremaProfiles()).jsonArray
            .first { it.jsonObject["id"]?.jsonPrimitive?.content == id }.toString()

    @Test
    fun `the shot start makes the dialled profile the one the DE1 holds`() {
        val vm = build()
        val dial = pickProfile(vm)
        val active = assertNotNull(vm.ui.value.activeProfile())
        vm.quickAdjustBrew(active.dose.toDouble(), active.yieldOut.toDouble(), dial.anchor + 2, null)
        VmHarness.idle()
        assertEquals(dial.anchor + 2, vm.ui.value.brewParams?.brewTemp)
        val bridge = bridgeOf(vm)
        vm.startShot()
        val profileJson = builtinJson(active.id)
        val dialled = effectiveProfileFingerprint(profileJson, vm.ui.value.brewParams.overridesJson(json))
        val bare = effectiveProfileFingerprint(profileJson, null)
        assertNotEquals(bare, dialled)
        // Once the start's core-lane task ran, its profile — override
        // included — is the desired one, and the start waits for the DE1 to
        // hold exactly that before it requests Espresso.
        drainCoreLane(vm)
        val status = json.decodeFromString(ProfileSyncStatus.serializer(), bridge.profileSyncStatus())
        assertEquals(dialled, status.desired)
        val waitsFor = MainViewModel::class.java.declaredFields.single { it.name == "pendingBrewFingerprint" }
            .apply { isAccessible = true }.get(vm)
        assertEquals(dialled, waitsFor, "the start waits for the dialled profile, not the bare one")
    }

    /** Wait for every task already queued on the VM's single-threaded core
     *  lane (it runs FIFO, so a no-op queued now runs after them). */
    private fun drainCoreLane(vm: MainViewModel) {
        val field = MainViewModel::class.java.declaredFields.single { it.name == "coreDispatcher" }
        field.isAccessible = true
        val lane = field.get(vm) as kotlinx.coroutines.CoroutineDispatcher
        repeat(3) { kotlinx.coroutines.runBlocking(lane) {} }
    }

    /** F7 (validation P2): the dial is part of the desired profile. A dial
     *  change while connected becomes the desired profile once the dial rests
     *  0.5 s, so the core pushes it and a group-head start runs it. */
    @Test
    fun `f7_dial a dial change becomes the desired profile after 0_5 s`() {
        val vm = build()
        val dial = pickProfile(vm)
        val active = assertNotNull(vm.ui.value.activeProfile())
        val bridge = bridgeOf(vm)
        drainCoreLane(vm)
        val profileJson = builtinJson(active.id)
        fun desired() = json.decodeFromString(ProfileSyncStatus.serializer(), bridge.profileSyncStatus()).desired
        assertEquals(effectiveProfileFingerprint(profileJson, null), desired(), "the bare profile at first")

        val moved = System.nanoTime()
        vm.quickAdjustBrew(active.dose.toDouble(), active.yieldOut.toDouble(), dial.anchor + 1, null)
        VmHarness.idle()
        vm.quickAdjustBrew(active.dose.toDouble(), active.yieldOut.toDouble(), dial.anchor + 2, null)
        VmHarness.idle()
        drainCoreLane(vm)
        val seenAfterMs = (System.nanoTime() - moved) / 1_000_000
        // The debounce is real time: the check means something only when it
        // ran well inside the 0.5 s (a loaded machine can take longer).
        if (seenAfterMs < 300) {
            assertEquals(effectiveProfileFingerprint(profileJson, null), desired(), "not while the dial moves")
        }

        val dialled = effectiveProfileFingerprint(profileJson, vm.ui.value.brewParams.overridesJson(json))
        Thread.sleep(800) // the 0.5 s debounce, with margin
        drainCoreLane(vm)
        assertEquals(dialled, desired(), "the dialled profile once it rests")
    }

    /** Ack every profile write of [o] on [bridge], as the write executor does. */
    private fun ackAll(bridge: CremaBridge, o: coffee.crema.core.CoreOutput) {
        for (w in o.commands.filterIsInstance<coffee.crema.core.Command.WriteCharacteristic>()) {
            val source = when (w.content.target) {
                coffee.crema.core.WriteTarget.De1ProfileHeader -> coffee.crema.core.NotificationSource.DE1_HEADER_ACK
                coffee.crema.core.WriteTarget.De1ProfileFrame -> coffee.crema.core.NotificationSource.DE1_FRAME_ACK
                else -> continue
            }
            bridge.onNotification(source, w.content.data.map { it.toByte() }.toByteArray(), 0uL)
        }
    }

    private fun out(raw: String) = json.decodeFromString(coffee.crema.core.CoreOutput.serializer(), raw)

    private fun uploads(o: coffee.crema.core.CoreOutput) =
        o.events.count { it is coffee.crema.core.Event.ProfileUploadStarted }

    /** Hot-water review finding on #228: a backup restore re-sends the
     *  connect sweep's settings. Done while the DE1 is down, it must not
     *  settle the link, or the real reconnect skips the profile upload. */
    @Test
    fun `a restore while the DE1 is down does not skip the reconnect upload`() {
        val vm = build()
        val bridge = bridgeOf(vm)
        drainCoreLane(vm)
        val modelReply = ByteArray(20).also { it[1] = 0x80.toByte(); it[3] = 0x0C; it[4] = 1 }
        val idle = byteArrayOf(2, 0)
        // Connected: the model, an idle machine, the on-ready sweep, whose
        // upload of the desired profile lands.
        bridge.onNotification(coffee.crema.core.NotificationSource.DE1_MMR_READ, modelReply, 0uL)
        bridge.onNotification(coffee.crema.core.NotificationSource.DE1_STATE, idle, 0uL)
        val first = out(bridge.connectSweep("{}", 0uL))
        assertEquals(1, uploads(first), "the connect uploads")
        ackAll(bridge, first)
        assertNotNull(json.decodeFromString(ProfileSyncStatus.serializer(), bridge.profileSyncStatus()).lastPushed)

        // The DE1 goes down; a backup is restored meanwhile.
        bridge.de1LinkLost()
        val backup = assertNotNull(kotlinx.coroutines.runBlocking { vm.buildBackupZipBytes() })
        kotlinx.coroutines.runBlocking { vm.restoreBackupBytes(backup, MainViewModel.RestoreMode.MERGE) }
        // The restore applies on its own coroutine: wait for its notice (it
        // re-sends the settings just before).
        VmHarness.idleUntil("the restore") { vm.ui.value.userMessages.any { it.startsWith("Restored ") } }
        drainCoreLane(vm)

        // The real reconnect: its on-ready sweep uploads the profile.
        bridge.onNotification(coffee.crema.core.NotificationSource.DE1_STATE, idle, 0uL)
        val again = out(bridge.connectSweep("{}", 0uL))
        assertEquals(1, uploads(again), "the reconnect uploads")
    }

    @Test
    fun `deleting or hiding the active profile clears the override`() {
        val vm = build()
        val dial = pickProfile(vm)
        val active = assertNotNull(vm.ui.value.activeProfile())
        vm.quickAdjustBrew(active.dose.toDouble(), active.yieldOut.toDouble(), dial.anchor + 2, null)
        VmHarness.idle()
        assertNotNull(vm.ui.value.brewParams)
        vm.archiveBuiltinProfile(active.id)
        VmHarness.idle()
        assertNotEquals(active.id, vm.ui.value.activeProfileId, "another profile is active")
        assertNull(vm.ui.value.brewParams, "the override does not carry over to it")
    }
}
