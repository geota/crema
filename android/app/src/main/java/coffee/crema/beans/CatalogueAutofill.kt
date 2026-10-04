package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.BeanOrigin
import coffee.crema.core.CatalogueAutofill
import coffee.crema.core.CatalogueCoffeeBag
import coffee.crema.core.CatalogueField
import coffee.crema.core.CataloguePick
import coffee.crema.core.CataloguePickRoaster
import coffee.crema.core.CatalogueRoaster
import coffee.crema.core.CatalogueRoasterAutofill
import coffee.crema.core.Roaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import coffee.crema.core.catalogueAutofill as coreCatalogueAutofill
import coffee.crema.core.catalogueClashes as coreCatalogueClashes
import coffee.crema.core.cataloguePick as coreCataloguePick
import coffee.crema.core.catalogueRoasterAutofill as coreCatalogueRoasterAutofill
import coffee.crema.core.catalogueRoasterClashes as coreCatalogueRoasterClashes

/*
 * Visualizer catalogue search for the bean editors (web `$lib/bean/catalogue`
 * parity). The HTTP is `VisualizerSync.searchCatalogue`; the response parsing
 * and the pick → bean autofill rule are the core's
 * (`de1_domain::visualizer_catalogue`, via UniFFI). This file holds the shell
 * pieces, kept pure so JVM tests drive them without the native core:
 *
 *  - [CatalogueSearchController] — debounced, last-query-wins search state
 *    (bags on the bean form, roasters on the roaster form).
 *  - [CatalogueFields] / [RoasterFields] — the editor text fields the
 *    catalogue can fill, mapped to a core [Bean] / [Roaster] and back.
 *  - The clash prompt: [CatalogueClashGate] decides "fill now" vs "ask"
 *    ("Keep mine" / "Use catalogue" / dismiss = apply nothing), with
 *    [clashMessage] building the body from [BAG_FORM_FIELD_LABELS] /
 *    [ROASTER_FORM_FIELD_LABELS].
 */

/** Debounce before a typed query hits the network (web `CATALOGUE_DEBOUNCE_MS`). */
const val CATALOGUE_DEBOUNCE_MS = 300L

/** Shortest query worth sending (web `CATALOGUE_MIN_CHARS`). */
const val CATALOGUE_MIN_CHARS = 2

/**
 * Debounced catalogue search. Each [setQuery] cancels the previous pending /
 * in-flight search and restarts the [debounceMs] wait; a query shorter than
 * [minChars] (trimmed) is never sent. Cancelling the previous job is what makes
 * the latest query win — a slow early response can't overwrite newer results.
 */
class CatalogueSearchController<T>(
    private val scope: CoroutineScope,
    private val search: suspend (String) -> List<T>,
    private val debounceMs: Long = CATALOGUE_DEBOUNCE_MS,
    private val minChars: Int = CATALOGUE_MIN_CHARS,
) {
    data class State<T>(
        /** The trimmed query the results / error belong to. */
        val query: String = "",
        val loading: Boolean = false,
        val results: List<T> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State<T>())
    val state: StateFlow<State<T>> = _state.asStateFlow()
    private var job: Job? = null

    fun setQuery(raw: String) {
        val query = raw.trim()
        job?.cancel()
        if (query.length < minChars) {
            _state.value = State(query = query)
            return
        }
        _state.value = _state.value.copy(query = query, loading = true, error = null)
        job = scope.launch {
            delay(debounceMs)
            val results = try {
                search(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, results = emptyList(), error = e.message ?: "Search failed.")
                return@launch
            }
            _state.value = _state.value.copy(loading = false, results = results, error = null)
        }
    }

    /** Drop any pending search and reset to idle (after a pick). */
    fun clear() {
        job?.cancel()
        _state.value = State()
    }
}

