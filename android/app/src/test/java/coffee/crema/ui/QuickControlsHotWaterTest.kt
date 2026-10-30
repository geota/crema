package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.HotWaterBasis
import coffee.crema.core.HotWaterContext
import coffee.crema.core.HotWaterDose
import coffee.crema.core.HotWaterMode
import coffee.crema.core.HotWaterTarget
import coffee.crema.core.resolveHotWaterJson
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.Setting
import coffee.crema.ui.RemoteFixture.Companion.savedPrefs
import coffee.crema.ui.RemoteFixture.Companion.writePrefs
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The Hot water card on Android (hot-water modes spec §2.7, AC-HW23; web
 * `WaterStepper.vitest.ts` parity): the readout copy from the core's target,
 * the chip amount and running counter, each mode's stepper from the core's
 * bounds, and the Time · ml · Ratio switch setting the persisted dose and
 * pushing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class QuickControlsHotWaterTest {

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    private fun dose(mode: HotWaterMode = HotWaterMode.Ratio, timeS: Float = 30f, volumeMl: Float = 150f, ratio: Float = 3f) =
        HotWaterDose(mode, timeS, false, volumeMl, ratio)

    /** The core's target for [d] (the shipped firmware). */
    private fun resolve(d: HotWaterDose, targetYieldG: Float? = null, scaleOperational: Boolean = false): HotWaterTarget =
        coreJson.decodeFromString(
            HotWaterTarget.serializer(),
            resolveHotWaterJson(
                coreJson.encodeToString(HotWaterDose.serializer(), d),
                coreJson.encodeToString(HotWaterContext.serializer(), HotWaterContext(targetYieldG, scaleOperational)),
            ),
        )

    private fun readout(t: HotWaterTarget, ratio: Float = 3f) = hotWaterReadout(t, "g", "ml", ratio)

    @Test
    fun `readout copy - ratio, no target, capped and floored`() {
        assertEquals("1:3 → 150 ml for a 50 g shot", readout(resolve(dose(), 50f)))
        assertEquals("No yield target · 150 ml", readout(resolve(dose())))
        assertEquals("1:3 → 250 ml (capped; 1:3 of 100 g is 300 ml)", readout(resolve(dose(), 100f)))
        assertEquals("1:0.5 → 20 ml (raised to the 20 ml minimum)", readout(resolve(dose(ratio = 0.5f), 18f), 0.5f))
        assertEquals("1:2.5 → 90 ml for a 36 g shot", readout(resolve(dose(ratio = 2.5f), 36f), 2.5f))
    }

    @Test
    fun `readout copy - volume by weight, time, a large pour, the fallback firmware and No limit`() {
        val volume = dose(mode = HotWaterMode.Volume, volumeMl = 200f)
        assertEquals("Stops by weight", readout(resolve(volume, 36f, scaleOperational = true)))
        assertEquals("", readout(resolve(volume, 36f, scaleOperational = false)))
        assertEquals("Stops at 30 s", readout(resolve(dose(mode = HotWaterMode.Time))))
        assertEquals("Stops at 200 s — a large pour, also from the group-head button", readout(resolve(dose(mode = HotWaterMode.Time, timeS = 200f))))
        val time = HotWaterTarget(HotWaterMode.Time, timeS = 20f, basis = HotWaterBasis.NotApplicable, wireVolumeMl = 0f, wireTimeoutS = 20f)
        // Volume 0 doesn't stop the DE1's volume stop: a 255 ml backstop.
        assertEquals("Stops at 20 s, or about 255 ml", readout(time.copy(wireVolumeMl = 255f)))
        assertEquals("Pours until you stop it, also from the group-head button", readout(time.copy(timeS = null, wireTimeoutS = 0f)))
        assertEquals("", hotWaterReadoutFor(null, "g", "ml", 3f), "nothing before the core's first target")
    }

    @Test
    fun `the short form under the digits`() {
        assertEquals(HotWaterSub("→ 108 ml", false), hotWaterSub(resolve(dose(), 36f), "ml"))
        assertEquals(HotWaterSub("max 250 ml", true), hotWaterSub(resolve(dose(), 100f), "ml"))
        assertEquals(HotWaterSub("min 20 ml", true), hotWaterSub(resolve(dose(ratio = 0.5f), 18f), "ml"))
        assertEquals(HotWaterSub("150 ml", true), hotWaterSub(resolve(dose()), "ml"), "no yield target")
        val volume = dose(mode = HotWaterMode.Volume, volumeMl = 200f)
        assertEquals(HotWaterSub("by weight", false), hotWaterSub(resolve(volume, 36f, scaleOperational = true), "ml"))
        assertEquals(null, hotWaterSub(resolve(volume, 36f), "ml"))
        assertEquals(null, hotWaterSub(resolve(dose(mode = HotWaterMode.Time)), "ml"))
        assertEquals(HotWaterSub("large pour", true), hotWaterSub(resolve(dose(mode = HotWaterMode.Time, timeS = 200f)), "ml"))
        val time = HotWaterTarget(HotWaterMode.Time, timeS = 20f, basis = HotWaterBasis.NotApplicable, wireVolumeMl = 0f, wireTimeoutS = 20f)
        assertEquals(HotWaterSub("≤ 255 ml", false), hotWaterSub(time.copy(wireVolumeMl = 255f), "ml"))
        assertEquals(HotWaterSub("until stopped", true), hotWaterSub(time.copy(timeS = null, wireTimeoutS = 0f), "ml"))
    }

    /** The label names the mode ("TEMP | RATIO ▾"): its second option is the
     *  current mode's word and opens the menu. */
    @Test
    fun `the label names the mode and is a menu`() {
        for ((mode, word) in listOf(HotWaterMode.Time to "time", HotWaterMode.Volume to "ml", HotWaterMode.Ratio to "ratio")) {
            val opts = hotWaterLabelOptions(mode)
            assertEquals(listOf("temp" to false, "amount" to true), opts.map { it.id to it.menu })
            assertEquals(word, opts[1].label, "$mode")
        }
        assertEquals(listOf("Time", "Volume", "Ratio"), hotWaterModes.map { it.name })
    }

    @Test
    fun `the chip amount and the running counter`() {
        val ratio = resolve(dose(), 50f)
        val timed = resolve(dose(mode = HotWaterMode.Time))
        assertEquals("150 ml", hotWaterRestingAmount(ratio, "ml"))
        assertEquals("30 s", hotWaterRestingAmount(timed, "ml"))
        assertEquals("150ml", hotWaterCompactAmount(ratio))
        assertEquals("30s", hotWaterCompactAmount(timed))
        assertEquals("12.3 s · 150 ml", hotWaterRunning(ratio, 12_340L, "ml"))
        assertEquals("12.3 / 30.0 s", hotWaterRunning(timed, 12_340L, "ml"))
        assertEquals(30f, hotWaterTargetSeconds(timed))
        assertEquals(0f, hotWaterTargetSeconds(ratio))
        assertEquals("5.1 fl oz", hotWaterRestingAmount(ratio, "floz"))
        // The mode card's amount: the target's, in the user's unit.
        assertEquals(Measurement("150", "ml"), hotWaterCardTarget(ratio, "ml"))
    }

    @Test
    fun `each mode's stepper comes from the core's bounds and edits only its own value`() {
        val b = HotWaterUi.bounds
        val edits = mutableListOf<HotWaterDose>()
        val start = dose(volumeMl = 200f, timeS = 40f, ratio = 2.5f)

        val ratio = hotWaterStepper(start) { edits += it }
        assertEquals(listOf(b.ratio.min, b.ratio.max, b.ratio.step).map { it.toDouble() }, listOf(ratio.min, ratio.max, ratio.step))
        assertEquals(b.ratio.chips.map { it.toDouble() }, ratio.chips)
        assertEquals(listOf("1:2", "1:2.5", "1:3", "1:4", "1:5"), ratio.chips.map(ratio.fmt))
        assertEquals(Setting.QC_HOT_WATER_RATIO, ratio.setting)
        ratio.onChange(4.0)
        assertEquals(start.copy(ratio = 4f), edits.last())

        val volume = hotWaterStepper(start.copy(mode = HotWaterMode.Volume)) { edits += it }
        assertEquals(listOf(b.volumeMl.min, b.volumeMl.max, b.volumeMl.step).map { it.toDouble() }, listOf(volume.min, volume.max, volume.step))
        assertEquals(b.volumeMl.chips.map { it.toDouble() }, volume.chips)
        assertEquals("ml", volume.unit)
        volume.onChange(120.0)
        assertEquals(start.copy(mode = HotWaterMode.Volume, volumeMl = 120f), edits.last())

        val time = hotWaterStepper(start.copy(mode = HotWaterMode.Time, timeNoLimit = true)) { edits += it }
        assertEquals(listOf(b.timeS.min, b.timeS.max, b.timeS.step).map { it.toDouble() }, listOf(time.min, time.max, time.step))
        assertEquals(b.timeS.chips.map { it.toDouble() }, time.chips)
        // No limit ships dark: "∞" (no unit, no number chip lit, a sixth chip)
        // only when this firmware honours it.
        assertEquals(if (b.timeNoLimit) "∞" else "40", time.fmt(40.0))
        assertEquals(if (b.timeNoLimit) null else "s", time.unit)
        assertEquals("40", time.chipFmt(40.0), "the chips stay numbers")
        assertEquals(!b.timeNoLimit, time.chipActive(40.0))
        assertEquals(b.timeNoLimit, time.extraChip != null)
        assertEquals(listOf("1:2", "1:2.5", "1:3", "1:4", "1:5"), ratio.chips.map(ratio.chipFmt), "ratio chips read as ratios")
        time.onChange(25.0)
        assertEquals(start.copy(mode = HotWaterMode.Time, timeS = 25f, timeNoLimit = false), edits.last(), "a time edit turns No limit off")
    }

    /** The switch sets the persisted mode and pushes it; each mode keeps its value. */
    @Test
    fun `the switch sets the persisted mode and pushes it`() {
        writePrefs(AppPrefs(idleSleepMin = 30))
        val vm = VmHarness.build(store)
        VmHarness.awaitStartupLoaded(vm)
        val start = VmHarness.ownState(vm).hotWaterDose()
        assertEquals(HotWaterMode.Ratio, start.mode, "a fresh install: ratio")
        fun coreTarget() = coreJson.decodeFromString(HotWaterTarget.serializer(), assertNotNull(VmHarness.bridge(vm).hotWaterTargetJson()))

        vm.setHotWaterDose(start.copy(mode = HotWaterMode.Volume))
        VmHarness.idle()
        assertEquals(HotWaterMode.Volume, vm.ui.value.qcHotWaterMode)
        assertEquals(HotWaterMode.Volume, coreTarget().mode, "pushed to the core")
        assertEquals(HotWaterMode.Volume, vm.ui.value.hotWaterTarget?.mode, "the readout follows the core's target")
        VmHarness.idleUntil("the mode saved") { savedPrefs().qcHotWaterMode == HotWaterMode.Volume }

        vm.setHotWaterDose(vm.ui.value.hotWaterDose().copy(mode = HotWaterMode.Ratio))
        VmHarness.idle()
        assertEquals(start, vm.ui.value.hotWaterDose(), "back to ratio is still 1:${start.ratio}, the volume kept")
        assertEquals(HotWaterMode.Ratio, coreTarget().mode)
        VmHarness.idleUntil("ratio saved") { savedPrefs().qcHotWaterMode == HotWaterMode.Ratio }
    }
}
