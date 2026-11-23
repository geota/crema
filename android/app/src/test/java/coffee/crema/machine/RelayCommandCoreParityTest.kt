package coffee.crema.machine

import coffee.crema.core.MachineRequest
import coffee.crema.profiles.ProfileBounds
import coffee.crema.ui.CoreBounds
import coffee.crema.ui.HotWaterUi
import coffee.crema.ui.QcBounds
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PLAN 65: the codec and refusal rules moved into the core (`de1_relay_app`).
 * [Oracle] is this shell's implementation as it was before the move, kept
 * here as the reference: for every golden sample, the old test inputs and a
 * few thousand seeded random controls and commands, the core decodes, refuses
 * and encodes byte for byte as it did (the words a remote shows, Kotlin's
 * number parsing and printing, UTF-16 lengths, Kotlin's whitespace).
 *
 * Two inputs differ by design, both only from a peer's escaped JSON: a lone
 * surrogate crosses to the core as U+FFFD, and a refusal that quotes 32 UTF-16
 * units leaves out a surrogate pair cut in half instead of keeping its lone
 * high half.
 */
class RelayCommandCoreParityTest {

    private val random = Random(65)

    /** Characters the rules treat specially, and some that are just text. */
    private val palette: List<String> = listOf(
        "a", "b", "z", "A", "0", "1", "5", "9", ".", ",", ":", "-", "+", "e", "E", "x", "p", "d", "f",
        " ", "\t", "\n", "\r", "\u0000", "\u0001", "\u001C", "\u001F", "\u007F", "\u0085", " ",
        "­", "​", "‎", "‮", "⁦", " ", "　", "﻿", "⅓", "é", "😀",
        "󠀁", "copy:", "new:", "NaN", "Infinity", "0x1p3", "1e3", "IDLE", "true", "false",
    )

    private fun text(max: Int = 40): String = buildString {
        repeat(random.nextInt(max + 1)) { append(palette[random.nextInt(palette.size)]) }
    }

    private fun number(): Double = when (random.nextInt(6)) {
        0 -> Double.fromBits(random.nextLong())
        1 -> 0.0
        2 -> -0.0
        else -> random.nextDouble(-5.0, 120.0)
    }

    private fun float(): Float = when (random.nextInt(4)) {
        0 -> Float.fromBits(random.nextInt())
        1 -> 0f
        else -> random.nextDouble(-10.0, 600.0).toFloat()
    }

    /** The oracle's reason with a cut-in-half surrogate pair's high half
     *  dropped, as the core quotes it. */
    private fun asCoreQuotes(reason: String): String =
        if (reason.isNotEmpty() && reason.last().isHighSurrogate()) reason.dropLast(1) else reason

    private fun assertSameDecode(method: String, args: String) {
        val expected = when (val d = Oracle.decode(method, args)) {
            is MachineCommandCodec.Decoded.Invalid -> MachineCommandCodec.Decoded.Invalid(asCoreQuotes(d.reason))
            is MachineCommandCodec.Decoded.Valid -> d
        }
        assertEquals(expected, MachineCommandCodec.decode(method, args), "decode(${method.escaped()}, ${args.escaped()})")
    }

    @Test
    fun `the golden samples and the old cases decode as they did`() {
        val cases = coffee.crema.ui.RELAYED_VERB_SAMPLES.map { it.method to it.args } + listOf(
            "machineState" to "", "machineState" to "espresso", "machineState" to "X".repeat(40),
            "setQcGrind" to "-1", "setQcGrind" to "20000", "setQcGrind" to "  ", "setQcGrind" to "a\nb",
            "setQcGrind" to "x".repeat(33), "setQcGrind" to "12" + " ".repeat(60_000) + "\u0001",
            "setQcGrind" to "12" + " ".repeat(60_000), "setQcGrind" to "1e3", "setQcGrind" to "\u001C",
            "setActiveGrinder" to "grinder:niche-zero", "setActiveGrinder" to "grinder:niche-zero\n",
            "setActiveGrinder" to "grinder:niche-zero\nNiche\nZero", "setActiveGrinder" to "\nNiche Zero",
            "quickAdjustBrew" to "18.0,36.0", "quickAdjustBrew" to "NaN,36.0,93.0,", "quickAdjustBrew" to "18,36,93,8,1",
            "quickAdjustBrew" to "18,36,93,abc", "quickAdjustBrew" to " 18d,0x1.2p5,93f,", "quickAdjustBrew" to "18,36,inf,",
            "setStopOnWeight" to "yes", "setAutoTare" to "", "setActiveProfile" to "", "setActiveProfile" to "x".repeat(200),
            "setActiveBean" to "a\u0000b", "startShot" to "x", "handoff" to "", "writeMmr" to "0x803828",
            "quickSave" to "x", "quickSave" to "new: ", "quickSave" to "copy:" + "n".repeat(129), "m".repeat(40) to "",
        )
        for ((method, args) in cases) assertSameDecode(method, args)
    }

    @Test
    fun `random controls decode as they did`() {
        val methods = Oracle.METHODS.toList()
        repeat(5_000) {
            val method = if (random.nextInt(5) == 0) text(40) else methods[random.nextInt(methods.size)]
            val args = when (method) {
                "quickAdjustBrew" -> List(random.nextInt(2, 6)) { if (random.nextBoolean()) number().toString() else text(6) }.joinToString(",")
                "machineState" -> if (random.nextBoolean()) MachineRequest.entries.random(random).name else text(40)
                else -> text(if (random.nextInt(10) == 0) 140 else 40)
            }
            assertSameDecode(method, args)
        }
    }

    @Test
    fun `random commands are refused and encoded as they were`() {
        repeat(5_000) {
            val cmd: MachineCommand.Relayable = when (random.nextInt(8)) {
                0 -> MachineCommand.SetActiveProfile(text())
                1 -> MachineCommand.SetActiveBean(if (random.nextBoolean()) text() else null)
                2 -> MachineCommand.SetActiveGrinder(if (random.nextBoolean()) text() else null, if (random.nextBoolean()) text(140) else null)
                3 -> MachineCommand.SetQc(QcField.entries.random(random), float())
                4 -> MachineCommand.SetGrind(text())
                5 -> MachineCommand.AdjustBrew(number(), number(), number(), if (random.nextBoolean()) number() else null)
                6 -> MachineCommand.QuickSave(if (random.nextBoolean()) text(140) else null, random.nextBoolean())
                else -> listOf(MachineCommand.SetStopOnWeight(random.nextBoolean()), MachineCommand.SetAutoTare(random.nextBoolean()), MachineCommand.Wake).random(random)
            }
            assertEquals(Oracle.refusal(cmd), cmd.refusal(), "refusal of $cmd")
            assertEquals(Oracle.encode(cmd), MachineCommandCodec.encode(cmd), "encode $cmd")
        }
        for (field in QcField.entries) {
            for (v in listOf(0f, -0f, Float.NaN, Float.POSITIVE_INFINITY, 1.2f, 148f, 120f, 61f, 501f, 265428.625f, Float.MIN_VALUE)) {
                assertEquals(Oracle.qcRefusal(field, v), field.refusal(v), "$field $v")
            }
        }
    }

    @Test
    fun `the method and state lists are the same`() {
        assertEquals(Oracle.METHODS, MachineCommandCodec.METHODS)
        assertEquals(Oracle.RELAYABLE_STATES, MachineCommandCodec.RELAYABLE_STATES)
    }

    @Test
    fun `a lone surrogate crosses as U+FFFD instead of failing`() {
        assertEquals(MachineCommandCodec.Decoded.Valid(MachineCommand.SetGrind("12�")), MachineCommandCodec.decode("setQcGrind", "12\uD800"))
        assertEquals(MachineCommandCodec.Decoded.Valid(MachineCommand.SetActiveProfile("a�b")), MachineCommandCodec.decode("setActiveProfile", "a\uDC00b"))
        assertEquals(null, MachineCommand.SetActiveBean("\uD800").refusal())
        assertEquals(MachineCommandCodec.Wire("setActiveBean", "�😀"), MachineCommandCodec.encode(MachineCommand.SetActiveBean("\uDC00😀")))
    }

    private fun String.escaped(): String = take(80).map { c ->
        if (c.code < 0x20 || c.code in 0x7F..0xA0 || c.isSurrogate() || Character.getType(c) == Character.FORMAT.toInt()) {
            "\\u%04X".format(c.code)
        } else {
            c.toString()
        }
    }.joinToString("", "\"", if (length > 80) "…\" ($length)" else "\"")

    /** This shell's codec and refusal rules before PLAN 65, verbatim but for names. */
    private object Oracle {
        fun encode(cmd: MachineCommand.Relayable): MachineCommandCodec.Wire? = when (cmd) {
            MachineCommand.StartShot -> MachineCommandCodec.Wire(START_SHOT, "")
            is MachineCommand.StateRequest -> MachineCommandCodec.Wire(MACHINE_STATE, cmd.request.name)
            MachineCommand.CancelMaintenance -> MachineCommandCodec.Wire(CANCEL_MAINTENANCE, "")
            MachineCommand.TareScale -> MachineCommandCodec.Wire(TARE_SCALE, "")
            is MachineCommand.SetActiveProfile -> MachineCommandCodec.Wire(SET_ACTIVE_PROFILE, cmd.id)
            is MachineCommand.SetActiveBean -> MachineCommandCodec.Wire(SET_ACTIVE_BEAN, cmd.id.orEmpty())
            is MachineCommand.SetActiveGrinder ->
                MachineCommandCodec.Wire(SET_ACTIVE_GRINDER, cmd.id?.let { id -> cmd.name?.let { "$id$GRINDER_NAME$it" } ?: id }.orEmpty())
            is MachineCommand.QuickSave -> MachineCommandCodec.Wire(
                QUICK_SAVE,
                when {
                    cmd.copyName == null -> ""
                    cmd.asNew -> "$SAVE_AS_NEW${cmd.copyName}"
                    else -> "$SAVE_COPY${cmd.copyName}"
                },
            )
            is MachineCommand.SetStopOnWeight -> MachineCommandCodec.Wire(SET_STOP_ON_WEIGHT, cmd.on.toString())
            is MachineCommand.SetAutoTare -> MachineCommandCodec.Wire(SET_AUTO_TARE, cmd.on.toString())
            is MachineCommand.SetQc -> null
            is MachineCommand.SetGrind -> MachineCommandCodec.Wire(SET_QC_GRIND, cmd.grind)
            is MachineCommand.AdjustBrew ->
                MachineCommandCodec.Wire(QUICK_ADJUST_BREW, "${cmd.dose},${cmd.yieldOut},${cmd.brewTemp},${cmd.preinf ?: ""}")
            MachineCommand.ResetBrew -> MachineCommandCodec.Wire(RESET_BREW, "")
        }

        fun decode(method: String, args: String): MachineCommandCodec.Decoded {
            val command = parse(method, args) ?: return MachineCommandCodec.Decoded.Invalid(parseRefusal(method, args))
            return refusal(command)?.let { MachineCommandCodec.Decoded.Invalid(it) } ?: MachineCommandCodec.Decoded.Valid(command)
        }

        val METHODS: Set<String> = setOf(
            START_SHOT, MACHINE_STATE, CANCEL_MAINTENANCE, TARE_SCALE, SET_ACTIVE_PROFILE,
            SET_ACTIVE_BEAN, SET_STOP_ON_WEIGHT, SET_AUTO_TARE, QUICK_ADJUST_BREW, RESET_BREW,
            SET_ACTIVE_GRINDER, QUICK_SAVE, SET_QC_GRIND,
        )

        private val stateRequests: Map<String, MachineCommand.Relayable> = buildMap {
            for (cmd in listOf(
                MachineCommand.Stop, MachineCommand.Sleep, MachineCommand.StartSteam, MachineCommand.StartHotWater,
                MachineCommand.StartFlush, MachineCommand.StartSteamRinse, MachineCommand.StartDescale,
                MachineCommand.StartClean,
            )) put(cmd.request.name, cmd)
            put(MachineRequest.ESPRESSO.name, MachineCommand.StartShot)
        }

        val RELAYABLE_STATES: Set<String> get() = stateRequests.keys

        fun refusal(cmd: MachineCommand.Relayable): String? = when (cmd) {
            is MachineCommand.SetActiveProfile -> idRefusal(cmd.id)
            is MachineCommand.SetActiveBean -> cmd.id?.let(::idRefusal)
            is MachineCommand.SetActiveGrinder -> when {
                cmd.id == null -> null
                cmd.name == null -> idRefusal(cmd.id) ?: "a grinder pick needs its name"
                else -> idRefusal(cmd.id) ?: nameRefusal(cmd.name)
            }
            is MachineCommand.SetQc -> qcRefusal(cmd.field, cmd.value)
            is MachineCommand.SetGrind -> {
                val text = cmd.grind.trim()
                val number = coffee.crema.beans.grindNumber(text)
                when {
                    text.isEmpty() -> "a grind is required"
                    text.length > MAX_GRIND_LENGTH || cmd.grind.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() } -> "malformed grind"
                    number != null && !number.within(0.0..MAX_GRIND) -> "grind out of range: $text"
                    else -> null
                }
            }
            is MachineCommand.AdjustBrew -> {
                val q = CoreBounds.qc
                val yieldRange = q.overrideYieldG.min.toDouble()..q.overrideYieldG.max.toDouble()
                val inRange = cmd.dose.within(q.doseG.min.toDouble()..q.doseG.max.toDouble()) &&
                    (cmd.yieldOut == 0.0 || cmd.yieldOut.within(yieldRange)) &&
                    cmd.brewTemp.within(0.0..ProfileBounds.INSTANCE.maxTemperatureC.toDouble()) &&
                    (cmd.preinf == null || cmd.preinf.within(0.0..MAX_PREINF_S))
                if (inRange) null else "brew override out of range"
            }
            is MachineCommand.QuickSave -> when {
                cmd.copyName == null -> if (cmd.asNew) "Save as new needs a name" else null
                cmd.copyName.isBlank() -> "a copy needs a name"
                cmd.copyName.length > MAX_NAME_LENGTH || cmd.copyName.any { it.isISOControl() } -> "malformed name"
                else -> null
            }
            else -> null
        }

        fun qcRefusal(field: QcField, value: Float): String? {
            if (field == QcField.STEAM_TEMP && value == 0f) return null
            val (label, range) = when (field) {
                QcField.STEAM_TIME -> "steam time" to CoreBounds.qc.steamTimeS.let { it.min.toDouble()..it.max.toDouble() }
                QcField.STEAM_FLOW -> "steam flow" to CoreBounds.qc.steamFlowMlS.let { it.min.toDouble()..it.max.toDouble() }
                QcField.STEAM_TEMP -> "steam temperature" to CoreBounds.qc.steamTempC.let { it.min.toDouble()..it.max.toDouble() }
                QcField.HOT_WATER_TEMP -> "hot-water temperature" to QcBounds.WATER_TEMP_MIN_C..QcBounds.WATER_TEMP_MAX_C
                QcField.HOT_WATER_VOLUME -> "hot-water volume" to HotWaterUi.bounds.volumeMl.let { it.min.toDouble()..it.max.toDouble() }
                QcField.FLUSH_TIME -> "flush time" to CoreBounds.qc.flushTimeS.let { it.min.toDouble()..it.max.toDouble() }
                QcField.FLUSH_TEMP -> "flush temperature" to CoreBounds.qc.flushTempC.let { it.min.toDouble()..it.max.toDouble() }
            }
            return if (value.toDouble().within(range)) null else "$label out of range: $value"
        }

        private fun parse(method: String, args: String): MachineCommand.Relayable? = when (method) {
            START_SHOT -> MachineCommand.StartShot.takeIf { args.isEmpty() }
            CANCEL_MAINTENANCE -> MachineCommand.CancelMaintenance.takeIf { args.isEmpty() }
            TARE_SCALE -> MachineCommand.TareScale.takeIf { args.isEmpty() }
            RESET_BREW -> MachineCommand.ResetBrew.takeIf { args.isEmpty() }
            MACHINE_STATE -> stateRequests[args]
            SET_ACTIVE_PROFILE -> MachineCommand.SetActiveProfile(args)
            SET_ACTIVE_BEAN -> MachineCommand.SetActiveBean(args.ifEmpty { null })
            SET_ACTIVE_GRINDER -> {
                val cut = args.indexOf(GRINDER_NAME)
                when {
                    args.isEmpty() -> MachineCommand.SetActiveGrinder(null, null)
                    cut < 0 -> MachineCommand.SetActiveGrinder(args, null)
                    else -> MachineCommand.SetActiveGrinder(args.substring(0, cut), args.substring(cut + 1))
                }
            }
            SET_QC_GRIND -> MachineCommand.SetGrind(args)
            QUICK_SAVE -> when {
                args.isEmpty() -> MachineCommand.QuickSave(copyName = null, asNew = false)
                args.startsWith(SAVE_COPY) -> MachineCommand.QuickSave(args.removePrefix(SAVE_COPY), asNew = false)
                args.startsWith(SAVE_AS_NEW) -> MachineCommand.QuickSave(args.removePrefix(SAVE_AS_NEW), asNew = true)
                else -> null
            }
            SET_STOP_ON_WEIGHT -> flag(args)?.let { MachineCommand.SetStopOnWeight(it) }
            SET_AUTO_TARE -> flag(args)?.let { MachineCommand.SetAutoTare(it) }
            QUICK_ADJUST_BREW -> brew(args)
            else -> null
        }

        private fun parseRefusal(method: String, args: String): String = when {
            method == MACHINE_STATE -> "machine state not relayable: ${args.take(32)}"
            method == SET_STOP_ON_WEIGHT || method == SET_AUTO_TARE -> "$method needs true or false"
            method == QUICK_ADJUST_BREW -> "quickAdjustBrew needs dose,yield,temp[,preinf]"
            method == QUICK_SAVE -> "quickSave takes nothing, copy:<name> or new:<name>"
            method in METHODS -> "$method takes no arguments"
            else -> "unknown relayed control: ${method.take(32)}"
        }

        private fun flag(args: String): Boolean? = when (args) {
            "true" -> true
            "false" -> false
            else -> null
        }

        private fun brew(args: String): MachineCommand.AdjustBrew? {
            val p = args.split(",")
            if (p.size !in 3..4) return null
            val dose = p[0].toDoubleOrNull() ?: return null
            val yieldOut = p[1].toDoubleOrNull() ?: return null
            val brewTemp = p[2].toDoubleOrNull() ?: return null
            val preinfText = p.getOrNull(3)?.takeIf { it.isNotEmpty() }
            val preinf = preinfText?.let { it.toDoubleOrNull() ?: return null }
            return MachineCommand.AdjustBrew(dose, yieldOut, brewTemp, preinf)
        }

        private fun idRefusal(id: String): String? = when {
            id.isEmpty() -> "an id is required"
            id.length > MAX_ID_LENGTH || id.any { it.isISOControl() } -> "malformed id"
            else -> null
        }

        private fun nameRefusal(name: String): String? = when {
            name.isBlank() -> "a name is required"
            name.length > MAX_NAME_LENGTH || name.any { it.isISOControl() } -> "malformed name"
            else -> null
        }

        private fun Double.within(range: ClosedFloatingPointRange<Double>): Boolean = isFinite() && this in range

        private const val START_SHOT = "startShot"
        private const val MACHINE_STATE = "machineState"
        private const val CANCEL_MAINTENANCE = "cancelMaintenance"
        private const val TARE_SCALE = "tareScale"
        private const val SET_ACTIVE_PROFILE = "setActiveProfile"
        private const val SET_ACTIVE_BEAN = "setActiveBean"
        private const val SET_STOP_ON_WEIGHT = "setStopOnWeight"
        private const val SET_AUTO_TARE = "setAutoTare"
        private const val QUICK_ADJUST_BREW = "quickAdjustBrew"
        private const val RESET_BREW = "resetBrew"
        private const val SET_ACTIVE_GRINDER = "setActiveGrinder"
        private const val QUICK_SAVE = "quickSave"
        private const val SET_QC_GRIND = "setQcGrind"
        private const val GRINDER_NAME = '\n'
        private const val SAVE_COPY = "copy:"
        private const val SAVE_AS_NEW = "new:"
        private const val MAX_ID_LENGTH = 128
        private const val MAX_NAME_LENGTH = 128
        private const val MAX_PREINF_S = 300.0
        private const val MAX_GRIND = 10_000.0
        private const val MAX_GRIND_LENGTH = 32
    }
}
