package coffee.crema.ui

import coffee.crema.MainSources
import coffee.crema.settings.SettingGroup
import coffee.crema.settings.Setting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-30, the source half: every read of a machine or session (A/B) field from
 * this device's own state — `_ui.value` (or a local alias of it) in
 * MainViewModel, `uiState()` (or an alias) in LibraryController — is one the
 * spec's §7.2 table classifies as an own read. What a remote shows must come
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
            // Persistence.
            "currentPrefs" to ANY,
            // Hub only (the remote's slice replaces its snapshot).
            "configSnapshotJson" to ANY,
            "activeBeanSummaryLine" to setOf("activeBeanId"),
            // Holder writes: on a remote they never reach the machine.
            "applySteamHotWater" to setOf("qcSteamTempC", "qcSteamTimeS", "qcHotWaterTempC", "qcHotWaterVolumeMl"),
            "seedMachineSettings" to setOf("activeProfileId"),
            "reuploadActiveProfile" to setOf("activeProfileId"),
            // Hub only (StartShot is relayed).
            "applyStartShot" to setOf("activeProfileId", "brewParams", "requireScale", "stopOnWeight"),
            "startShotOnCoreLane" to setOf("autoTare", "brewParams", "preFlush", "stopOnWeight", "volumeStopWithScale"),
            // Holder logic, already gated by `authoritative`.
            "applyEvent" to setOf("idleSleepMin", "preFlush", "steamPurge", "de1MachineInfo"),
            // Own-state writers (§7.4).
            "loadPrefs" to setOf("idleSleepMin"),
            "applyRestoredSettings" to setOf("idleSleepMin"),
            // The holder's own machine reads.
            "readMachineInfo" to setOf("de1MachineInfo"),
            "firmware" to setOf("de1MachineInfo"),
        ),
        "LibraryController.kt" to mapOf(
            "persistLibrary" to setOf("activeBeanId"),
            "persistLibraryNow" to setOf("activeBeanId"),
            "exportBeansLibrary" to setOf("activeBeanId"),
            "beansLibraryJson" to setOf("activeBeanId"),
            "deleteRoaster" to setOf("activeBeanId"),
            "maybeLoadLinkedProfile" to setOf("activeProfileId"),
            "refreshProfiles" to setOf("activeProfileId"),
            "deleteProfile" to setOf("activeProfileId"),
            "archiveBuiltinProfile" to setOf("activeProfileId"),
            "deleteBean" to setOf("activeBeanId"),
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

    private val roots = mapOf("MainViewModel.kt" to """_ui\.value""", "LibraryController.kt" to """uiState\(\)""")

    @Test
    fun `every own read of a machine or session field is one the table lists`() {
        val files = MainSources.kotlinFiles().toMap()
        val unlisted = roots.flatMap { (file, root) ->
            val text = files.getValue("coffee/crema/ui/$file")
            reads(text, root).filter { (member, field, _) ->
                val allowed = ownReads.getValue(file)[member] ?: return@filter true
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

    /** (member, field, line) for every A/B field read off [root] or an alias of it. */
    private fun reads(text: String, root: String): List<Triple<String, String, Int>> {
        val out = mutableListOf<Triple<String, String, Int>>()
        var member = ""
        val aliases = mutableSetOf<String>()
        val aliasDecl = Regex("""\b(?:val|var)\s+(\w+)\s*=\s*$root(?!\s*\.)""")
        val direct = Regex("""$root\.(\w+)""")
        val letIt = Regex("""$root\.let \{ (?:(\w+) ->)?""")
        text.lines().forEachIndexed { i, line ->
            MEMBER.find(line)?.let { member = it.groupValues[1]; aliases.clear() }
            aliasDecl.find(line)?.let { aliases += it.groupValues[1] }
            direct.findAll(line).forEach { if (it.groupValues[1] in fields) out += Triple(member, it.groupValues[1], i + 1) }
            for (a in aliases) {
                Regex("""\b${Regex.escape(a)}\.(\w+)""").findAll(line).forEach {
                    if (it.groupValues[1] in fields) out += Triple(member, it.groupValues[1], i + 1)
                }
            }
            letIt.find(line)?.let { m ->
                val name = m.groupValues[1].ifEmpty { "it" }
                Regex("""\b$name\.(\w+)""").findAll(line.substring(m.range.last + 1)).forEach {
                    if (it.groupValues[1] in fields) out += Triple(member, it.groupValues[1], i + 1)
                }
            }
        }
        return out
    }

    /** Each class member's source, by name. */
    private fun memberBodies(text: String): Map<String, String> {
        val bodies = mutableMapOf<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in text.lines()) {
            MEMBER.find(line)?.let { current = bodies.getOrPut(it.groupValues[1]) { StringBuilder() } }
            current?.appendLine(line)
        }
        return bodies.mapValues { it.value.toString() }
    }

    private companion object {
        /** A class member at four spaces: a function or a property. */
        val MEMBER = Regex("""^ {4}(?:(?:private|internal|override|public|protected|suspend|inline|@\S+)\s+)*(?:fun|val|var)\s+(?:<[^>]*>\s*)?(?:[A-Za-z_.]+\.)?(\w+)""")

        /** Any field: the member reads them all, by its nature. */
        val ANY: Set<String> = setOf("*")
    }
}
