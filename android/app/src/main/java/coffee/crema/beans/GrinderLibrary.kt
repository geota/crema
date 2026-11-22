package coffee.crema.beans

import coffee.crema.core.AutoSaveInput
import coffee.crema.core.BagGrind
import coffee.crema.core.BagSelectInput
import coffee.crema.core.BagSelection
import coffee.crema.core.Bean
import coffee.crema.core.CatalogueGrinderInfo
import coffee.crema.core.GrindSave
import coffee.crema.core.GrindScale
import coffee.crema.core.Grinder
import coffee.crema.core.GrinderAdd
import coffee.crema.core.GrinderArchive
import coffee.crema.core.GrinderLibraryMigration
import coffee.crema.core.GrinderMatch
import coffee.crema.core.GrinderSwitchInput
import coffee.crema.core.LastGrind
import coffee.crema.core.LastGrindRow
import coffee.crema.core.GrinderPickRow
import coffee.crema.core.GrinderPickInput
import coffee.crema.core.MigrationGate
import coffee.crema.core.SelectionChange
import coffee.crema.core.SelectionState
import coffee.crema.core.StagedGrinderPick
import coffee.crema.core.StagedGrindersInput
import coffee.crema.core.StagedGrindersSave
import coffee.crema.core.addGrinderJson
import coffee.crema.core.bagEditorGrindersJson
import coffee.crema.core.bagSelectionUndoJson
import coffee.crema.core.stageGrinderPickJson
import coffee.crema.core.stagedGrindersOnSaveJson
import coffee.crema.core.archiveGrinderJson
import coffee.crema.core.autoSaveShotGrindJson
import coffee.crema.core.bagGrindsToFixJson
import coffee.crema.core.grindersForNamesJson
import coffee.crema.core.matchGrindersJson
import coffee.crema.core.mergeGrinderEditJson
import coffee.crema.core.mergeBeanEditJson
import coffee.crema.core.migrateGrinderLibraryJson
import coffee.crema.core.resolveBagSelectionJson
import coffee.crema.core.resolveGrinderSwitchJson
import coffee.crema.core.restoreGrinderJson
import coffee.crema.core.saveBagGrindJson
import coffee.crema.core.lastGrindsFromRowsJson
import coffee.crema.core.newestShotGrindJson
import coffee.crema.core.grinderPickRowsJson
import coffee.crema.core.newBagGrindRowJson
import coffee.crema.core.addAnotherGrinderJson
import coffee.crema.history.ShotMeta
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * Grinder records and bag grinds (#488, DESIGN.md "Phase 1 spec" and "Bag
 * grinds"). The core owns every rule: adding (and reviving an archived
 * record), archiving, the library migration, the type-or-pick matcher, Brew's
 * Save of a grind, the bag-selection resolve and auto-save. These are the
 * typed calls onto it; each takes its UniFFI function as a parameter so JVM
 * tests can swap it. The few Kotlin helpers below only read what's stored.
 */

private val grinderJson = Json { ignoreUnknownKeys = true }

private val grinders = ListSerializer(Grinder.serializer())

/** The "no grinder" slot's `BagGrind.grinderId` (core `NO_GRINDER_ID`). */
const val NO_GRINDER_ID = ""

/** The default grind scale (no active grinder): today's 0–20 in 0.1 steps, no unit. */
val DEFAULT_GRIND_SCALE = GrindScale(min = 0f, max = 20f, step = 0.1f, unit = "")

/** Owned: not archived, not deleted (core `Grinder::is_live`). */
val Grinder.isLive: Boolean get() = archivedAt == null && deletedAt == null

/** The grinder [activeId] names, when it's live (core `active_grinder`); else none. */
fun List<Grinder>.activeGrinder(activeId: String?): Grinder? =
    activeId?.takeIf { it.isNotEmpty() }?.let { id -> firstOrNull { it.id == id && it.isLive } }

