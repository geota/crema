package coffee.crema.ui

import coffee.crema.MainSources
import coffee.crema.settings.SettingGroup
import coffee.crema.settings.Setting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-30, the source half: every read of a machine or session (A/B) field from
 * this device's own state — `_ui.value` in MainViewModel, the own accessor
 * (`uiState()`, `uiFlow.value`) in every `*Controller` — is one the spec's
 * §7.2 table classifies as an own read. Reads through a local alias, a
 * `let`/`also` block (one line or many), the state an `_ui.update` /
 * `updateUi` block receives, and a helper over `MainUiState` that reads A/B
 * fields (`connectSweepSettings`, `refillPointMm`, …) all count. What a remote shows must come
 * from the effective state instead, and the table's effective rows must read
 * it. An unlisted read fails here, so each new one is a deliberate choice.
 */
class OwnStateReadsTest {

    /** The A/B fields of MainUiState, plus the helpers that read them. */
    private val fields: Set<String> = Setting.entries
        .filter { it.group != SettingGroup.C_PERSONAL }
        .mapNotNull { it.uiField }.toSet() + setOf("lineFreqHz", "activeProfile", "effectiveBrew")

    /** §7.2 own reads: function (or property initializer) → fields. */
    private val ownReads: Map<String, Map<String, Set<String>>> = mapOf(
        "MainViewModel.kt" to mapOf(
            // The projection itself: own in, effective out.
            "ui" to ANY,
            "effectiveState" to ANY,
            // Persistence.
            "currentPrefs" to ANY,
            // Hub only (the remote's slice replaces its snapshot).
            "configSnapshotJson" to ANY,
            "activeBeanSummaryLine" to setOf("activeBeanId"),
            // Holder writes: on a remote they never reach the machine.
            "applySteamHotWater" to setOf("qcSteamTempC", "qcSteamTimeS", "qcHotWaterTempC", "qcHotWaterVolumeMl"),
            "seedMachineSettings" to setOf("activeProfileId", "connectSweepSettings()"),
            "reuploadActiveProfile" to setOf("activeProfileId", "brewParams"),
            // The device that owns the config (a remote shows its edits in its
            // mirror slice): the brew override is stored normalized against
            // its own active profile.
            "showConfig" to setOf("activeProfileId", "brewParams"),
            // Hub only (StartShot is relayed).
            "applyStartShot" to setOf("activeProfileId", "brewParams", "requireScale", "stopOnWeight"),
            "startShotOnCoreLane" to setOf("autoTare", "brewParams", "preFlush", "stopOnWeight", "volumeStopWithScale"),
            // Holder logic, already gated by `authoritative`; and the scale
            // echo, which fills this device's scale fields (the scale's own
            // values: no snapshot carries them).
            "applyEvent" to setOf(
                "idleSleepMin", "preFlush", "steamPurge", "de1MachineInfo",
                "scaleVolume", "scaleStandbyMinutes", "scaleFlowSmoothing", "scaleAutoStop", "scaleAntiMistouch", "scaleActiveMode",
            ),
            // Sleep on quit: holder only (a remote never sends it), own firmware.
            "init" to setOf("sleepLatchesInRefill()"),
            // T6 (§7.5): leaving compares and adopts into this device's own.
            "leaveRemote" to setOf("adoptableChanges()"),
            "adoptHubSettings" to setOf("adopt()"),
            // The machine's identity (its serial register), not a setting.
            "checkStolenSerial" to setOf("de1MachineInfo"),
            // Own-state writers (§7.4).
            "loadPrefs" to setOf("idleSleepMin", "activeProfileId"),
            "applyRestoredSettings" to setOf("idleSleepMin"),
            // Restore defaults keeps this device's own line frequency (F15).
            "resetPreferences" to setOf("lineFreqOverride"),
            // The holder's own machine reads.
            "readMachineInfo" to setOf("de1MachineInfo"),
            "firmware" to setOf("de1MachineInfo"),
        ),
        "LibraryController.kt" to mapOf(
            "persistLibrary" to setOf("activeBeanId"),
            "persistLibraryNow" to setOf("activeBeanId"),
            "deleteRoaster" to setOf("activeBeanId"),
            "maybeLoadLinkedProfile" to setOf("activeProfileId"),
            // The library's own selection: a reseed after a delete or hide is
            // a profile change that clears this device's own override.
            "refreshProfiles" to setOf("activeProfileId", "withActiveProfileId()"),
            "deleteProfile" to setOf("activeProfileId", "withActiveProfileId()"),
            "archiveBuiltinProfile" to setOf("activeProfileId", "withActiveProfileId()"),
            "activateReseeded" to setOf("activeProfileId"),
            "deleteBean" to setOf("activeBeanId"),
            // Own-state writers (§7.4): a restore and a bean import.
            "restoreBackupFromText" to setOf("activeBeanId"),
            "applyBeanImportPlan" to setOf("activeBeanId"),
        ),
        "SharingController.kt" to mapOf(
            // The connected machine's identity (serial) for uploads, not a setting.
            "liveSerialUnreadable" to setOf("de1MachineInfo"),
            "liveMachine" to setOf("de1MachineInfo"),
        ),
    )