/** A result row's secondary line — roaster, then country · process when known. */
fun CatalogueCoffeeBag.subline(): String =
    listOf(roasterName, meta.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")

/** A roaster result row's secondary line — its country, when known. */
fun CatalogueRoaster.subline(): String = country.orEmpty()

/**
 * The bean-editor fields the catalogue can fill, as the editors hold them
 * (plain strings; [roast] null = the user hasn't set a roast level), plus the
 * catalogue links the editor carries through to Save.
 */
data class CatalogueFields(
    val name: String,
    val roaster: String,
    val roast: Int?,
    val country: String,
    val region: String,
    val farmer: String,
    val variety: String,
    val elevation: String,
    val processing: String,
    val harvestTime: String,
    val tastingNotes: String,
    val url: String,
    val canonicalCoffeeBagId: String?,
    val canonicalRoasterId: String?,
) {
    /** The core input: [base] with these field values (blank → null). */
    fun toBean(base: Bean): Bean = base.copy(
        name = name,
        roastLevel = roast?.toUByte(),
        origin = (base.origin ?: BeanOrigin()).copy(
            country = country.ifBlank { null },
            region = region.ifBlank { null },
            farmer = farmer.ifBlank { null },
            variety = variety.ifBlank { null },
            elevation = elevation.ifBlank { null },
            processing = processing.ifBlank { null },
            harvestTime = harvestTime.ifBlank { null },
        ),
        tastingNotes = tastingNotes,
        url = url.ifBlank { null },
        canonicalCoffeeBagId = canonicalCoffeeBagId,
        canonicalRoasterId = canonicalRoasterId,
    )

    /** These fields after the core applied a pick ([result]). */
    fun applying(result: CatalogueAutofill): CatalogueFields {
        val b = result.bean
        val o = b.origin ?: BeanOrigin()
        return copy(
            name = b.name,
            roaster = result.roasterName ?: roaster,
            roast = b.roastLevel?.toInt() ?: roast,
            country = o.country.orEmpty(),
            region = o.region.orEmpty(),
            farmer = o.farmer.orEmpty(),
            variety = o.variety.orEmpty(),
            elevation = o.elevation.orEmpty(),
            processing = o.processing.orEmpty(),
            harvestTime = o.harvestTime.orEmpty(),
            tastingNotes = b.tastingNotes.orEmpty(),
            url = b.url.orEmpty(),
            canonicalCoffeeBagId = b.canonicalCoffeeBagId,
            canonicalRoasterId = b.canonicalRoasterId,
        )
    }
}

/** The one-line outcome under the search field after a pick. */
fun catalogueFillStatus(filledCount: Int): String = when (filledCount) {
    0 -> "Linked — every field was already filled."
    1 -> "Filled 1 field from the catalogue."
    else -> "Filled $filledCount fields from the catalogue."
}

private val catalogueJson = Json { ignoreUnknownKeys = true }

/**
 * Apply a picked catalogue row through the core rule: fill only empty fields
 * (the roaster only when [CatalogueFields.roaster] is blank) unless
 * [replaceAll]; the catalogue links are always set. Returns the new fields and
 * the names of the fields that changed. [core] is the UniFFI
 * `catalogueAutofill` (injectable for JVM tests).
 */
fun autofillFromCatalogue(
    fields: CatalogueFields,
    base: Bean,
    entry: CatalogueCoffeeBag,
    replaceAll: Boolean,
    core: (beanJson: String, entryJson: String, roasterSet: Boolean, replaceAll: Boolean) -> String = ::coreCatalogueAutofill,
): Pair<CatalogueFields, List<String>> {
    val out = core(
        catalogueJson.encodeToString(Bean.serializer(), fields.toBean(base)),
        catalogueJson.encodeToString(CatalogueCoffeeBag.serializer(), entry),
        fields.roaster.isNotBlank(),
        replaceAll,
    )
    val result = catalogueJson.decodeFromString(CatalogueAutofill.serializer(), out)
    return fields.applying(result) to result.filled
}

// ── Clash prompt ─────────────────────────────────────────────────────────────

/** Human labels for the bean form (roaster-row fields say "Roaster …"). Web parity. */
val BAG_FORM_FIELD_LABELS: Map<CatalogueField, String> = mapOf(
    CatalogueField.Name to "Name",
    CatalogueField.Roaster to "Roaster",
    CatalogueField.Country to "Country",
    CatalogueField.Region to "Region",
    CatalogueField.Farmer to "Farmer",
    CatalogueField.Variety to "Variety",
    CatalogueField.Elevation to "Elevation",
    CatalogueField.Processing to "Process",
    CatalogueField.HarvestTime to "Harvest time",
    CatalogueField.RoastLevel to "Roast level",
    CatalogueField.TastingNotes to "Tasting notes",
    CatalogueField.Url to "URL",
    CatalogueField.RoasterName to "Roaster name",
    CatalogueField.RoasterWebsite to "Roaster website",
    CatalogueField.RoasterCountry to "Roaster country",
)

/** Human labels for the roaster form (its own fields need no prefix). */
val ROASTER_FORM_FIELD_LABELS: Map<CatalogueField, String> = BAG_FORM_FIELD_LABELS + mapOf(
    CatalogueField.RoasterName to "Name",
    CatalogueField.RoasterWebsite to "Website",
    CatalogueField.RoasterCountry to "Country",
)

/** The clash dialog's title. */
const val CLASH_TITLE = "Some fields are already filled in"

/** Beyond this many fields the list ends "… and N more". */
private const val CLASH_LIST_MAX = 5

/** "A", "A and B", "A, B and C"; more than five → the first four + "and N more". */
fun joinFieldLabels(labels: List<String>): String = when {
    labels.size > CLASH_LIST_MAX -> {
        val shown = labels.take(CLASH_LIST_MAX - 1)
        "${shown.joinToString(", ")} and ${labels.size - shown.size} more"
    }
    labels.size <= 1 -> labels.firstOrNull().orEmpty()
    else -> "${labels.dropLast(1).joinToString(", ")} and ${labels.last()}"
}

/** The clash dialog's body for [fields]. */
fun clashMessage(fields: List<CatalogueField>, labels: Map<CatalogueField, String> = BAG_FORM_FIELD_LABELS): String =
    "The catalogue has different values for ${joinFieldLabels(fields.map { labels[it] ?: it.string })}."

/** The clash dialog's answer. */
enum class ClashChoice {
    /** Fill only the empty fields (the primary, safe answer). */
    KeepMine,

    /** Replace the clashing fields too (catalogue blanks never erase). */
    UseCatalogue,
}

/**
 * The pick → (prompt) → apply decision for one catalogue form. [offer] a pick
 * with its clash list: none → `apply(replaceAll = false)` right away; some →
 * it waits in [pending] for the dialog, and [resolve] applies the answer
 * (`null` = dismissed: nothing is applied). Compose state, so the dialog host
 * recomposes on it; plain JVM-testable.
 */
class CatalogueClashGate {
    /** A pick waiting on the dialog. */
    class Pending(val clashes: List<CatalogueField>, val apply: (replaceAll: Boolean) -> Unit)

    var pending by androidx.compose.runtime.mutableStateOf<Pending?>(null)
        private set

    fun offer(clashes: List<CatalogueField>, apply: (replaceAll: Boolean) -> Unit) {
        if (clashes.isEmpty()) {
            pending = null
            apply(false)
        } else {
            pending = Pending(clashes, apply)
        }
    }

    fun resolve(choice: ClashChoice?) {
        val p = pending ?: return
        pending = null
        when (choice) {
            ClashChoice.KeepMine -> p.apply(false)
            ClashChoice.UseCatalogue -> p.apply(true)
            null -> Unit
        }
    }
}

// ── Bag pick: clashes + apply (incl. the roaster the bag is filed under) ─────

/** The core inputs a bag pick shares between the clash check and the apply. */
data class CataloguePickInput(
    val fields: CatalogueFields,
    val base: Bean,
    val entry: CatalogueCoffeeBag,
    /** The local roaster directory. */
    val roasters: List<Roaster>,
    /** The bag's catalogue roaster record (website / country), or null. */
    val fetched: CatalogueRoaster?,
)

private fun CataloguePickInput.coreArgs(): Array<String> = arrayOf(
    catalogueJson.encodeToString(Bean.serializer(), fields.toBean(base)),
    catalogueJson.encodeToString(CatalogueCoffeeBag.serializer(), entry),
    fields.roaster,
    catalogueJson.encodeToString(ListSerializer(Roaster.serializer()), roasters),
    fetched?.let { catalogueJson.encodeToString(CatalogueRoaster.serializer(), it) } ?: "null",
)

/**
 * Every clash a bag pick would raise (bag fields, the roaster input, the
 * matched roaster's name / website / country), in form order. [core] is the
 * UniFFI `catalogueClashes` (injectable for JVM tests).
 */
fun catalogueClashes(
    input: CataloguePickInput,
    core: (bean: String, entry: String, roasterInput: String, roasters: String, fetched: String) -> String = ::coreCatalogueClashes,
): List<CatalogueField> {
    val a = input.coreArgs()
    return catalogueJson.decodeFromString(ListSerializer(CatalogueField.serializer()), core(a[0], a[1], a[2], a[3], a[4]))
}

/** A bag pick's outcome for the editor. */
data class CatalogueBagPick(
    /** The editor fields after the pick (roaster text included). */
    val fields: CatalogueFields,
    /** Bag + roaster fields that changed — for the "Filled N fields" line. */
    val filledCount: Int,
    /** The roaster to file the bag under (write at Save), or null = unchanged. */
    val roaster: CataloguePickRoaster?,
)

/**
 * Apply a bag pick through the core (`catalogue_pick`): bag fields (empty only
 * unless [replaceAll]) and the roaster to file it under — an existing row
 * linked to / named like the catalogue roaster, or a seed to create.
 */
fun pickFromCatalogue(
    input: CataloguePickInput,
    replaceAll: Boolean,
    core: (bean: String, entry: String, roasterInput: String, roasters: String, fetched: String, replaceAll: Boolean) -> String = ::coreCataloguePick,
): CatalogueBagPick {
    val a = input.coreArgs()
    val pick = catalogueJson.decodeFromString(CataloguePick.serializer(), core(a[0], a[1], a[2], a[3], a[4], replaceAll))
    val fields = input.fields.applying(CatalogueAutofill(bean = pick.bean, roasterName = pick.roaster?.name, filled = pick.filled))
    return CatalogueBagPick(fields, pick.filled.size + (pick.roaster?.filled?.size ?: 0), pick.roaster)
}

/**
 * The roaster row a bag pick files the bag under: the updated existing row,
 * or — for a seed — a fresh row ([mint]: new id + timestamps) carrying the
 * catalogue name / website / country and link.
 */
fun pickedRoasterRow(picked: CataloguePickRoaster, mint: (name: String) -> Roaster): Roaster =
    if (!picked.isNew) picked.roaster
    else mint(picked.roaster.name).copy(
        website = picked.roaster.website,
        country = picked.roaster.country,
        catalogueRoasterId = picked.roaster.catalogueRoasterId,
    )

// ── Roaster form pick ────────────────────────────────────────────────────────

/** The roaster editors' fields, as they hold them (plain strings). */
data class RoasterFields(
    val name: String,
    val website: String,
    val city: String,
    val country: String,
    val notes: String,
    val catalogueRoasterId: String?,
) {
    /** The core input ([base] = the row being edited, or a blank draft). */
    fun toRoaster(base: Roaster): Roaster = base.copy(
        name = name,
        website = website.ifBlank { null },
        city = city.ifBlank { null },
        country = country.ifBlank { null },
        notes = notes,
        catalogueRoasterId = catalogueRoasterId,
    )

    /** These fields after the core applied a pick. */
    fun applying(r: Roaster): RoasterFields = copy(
        name = r.name,
        website = r.website.orEmpty(),
        country = r.country.orEmpty(),
        catalogueRoasterId = r.catalogueRoasterId,
    )
}

/** A blank roaster for a not-yet-saved roaster form (core input only). */
fun draftRoaster(): Roaster = Roaster(id = "roaster:draft", name = "", notes = "", metadata = JsonNull, createdAt = 0L, updatedAt = 0L)

/** The roaster form's clashes for a picked catalogue roaster. */
fun catalogueRoasterClashes(
    fields: RoasterFields,
    base: Roaster,
    entry: CatalogueRoaster,
    core: (roaster: String, entry: String) -> String = ::coreCatalogueRoasterClashes,
): List<CatalogueField> = catalogueJson.decodeFromString(
    ListSerializer(CatalogueField.serializer()),
    core(
        catalogueJson.encodeToString(Roaster.serializer(), fields.toRoaster(base)),
        catalogueJson.encodeToString(CatalogueRoaster.serializer(), entry),
    ),
)

/**
 * Apply a picked catalogue roaster: name / website / country (empty only
 * unless [replaceAll]) and the `catalogueRoasterId` link. Returns the new
 * fields and how many changed.
 */
fun autofillRoasterFromCatalogue(
    fields: RoasterFields,
    base: Roaster,
    entry: CatalogueRoaster,
    replaceAll: Boolean,
    core: (roaster: String, entry: String, replaceAll: Boolean) -> String = ::coreCatalogueRoasterAutofill,
): Pair<RoasterFields, Int> {
    val out = core(
        catalogueJson.encodeToString(Roaster.serializer(), fields.toRoaster(base)),
        catalogueJson.encodeToString(CatalogueRoaster.serializer(), entry),
        replaceAll,
    )
    val r = catalogueJson.decodeFromString(CatalogueRoasterAutofill.serializer(), out)
    return fields.applying(r.roaster) to r.filled.size
}

// ── Pick flows (shared by the phone + tablet editors) ────────────────────────

/**
 * A bag pick through [gate]: the clash list is read from [input] now; with no
 * clash [onApplied] runs at once (empty fields only), otherwise after the
 * dialog — "Keep mine" / "Use catalogue" — re-reading [input] so the apply sees
 * the form as it is then. Dismiss applies nothing. [onError] = a core failure.
 */
fun offerBagPick(
    gate: CatalogueClashGate,
    input: () -> CataloguePickInput,
    onApplied: (CatalogueBagPick) -> Unit,
    onError: () -> Unit,
    clashes: (CataloguePickInput) -> List<CatalogueField> = { catalogueClashes(it) },
    pick: (CataloguePickInput, Boolean) -> CatalogueBagPick = { i, all -> pickFromCatalogue(i, all) },
) {
    val found = runCatching { clashes(input()) }.getOrElse { onError(); return }
    gate.offer(found) { replaceAll ->
        runCatching { pick(input(), replaceAll) }.onSuccess(onApplied).onFailure { onError() }
    }
}

/** A roaster-form pick through [gate] — as [offerBagPick], for [RoasterFields]. */
fun offerRoasterPick(
    gate: CatalogueClashGate,
    fields: () -> RoasterFields,
    base: Roaster,
    entry: CatalogueRoaster,
    onApplied: (fields: RoasterFields, filledCount: Int) -> Unit,
    onError: () -> Unit,
    clashes: (RoasterFields) -> List<CatalogueField> = { catalogueRoasterClashes(it, base, entry) },
    autofill: (RoasterFields, Boolean) -> Pair<RoasterFields, Int> = { f, all -> autofillRoasterFromCatalogue(f, base, entry, all) },
) {
    val found = runCatching { clashes(fields()) }.getOrElse { onError(); return }
    gate.offer(found) { replaceAll ->
        runCatching { autofill(fields(), replaceAll) }
            .onSuccess { (f, n) -> onApplied(f, n) }
            .onFailure { onError() }
    }
}

/** The held bag-pick roaster still applies at Save: the roaster input names it. */
fun CataloguePickRoaster.stillNamedBy(roasterInput: String): Boolean =
    name.trim().equals(roasterInput.trim(), ignoreCase = true)