/** The bag's saved grind for [grinderId] (null = the "no grinder" slot), core `saved_grind`. */
fun Bean.savedGrind(grinderId: String?): BagGrind? = grinds?.firstOrNull { it.grinderId == (grinderId ?: NO_GRINDER_ID) }

/** The bag's starred row ("this bag's grinder"), if any. */
val Bean.starredGrind: BagGrind? get() = grinds?.firstOrNull { it.starred }

/**
 * The newest grind per grinder on bag [beanId] among [shots] (newest first or
 * not), the history's `last_grinds_for_bag` grouping through the core
 * (`last_grinds_from_rows`, REVIEW-CORE M1): one per grinder id, and per name
 * for rows from before grinder ids. Shots without a grind don't count.
 */
fun lastGrindsForBag(
    beanId: String,
    shots: List<ShotMeta>,
    core: (String) -> String = ::lastGrindsFromRowsJson,
): List<LastGrind> {
    val rows = shots.filter { it.bean?.beanId == beanId }.map { s ->
        LastGrindRow(completedAt = s.completedAtMs, id = s.id, grinderId = s.grinderId, grinderModel = s.grinderModel, grind = s.grindSetting)
    }
    if (rows.isEmpty()) return emptyList()
    return grinderJson.decodeFromString(lastGrindList, core(grinderJson.encodeToString(ListSerializer(LastGrindRow.serializer()), rows)))
}

private val lastGrindList = ListSerializer(LastGrind.serializer())

/** What the core's history lookups read (`{lastGrinds, grinder | grinders, bag}`). */
private fun lookupJson(lastGrinds: List<LastGrind>, grinder: Grinder? = null, grinderRows: List<Grinder>? = null, bag: Bean? = null): String =
    buildJsonObject {
        put("lastGrinds", grinderJson.encodeToJsonElement(lastGrindList, lastGrinds))
        grinder?.let { put("grinder", grinderJson.encodeToJsonElement(Grinder.serializer(), it)) }
        grinderRows?.let { put("grinders", grinderJson.encodeToJsonElement(grinders, it)) }
        put("bag", bag?.let { grinderJson.encodeToJsonElement(Bean.serializer(), it) } ?: JsonNull)
    }.toString()

/**
 * The newest shot grind on [lastGrinds] for [grinder] (core
 * `newest_shot_grind`, REVIEW-CORE M1): a row stamped with its id, or an
 * older row by its name through the catalogue's aliases ("Niche" finds the
 * Niche Zero). Blank grinds don't count.
 */
fun newestShotGrind(
    lastGrinds: List<LastGrind>,
    grinder: Grinder,
    core: (String) -> String = ::newestShotGrindJson,
): LastGrind? {
    if (lastGrinds.isEmpty()) return null
    val out = core(lookupJson(lastGrinds, grinder = grinder))
    return if (out.trim() == "null") null else grinderJson.decodeFromString(LastGrind.serializer(), out)
}

/**
 * Brew's grinder picker rows for [grinderRows] (the live ones) with [bag]
 * selected (core `grinder_pick_rows`, REVIEW-CORE M1): the starred row, the
 * saved grind, else the newest shot grind; the shell words them.
 */
fun grinderPickRows(
    grinderRows: List<Grinder>,
    bag: Bean?,
    lastGrinds: List<LastGrind>,
    core: (String) -> String = ::grinderPickRowsJson,
): List<GrinderPickRow> =
    grinderJson.decodeFromString(ListSerializer(GrinderPickRow.serializer()), core(lookupJson(lastGrinds, grinderRows = grinderRows, bag = bag)))

/**
 * The bag editor's new row for [grinder] (core `new_bag_grind_row`,
 * REVIEW-CORE M1): seeded from the newest shot on the bag with it, else
 * empty (Save asks for one). Not starred.
 */
fun newBagGrindRow(
    grinder: Grinder,
    lastGrinds: List<LastGrind>,
    core: (String) -> String = ::newBagGrindRowJson,
): BagGrind = grinderJson.decodeFromString(BagGrind.serializer(), core(lookupJson(lastGrinds, grinder = grinder)))

