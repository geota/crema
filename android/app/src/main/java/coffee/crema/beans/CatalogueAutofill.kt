package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.BeanOrigin
import coffee.crema.core.CatalogueAutofill
import coffee.crema.core.CatalogueCoffeeBag
import coffee.crema.core.CataloguePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import coffee.crema.core.catalogueAutofill as coreCatalogueAutofill

/*
 * Visualizer catalogue search for the bean editors (web `$lib/bean/catalogue`
 * parity). The HTTP is `VisualizerSync.searchCatalogue`; the response parsing
 * and the pick → bean autofill rule are the core's
 * (`de1_domain::visualizer_catalogue`, via UniFFI). This file holds the shell
 * pieces, kept pure so JVM tests drive them without the native core:
 *
 *  - [CatalogueSearchController] — debounced, last-query-wins search state.
 *  - [CatalogueFields] — the editor text fields the catalogue can fill, mapped
 *    to a core [Bean] for the autofill call and back.
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
class CatalogueSearchController(
    private val scope: CoroutineScope,
    private val search: suspend (String) -> CataloguePage,
    private val debounceMs: Long = CATALOGUE_DEBOUNCE_MS,
    private val minChars: Int = CATALOGUE_MIN_CHARS,
) {
    data class State(
        /** The trimmed query the results / error belong to. */
        val query: String = "",
        val loading: Boolean = false,
        val results: List<CatalogueCoffeeBag> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
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
            val page = try {
                search(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, results = emptyList(), error = e.message ?: "Search failed.")
                return@launch
            }
            _state.value = _state.value.copy(loading = false, results = page.entries, error = null)
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