    /** §7.2 effective reads: these act on what is on screen. */
    private val effectiveMembers: Map<String, Set<String>> = mapOf(
        "MainViewModel.kt" to setOf(
            "pushStopTargets", "startFromShot", "saveQuickGrindToBean", "shotDiagSummary", "guidedBeanId",
            "openGuidedLogBrew", "openLogBrew", "defaultBrewRecipeFor", "resolveGuidedSetup",
            // Decent buildRecord.grinderModel; Visualizer grinderModel and beanLibrary.
            "decent", "visualizer",
        ),
        "LibraryController.kt" to setOf("saveQuickPreset", "captureCompletedShot"),
    )

    /** Where each file reads its own state: `_ui.value` in the VM, the own
     *  accessor in a controller; and the blocks that receive it (`_ui.update`,
     *  `updateUi`). */
    private fun rootsOf(file: String): Pair<String, String> = when (file) {
        "MainViewModel.kt" -> """_ui\.value""" to """_ui\.update"""
        else -> """uiState\(\)|uiFlow\.value""" to """updateUi"""
    }

    /** Every file with a `*Controller` class, by the name the table uses. */
    private fun controllers(files: Map<String, String>): Map<String, String> =
        files.filter { (path, text) -> path.endsWith("Controller.kt") || Regex("""\bclass \w+Controller\b""").containsMatchIn(text) }
            .mapKeys { it.key.substringAfterLast('/') }

    /** Functions over a [MainUiState] that read A/B fields: a call of one on
     *  the own state reads them (name + "()"). */
    private fun helpers(files: Map<String, String>): Set<String> {
        val decl = Regex("""^(?:internal |private |public )?fun (?:MainUiState\.(\w+)\(|(\w+)\([^)]*:\s*MainUiState\b)""")
        val bodies = mutableMapOf<String, StringBuilder>()
        for ((_, text) in files) {
            var current: StringBuilder? = null
            for (line in text.lines()) {
                val m = decl.find(line)
                if (m != null) current = bodies.getOrPut(m.groupValues[1].ifEmpty { m.groupValues[2] }) { StringBuilder() }
                else if (line.startsWith("fun ") || line.startsWith("private fun ") || line.startsWith("internal fun ") || line.startsWith("class ") || line.startsWith("val ")) current = null
                current?.appendLine(line)
            }
        }
        val reading = bodies.filter { (_, body) -> fields.any { Regex("""\b$it\b""").containsMatchIn(body) } }.keys.toMutableSet()
        // A helper that calls a reading helper reads too.
        do {
            val more = bodies.filter { (name, body) -> name !in reading && reading.any { Regex("""\b$it\(""").containsMatchIn(body) } }.keys
            reading += more
        } while (more.isNotEmpty())
        return reading
    }

    @Test
    fun `every own read of a machine or session field is one the table lists`() {
        val files = MainSources.kotlinFiles().toMap()
        val helpers = helpers(files)
        val scanned = mapOf("MainViewModel.kt" to files.getValue("coffee/crema/ui/MainViewModel.kt")) + controllers(files)
        val unlisted = scanned.flatMap { (file, text) ->
            val (root, block) = rootsOf(file)
            reads(text, root, block, helpers).filter { (member, field, _) ->
                val allowed = ownReads[file]?.get(member) ?: return@filter true
                allowed !== ANY && field !in allowed
            }.map { (member, field, line) -> "$file:$line $member reads own $field" }
        }
        assertEquals(emptyList(), unlisted, "own reads the §7.2 table doesn't list: read effectiveState(), or classify them")
    }