/**
 * Whether the migration's inputs read (REVIEW-CORE H5, PLAN follow-up 20),
 * from this session's loads of the library and the settings files: read, or
 * absent (a first run). One that came out damaged or unavailable, or hasn't
 * loaded, isn't: its rows or its `grinderModel` would look blank, and the
 * migration would file every old grind under "no grinder" for good.
 */
fun migrationGate(
    library: coffee.crema.persist.StoreHealth,
    settings: coffee.crema.persist.StoreHealth,
    settingsKeptAside: Boolean = false,
    grinderModel: String = "",
): MigrationGate {
    fun read(h: coffee.crema.persist.StoreHealth) = h == coffee.crema.persist.StoreHealth.OK || h == coffee.crema.persist.StoreHealth.ABSENT
    // A damaged settings file was kept aside and no settings grinder is known
    // now, even after the settings were written again (REVIEW-514): the core
    // then migrates only bags with their own grinder (PLAN follow-up 40).
    // Never-written settings have nothing kept aside and read normally.
    val quarantined = settingsKeptAside && grinderModel.isBlank()
    return MigrationGate(library = read(library), settings = read(settings), settingsQuarantined = quarantined)
}

/** The library migration (core `migrate_grinder_library`), run once the library and the settings have loaded.
 *  The core runs nothing unless [gate] says both read (REVIEW-CORE H5). */
fun migrateGrinderLibrary(
    grinderRows: List<Grinder>,
    beans: List<Bean>,
    grinderModel: String,
    gate: MigrationGate,
    nowMs: Long,
    core: (payload: String, nowMs: Long) -> String = ::migrateGrinderLibraryJson,
): GrinderLibraryMigration {
    val payload = buildJsonObject {
        put("grinders", grinderJson.encodeToJsonElement(grinders, grinderRows))
        put("beans", grinderJson.encodeToJsonElement(ListSerializer(Bean.serializer()), beans))
        put("grinderModel", JsonPrimitive(grinderModel))
        put("gate", grinderJson.encodeToJsonElement(MigrationGate.serializer(), gate))
    }
    return grinderJson.decodeFromString(GrinderLibraryMigration.serializer(), core(payload.toString(), nowMs))
}

/** Add a grinder from catalogue entry [catalogueId] (blank for none) or typed [name]: a new, revived or existing record. */
fun addGrinder(
    grinderRows: List<Grinder>,
    name: String,
    catalogueId: String?,
    nowMs: Long,
    core: (String, String, String, Long) -> String = ::addGrinderJson,
): GrinderAdd? {
    val out = core(grinderJson.encodeToString(grinders, grinderRows), name, catalogueId.orEmpty(), nowMs)
    return if (out.trim() == "null") null else grinderJson.decodeFromString(GrinderAdd.serializer(), out)
}

/**
 * Add **another** grinder of a model already owned (core
 * `add_another_grinder`, #488 follow-up 18: two Niche Zeros): an archived
 * one of that model comes back, else a new record "‹name› 2" with its own id.
 */
fun addAnotherGrinder(
    grinderRows: List<Grinder>,
    name: String,
    catalogueId: String?,
    nowMs: Long,
    core: (String, String, String, Long) -> String = ::addAnotherGrinderJson,
): GrinderAdd? {
    val out = core(grinderJson.encodeToString(grinders, grinderRows), name, catalogueId.orEmpty(), nowMs)
    return if (out.trim() == "null") null else grinderJson.decodeFromString(GrinderAdd.serializer(), out)
}

/** Remove [grinder] from Devices: archive it, clearing [activeId] when it's this one (review F5). */
fun archiveGrinder(
    grinder: Grinder,
    activeId: String?,
    nowMs: Long,
    core: (String, String, Long) -> String = ::archiveGrinderJson,
): GrinderArchive = grinderJson.decodeFromString(
    GrinderArchive.serializer(),
    core(grinderJson.encodeToString(Grinder.serializer(), grinder), activeId.orEmpty(), nowMs),
)

