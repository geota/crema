package coffee.crema.ui

import coffee.crema.MainSources
import coffee.crema.core.Command
import coffee.crema.core.CremaBridge
import coffee.crema.core.NotificationSource
import coffee.crema.core.ProfileSyncOutput
import coffee.crema.core.WriteTarget
import coffee.crema.core.brewTempRange
import coffee.crema.core.cremaProfileFromWire
import coffee.crema.core.effectiveProfileFingerprint
import coffee.crema.core.normalizeBrewOverrides
import coffee.crema.core.preinfusionSeconds
import coffee.crema.machine.MachineCommand
import coffee.crema.machine.maxBrewTempC
import coffee.crema.profiles.ProfileBounds
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F6 on Android: the shell uploads only the core's effective profile (the
 * library profile with the Quick-Controls override applied by the core), its
 * fingerprint is the hash of those bytes, the override lifecycle is the core's,
 * and the relay's brew-temp bound is the core's.
 */
class EffectiveProfileTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Two 20 s steps at 92 °C, the first the one pre-infusion step. */
    private val wire = """
        {"id":"","title":"Dialled","notes":"","steps":[
          {"name":"a","pump":"pressure","target":4.0,"temperature_c":92.0,"temp_sensor":"coffee",
           "transition":"fast","duration_seconds":20.0,"exit":null,"volume_limit_ml":0,"limiter":null,"weight":null},
          {"name":"b","pump":"pressure","target":9.0,"temperature_c":92.0,"temp_sensor":"coffee",
           "transition":"fast","duration_seconds":20.0,"exit":null,"volume_limit_ml":0,"limiter":null,"weight":null}],
         "preinfuse_step_count":1,"minimum_pressure":0.0,"maximum_flow":0.0,"max_total_volume_ml":0,
         "target_weight":36.0,"dose":18.0,"author":"","beverage_type":"espresso","tank_temperature":0.0,"version":"2"}
    """.trimIndent()
    private val crema by lazy { cremaProfileFromWire(wire) }

    private fun modelReply(model: Int): ByteArray = ByteArray(20).also {
        it[1] = 0x80.toByte(); it[2] = 0x00; it[3] = 0x0C
        it[4] = model.toByte()
    }

    /** Upload through the shell's one entry point (the desired profile, which
     *  the core's profile sync uploads on a settled, idle connection); the
     *  normal frames' bytes and the fingerprint the core is pushing. */
    private fun upload(qcJson: String?): Pair<List<ByteArray>, String?> {
        val bridge = CremaBridge()
        bridge.onNotification(NotificationSource.DE1_MMR_READ, modelReply(1), 0uL)
        bridge.settleForUploads()
        val out = json.decodeFromString(ProfileSyncOutput.serializer(), bridge.setDesiredProfile(crema, qcJson, 0uL))
        val frames = out.output.commands.filterIsInstance<Command.WriteCharacteristic>()
            .filter { it.content.target == WriteTarget.De1ProfileFrame }
            .map { w -> w.content.data.map { it.toByte() }.toByteArray() }
            .dropLast(1) // the tail
            .filter { (it[0].toInt() and 0xFF) < 32 } // extension frames carry index + 32
        return frames to out.status.desired
    }

    @Test
    fun `f6_3 the upload carries the brew-temp and pre-infusion overrides, and its fingerprint is theirs`() {
        val qc = BrewParams(dose = 18.0, yieldOut = 36.0, brewTemp = 94.0, preinf = 12.0).overridesJson(json)
        val (bare, bareFp) = upload(null)
        val (dialled, fp) = upload(qc)
        // ShotFrame bytes: [3] temperature in half degrees, [4] duration
        // (tenths below 12.75 s, whole seconds | 0x80 above).
        assertEquals(listOf(184, 184), bare.map { it[3].toInt() and 0xFF })
        assertEquals(listOf(188, 188), dialled.map { it[3].toInt() and 0xFF })
        assertEquals(0x80 or 20, bare[0][4].toInt() and 0xFF)
        assertEquals(120, dialled[0][4].toInt() and 0xFF) // 12.0 s
        assertEquals(bare[1][4], dialled[1][4])
        // The fingerprint the skip check compares is the hash of those bytes.
        assertEquals(effectiveProfileFingerprint(crema, qc), fp)
        assertEquals(effectiveProfileFingerprint(crema, null), bareFp)
        assertNotEquals(bareFp, fp)
    }

    @Test
    fun `f6_5 a profile change clears the override`() {
        val dialled = MainUiState(activeProfileId = "a", brewParams = BrewParams(18.0, 36.0, 95.0, 6.0))
        val switched = MachineCommand.SetActiveProfile("b").applyTo(dialled)
        assertNull(switched.brewParams)
    }

    @Test
    fun `f6_5 an override dialled back to the profile is cleared, and one out of range is clamped`() {
        val dial = assertNotNull(brewDialOf(crema, json, ::brewTempRange, ::preinfusionSeconds))
        assertEquals(92.0, dial.anchor)
        assertEquals(20.0, dial.preinfS)
        val atAnchor = BrewParams(18.0, 36.0, dial.anchor, null)
        assertNull(normalizedBrewParams(atAnchor, crema, json, ::normalizeBrewOverrides, ::brewTempRange))
        val atAnchorPreinf = BrewParams(18.0, 36.0, 92.0, 20.0)
        assertNull(normalizedBrewParams(atAnchorPreinf, crema, json, ::normalizeBrewOverrides, ::brewTempRange))
        // Every step at 92 °C: the dial tops out at 100.
        assertEquals(100.0, dial.max)
        val hot = normalizedBrewParams(BrewParams(18.0, 36.0, 104.0, null), crema, json, ::normalizeBrewOverrides, ::brewTempRange)
        assertEquals(BrewParams(18.0, 36.0, 100.0, null), hot)
        assertEquals("+8.0 °C from profile", dial.offsetLabel(100.0))
        assertNull(dial.offsetLabel(92.0))
        // A dose-only override keeps the brew temp on the anchor.
        val dose = normalizedBrewParams(BrewParams(19.0, 36.0, 92.0, null), crema, json, ::normalizeBrewOverrides, ::brewTempRange)
        assertEquals(BrewParams(19.0, 36.0, 92.0, null), dose)
    }

    @Test
    fun `f6_7 the relay's brew-temp bound is the core's`() {
        assertEquals(ProfileBounds.INSTANCE.maxTemperatureC.toDouble(), maxBrewTempC())
        assertEquals(100.0, maxBrewTempC())
        assertNull(MachineCommand.AdjustBrew(18.0, 36.0, 100.0, null).refusal())
        assertNotNull(MachineCommand.AdjustBrew(18.0, 36.0, 100.5, null).refusal())
    }

    @Test
    fun `f6_6 the shell has no profile upload, only the desired profile`() {
        // (Kotlin mangles a name with an unsigned parameter: `setDesiredProfile-<hash>`.)
        val methods = CremaBridge::class.java.methods.map { it.name.substringBefore('-') }
        assertTrue("setDesiredProfile" in methods)
        assertEquals(emptyList(), methods.filter { it.startsWith("upload") && "rofile" in it })
        val callers = MainSources.kotlinFiles().filter { (_, text) -> "bridge.setDesiredProfile(" in text }.map { it.first }
        assertEquals(listOf("coffee/crema/ui/MainViewModel.kt"), callers)
        val handPatched = MainSources.kotlinFiles().filter { (_, text) -> "overrideBrewParamsJson" in text }.map { it.first }
        assertTrue(handPatched.isEmpty(), "no shell code hand-patches upload bytes: $handPatched")
    }
}