    @Test
    fun `the effective rows read the effective state`() {
        val files = MainSources.kotlinFiles().toMap()
        for ((file, members) in effectiveMembers) {
            val bodies = memberBodies(files.getValue("coffee/crema/ui/$file"))
            for (m in members) {
                val body = bodies[m] ?: error("$file has no member $m")
                assertTrue("effectiveState()" in body, "$file: $m must read effectiveState()")
            }
        }
    }

    /**
     * (member, field, line) for every A/B field read off [root] or an alias of
     * it: a `val x = root`, a `root.let { x -> … }` block (or `it`), and the
     * state a [block] (`_ui.update { s -> … }`) receives. A call of a reading
     * helper on any of them reads `helper()`.
     */
    private fun reads(text: String, root: String, block: String, helpers: Set<String>): List<Triple<String, String, Int>> {
        val out = mutableListOf<Triple<String, String, Int>>()
        var member = ""
        var depth = 0
        val aliases = mutableSetOf<String>() // until the member ends
        val scoped = mutableListOf<Pair<String, Int>>() // until its block closes: (name, depth inside it)
        val aliasDecl = Regex("""\b(?:val|var)\s+(\w+)\s*=\s*(?:$root)(?!\s*[.?])""")
        val opener = Regex("""(?:(?:$root)\s*\??\.\s*(?:let|also)|(?:$block))\s*\{\s*(?:(\w+)\s*->)?""")
        text.lines().forEachIndexed { i, raw ->
            val line = raw.substringBefore("//")
            MEMBER.find(raw)?.let { member = it.groupValues[1].ifEmpty { it.groupValues[2] }; aliases.clear(); scoped.clear() }
            aliasDecl.find(line)?.let { aliases += it.groupValues[1] }
            // Blocks opened on this line, with the depth inside each.
            var d = depth
            var pos = 0
            val opened = mutableListOf<Pair<String, Int>>()
            for (m in opener.findAll(line)) {
                d += line.substring(pos, m.range.last + 1).count { it == '{' } - line.substring(pos, m.range.last + 1).count { it == '}' }
                pos = m.range.last + 1
                opened += m.groupValues[1].ifEmpty { "it" } to d
            }
            val names = aliases + scoped.map { it.first } + opened.map { it.first }
            fun scan(target: String) {
                Regex("""(?:$target)\s*\??\.\s*(\w+)(\()?""").findAll(line).forEach { m ->
                    val name = m.groupValues[1]
                    when {
                        m.groupValues[2].isEmpty() && name in fields -> out += Triple(member, name, i + 1)
                        m.groupValues[2].isNotEmpty() && name in helpers -> out += Triple(member, "$name()", i + 1)
                    }
                }
                Regex("""\b(\w+)\((?:$target)\s*[,)]""").findAll(line).forEach { m ->
                    if (m.groupValues[1] in helpers) out += Triple(member, "${m.groupValues[1]}()", i + 1)
                }
            }
            scan(root)
            for (n in names) scan("""\b${Regex.escape(n)}\b""")
            scoped += opened
            depth += line.count { it == '{' } - line.count { it == '}' }
            scoped.removeAll { it.second > depth }
        }
        return out.distinct()
    }

    /** Each class member's source, by name. */
    private fun memberBodies(text: String): Map<String, String> {
        val bodies = mutableMapOf<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in text.lines()) {
            MEMBER.find(line)?.let { current = bodies.getOrPut(it.groupValues[1].ifEmpty { it.groupValues[2] }) { StringBuilder() } }
            current?.appendLine(line)
        }
        return bodies.mapValues { it.value.toString() }
    }

    private companion object {
        /** A class member at four spaces: a function or a property. */
        val MEMBER = Regex("""^ {4}(?:(?:(?:private|internal|override|public|protected|suspend|inline|@\S+)\s+)*(?:fun|val|var)\s+(?:<[^>]*>\s*)?(?:[A-Za-z_.]+\.)?(\w+)|(init)\s*\{)""")

        /** Any field: the member reads them all, by its nature. */
        val ANY: Set<String> = setOf("*")
    }
}
