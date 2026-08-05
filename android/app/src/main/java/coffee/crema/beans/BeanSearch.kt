package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.FieldHit
import coffee.crema.core.Roaster
import coffee.crema.core.SearchField
import coffee.crema.core.SearchHit
import coffee.crema.core.searchBeans as coreSearchBeans
import coffee.crema.core.searchRoasters as coreSearchRoasters
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * Bean-library search — the Android side of the core matcher (issue 62).
 *
 * The ranking lives in `de1_domain::bean_search` and is reached through the
 * FFI, so the tablet, the phone and the web PWA return the same bags in the
 * same order for the same query. What used to be here was a
 * `name.contains(q) || roaster.contains(q) || …` chain that covered eight
 * fields, could not search tags at all (web could), and returned nothing for
 * a single mistyped letter.
 *
 * The library JSON is memoised on list identity: the query changes on every
 * keystroke, the library only when the user edits a bag, so re-serialising
 * the whole library per keystroke would be pure waste.
 */

/** Wire codec for the search FFI round-trip. */
private val searchJson = Json { ignoreUnknownKeys = true }

/** One-entry memo over `encodeToString`, keyed on list identity. */
private class JsonMemo<T>(private val encode: (List<T>) -> String) {
    private var lastRows: List<T>? = null
    private var lastJson: String = "[]"

    fun of(rows: List<T>): String {
        // Reference equality on purpose: the ViewModel hands out the same list
        // until something in it changes, and a deep comparison would cost more
        // than the re-encode it saves.
        if (rows !== lastRows) {
            lastRows = rows
            lastJson = encode(rows)
        }
        return lastJson
    }
}

private val beansMemo = JsonMemo<Bean> { searchJson.encodeToString(ListSerializer(Bean.serializer()), it) }
private val roastersMemo = JsonMemo<Roaster> { searchJson.encodeToString(ListSerializer(Roaster.serializer()), it) }

/**
 * A ranked search over one library, keyed by row id.
 *
 * [active] is false for a blank query — "no query" is not "no results", and
 * the caller keeps its own ordering in that case.
 */
class SearchResults private constructor(
    private val byId: Map<String, SearchHit>,
    val active: Boolean,
) {
    /** Whether [id] matched. Always true when the search is inactive. */
    fun matches(id: String): Boolean = !active || byId.containsKey(id)

    /** This row's match, or null. */
    fun hit(id: String): SearchHit? = byId[id]

    /**
     * Relevance of [id], or `-1f` when it did not match. Sorting on the score
     * rather than the result index lets rows that tie exactly fall through to
     * the caller's own sort control instead of being frozen into the core's
     * id tiebreak.
     */
    fun score(id: String): Float = byId[id]?.score ?: -1f

    companion object {
        val INACTIVE = SearchResults(emptyMap(), active = false)

        fun of(hits: List<SearchHit>) = SearchResults(hits.associateBy { it.id }, active = true)
    }
}

private fun parse(run: () -> String): SearchResults = runCatching {
    SearchResults.of(searchJson.decodeFromString(ListSerializer(SearchHit.serializer()), run()))
}.getOrElse {
    // A search box must never empty the library. Degrading to "no search"
    // shows the user their unfiltered list; degrading to "no matches" would
    // look like their beans had vanished.
    SearchResults.INACTIVE
}

/**
 * Rank [beans] against [query] — typo-tolerant and weighted across every
 * recorded field. [roasters] are passed so a bag can match on its roastery's
 * name. A blank query returns [SearchResults.INACTIVE].
 */
fun searchBeans(beans: List<Bean>, roasters: List<Roaster>, query: String): SearchResults {
    if (query.isBlank()) return SearchResults.INACTIVE
    return parse { coreSearchBeans(beansMemo.of(beans), roastersMemo.of(roasters), query) }
}

/** The roaster-directory half of [searchBeans]. */
fun searchRoasters(roasters: List<Roaster>, query: String): SearchResults {
    if (query.isBlank()) return SearchResults.INACTIVE
    return parse { coreSearchRoasters(roastersMemo.of(roasters), query) }
}

/** The `FieldHit` for one field, for highlighting it where it already shows. */
fun SearchHit?.forField(field: SearchField): FieldHit? = this?.fields?.firstOrNull { it.field == field }

/**
 * The field hit a card should surface as a "matched in …" line, or null when
 * the match needs no explanation.
 *
 * Name and roaster hits are suppressed: both are already the largest text on
 * the card and are highlighted in place, so repeating them below is noise.
 * Everything else — a process, a tasting note, where the bag was bought — is
 * invisible until we say so.
 */
fun SearchHit?.explanatory(): FieldHit? =
    this?.fields?.firstOrNull { it.field != SearchField.Name && it.field != SearchField.Roaster }