/** An archived grinder brought back, same id. */
fun restoreGrinder(
    grinder: Grinder,
    nowMs: Long,
    core: (String, Long) -> String = ::restoreGrinderJson,
): Grinder = grinderJson.decodeFromString(Grinder.serializer(), core(grinderJson.encodeToString(Grinder.serializer(), grinder), nowMs))

/** A Devices edit's Save: the fields changed ([edited] against [base]) onto [current], the row as stored. */
fun mergeGrinderEdit(
    base: Grinder,
    edited: Grinder,
    current: Grinder,
    nowMs: Long,
    core: (String, String, String, Long) -> String = ::mergeGrinderEditJson,
): Grinder {
    val enc = { g: Grinder -> grinderJson.encodeToString(Grinder.serializer(), g) }
    return grinderJson.decodeFromString(Grinder.serializer(), core(enc(base), enc(edited), enc(current), nowMs))
}

/** New grinder records for [names] no grinder has yet (imports' grinder names). */
fun grindersForNames(
    grinderRows: List<Grinder>,
    names: List<String>,
    nowMs: Long,
    core: (String, String, Long) -> String = ::grindersForNamesJson,
): List<Grinder> {
    if (names.none { it.isNotBlank() }) return emptyList()
    val namesJson = grinderJson.encodeToString(ListSerializer(String.serializer()), names)
    return grinderJson.decodeFromString(grinders, core(grinderJson.encodeToString(grinders, grinderRows), namesJson, nowMs))
}

/** The type-or-pick box: owned grinders not in [excludeIds], then the catalogue, then archived ones. */
fun matchGrinders(
    query: String,
    grinderRows: List<Grinder>,
    excludeIds: List<String> = emptyList(),
    core: (String, String, String) -> String = ::matchGrindersJson,
): List<GrinderMatch> = runCatching {
    grinderJson.decodeFromString(
        ListSerializer(GrinderMatch.serializer()),
        core(query, grinderJson.encodeToString(grinders, grinderRows), grinderJson.encodeToString(ListSerializer(String.serializer()), excludeIds)),
    )
}.getOrDefault(emptyList())

/** The built-in grinder catalogue (maker, model, aliases, a scale only where verified). */
val grinderCatalogue: List<CatalogueGrinderInfo> by lazy {
    runCatching {
        grinderJson.decodeFromString(ListSerializer(CatalogueGrinderInfo.serializer()), coffee.crema.core.grinderCatalogueJson())
    }.getOrDefault(emptyList())
}

/** Brew's Save of [grind] to [bean]'s row for [grinder] (null = the "no grinder" slot). Never changes the star. */
fun saveBagGrind(
    bean: Bean,
    grinder: Grinder?,
    grind: String,
    nowMs: Long,
    core: (String, String, String, Long) -> String = ::saveBagGrindJson,
): GrindSave = grinderJson.decodeFromString(
    GrindSave.serializer(),
    core(
        grinderJson.encodeToString(Bean.serializer(), bean),
        grinder?.let { grinderJson.encodeToString(Grinder.serializer(), it) } ?: JsonNull.toString(),
        grind,
        nowMs,
    ),
)

/** The grinder ids of [bean]'s rows the bag editor's Save refuses (a blank grind). */
fun bagGrindsToFix(
    bean: Bean,
    core: (String) -> String = ::bagGrindsToFixJson,
): List<String> = grinderJson.decodeFromString(ListSerializer(String.serializer()), core(grinderJson.encodeToString(Bean.serializer(), bean)))

/** Selecting a bag, resolved into one change and its one Undo (review F6). */
fun resolveBagSelection(
    input: BagSelectInput,
    core: (String) -> String = ::resolveBagSelectionJson,
): BagSelection = grinderJson.decodeFromString(BagSelection.serializer(), core(grinderJson.encodeToString(BagSelectInput.serializer(), input)))

