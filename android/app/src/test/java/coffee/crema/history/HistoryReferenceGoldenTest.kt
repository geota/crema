package coffee.crema.history

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #310 C1: the History list's filter and sort as Android does them today,
 * pinned as a golden the core's reference rules are checked against
 * (`core/de1-history/tests/shells.rs`). The fixture is 300 real-shaped rows in
 * the shape `shots.db` keeps in `row_json` (`core/de1-history/tests/fixtures/`,
 * written by its `gen.mjs`); each query runs through [filterAndSortShots] over
 * the rows, with the live coffee lookup over the fixture's bean library. Android
 * has no tag filter, so those queries are left out. Pure JVM.
 *
 * Regenerate with `CREMA_REGEN_HISTORY_GOLDEN=1 ./gradlew :app:testDebugUnitTest
 * --tests coffee.crema.history.HistoryReferenceGoldenTest`.
 */
class HistoryReferenceGoldenTest {
    private val fixtures = File("../../core/de1-history/tests/fixtures")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Sort(val key: String, val dir: String)

    @Serializable
    private data class Query(
        val name: String,
        val range: String,
        val text: String,
        val profile: String? = null,
        val bean: String? = null,
        val method: String? = null,
        val tags: List<String> = emptyList(),
        val pinnedOnly: Boolean = false,
        val sort: Sort,
    )

    @Serializable
    private data class Queries(val now: Long, val queries: List<Query>)

    @Serializable
    private data class LiveBean(val beanId: String, val name: String, val roaster: String? = null)

    @Serializable
    private data class Live(val beans: List<LiveBean>)

    @Test
    fun `filterAndSortShots matches the golden on every query Android offers`() {
        val rows = File(fixtures, "rows.android.jsonl").readLines().filter { it.isNotBlank() }
            .map { json.decodeFromString(StoredShot.serializer(), it) }
            .rows()
        val index = rows.withIndex().associate { it.value.id to it.index }
        val beans = json.decodeFromString(Live.serializer(), File(fixtures, "live.json").readText())
            .beans.associateBy { it.beanId }
        val live: (String) -> Pair<String, String?>? = { id -> beans[id]?.let { it.name to it.roaster } }
        val spec = json.decodeFromString(Queries.serializer(), File(fixtures, "queries.json").readText())

        val golden = LinkedHashMap<String, List<Int>>()
        for (q in spec.queries) {
            if (q.tags.isNotEmpty()) continue
            val listed = filterAndSortShots(
                rows, q.text, q.range, q.profile, q.sort.key, q.sort.dir == "desc", spec.now,
                beanFilter = q.bean,
                methodFilter = q.method,
                coffeeOf = { shotCoffee(it, live)?.key },
                pinnedOnly = q.pinnedOnly,
            )
            golden[q.name] = listed.map { index.getValue(it.id) }
        }
        assertTrue(golden.size > 30)

        val serializer = MapSerializer(String.serializer(), ListSerializer(Int.serializer()))
        val file = File(fixtures, "golden.android.json")
        if (System.getenv("CREMA_REGEN_HISTORY_GOLDEN") == "1") {
            file.writeText(Json.encodeToString(serializer, golden) + "\n")
        }
        assertEquals(json.decodeFromString(serializer, file.readText()), golden)
    }
}