/**
 * The Undo of [selection] (core `bag_selection_undo`, REVIEW-CORE H4): only
 * the parts Brew still holds as the selection set them ([now]), so a dial,
 * grinder or profile change made since stands; with a shot in progress the
 * profile part is left out.
 */
fun bagSelectionUndo(
    selection: BagSelection,
    now: SelectionState,
    core: (String) -> String = ::bagSelectionUndoJson,
): SelectionChange {
    val payload = buildJsonObject {
        put("selection", grinderJson.encodeToJsonElement(BagSelection.serializer(), selection))
        put("now", grinderJson.encodeToJsonElement(SelectionState.serializer(), now))
    }
    return grinderJson.decodeFromString(SelectionChange.serializer(), core(payload.toString()))
}

/** The grinders as the bag editor sees them: [stored] with its [staged] picks over them (core `bag_editor_grinders`, FINAL S7). */
fun bagEditorGrinders(
    stored: List<Grinder>,
    staged: List<Grinder>,
    core: (String, String) -> String = ::bagEditorGrindersJson,
): List<Grinder> =
    if (staged.isEmpty()) stored
    else grinderJson.decodeFromString(grinders, core(grinderJson.encodeToString(grinders, stored), grinderJson.encodeToString(grinders, staged)))

/**
 * A pick in the bag editor's Add box, staged in its draft (core
 * `stage_grinder_pick`, FINAL S7): nothing is stored and the active grinder
 * doesn't change until Save. Null when the pick names nothing.
 */
fun stageGrinderPick(
    input: GrinderPickInput,
    nowMs: Long,
    core: (String, Long) -> String = ::stageGrinderPickJson,
): StagedGrinderPick? {
    val out = core(grinderJson.encodeToString(GrinderPickInput.serializer(), input), nowMs).trim()
    return if (out == "null") null else grinderJson.decodeFromString(StagedGrinderPick.serializer(), out)
}

/** The bag editor's Save of its staged grinders (core `staged_grinders_on_save`): the records to upsert and the active pointer to set. */
fun stagedGrindersOnSave(
    input: StagedGrindersInput,
    nowMs: Long,
    core: (String, Long) -> String = ::stagedGrindersOnSaveJson,
): StagedGrindersSave =
    grinderJson.decodeFromString(StagedGrindersSave.serializer(), core(grinderJson.encodeToString(StagedGrindersInput.serializer(), input), nowMs))

/** The grind Brew takes on switching grinder, or null to keep the grind as it is. */
fun resolveGrinderSwitch(
    input: GrinderSwitchInput,
    core: (String) -> String = ::resolveGrinderSwitchJson,
): String? {
    val out = core(grinderJson.encodeToString(GrinderSwitchInput.serializer(), input)).trim()
    return if (out == "null") null else grinderJson.decodeFromString(String.serializer(), out)
}

/** Auto-save after a kept shot, on the device that recorded it only (review F8): the bag to store, or null. */
fun autoSaveShotGrind(
    input: AutoSaveInput,
    nowMs: Long,
    core: (String, Long) -> String = ::autoSaveShotGrindJson,
): GrindSave? {
    val out = core(grinderJson.encodeToString(AutoSaveInput.serializer(), input), nowMs).trim()
    return if (out == "null") null else grinderJson.decodeFromString(GrindSave.serializer(), out)
}

/**
 * The bag editor's grind rows ([edited] against [opened], the bag the editor
 * opened on) applied onto [current], row by row by grinder id (the core's
 * `merge_bean_edit`): a row auto-saved meanwhile survives, a removed row
 * goes, the star moves to the editor's. Only `grinds` is taken from it.
 */
fun mergeBagGrinds(
    opened: Bean,
    edited: Bean,
    current: Bean,
    nowMs: Long,
    core: (String, String, String, Long) -> String = ::mergeBeanEditJson,
): List<BagGrind>? {
    val enc = { b: Bean -> grinderJson.encodeToString(Bean.serializer(), b) }
    return grinderJson.decodeFromString(Bean.serializer(), core(enc(opened), enc(edited), enc(current), nowMs)).grinds
}
