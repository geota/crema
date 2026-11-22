package coffee.crema.ui

import coffee.crema.MainSources
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #310 A3b: `ui.history` holds no list of rows, only the counts the screens
 * show and a row by id; the rows are the history store's. These pin it, so a
 * new reader can't bring a full list back into the UI state.
 */
class NoHistoryListInUiStateTest {
    @Test
    fun `the UI state's history is no list`() {
        val type = MainUiState::class.java.getDeclaredField("history").type
        assertEquals(HistoryState::class.java, type)
        assertFalse(Collection::class.java.isAssignableFrom(HistoryState::class.java), "HistoryState is no collection")
        assertFalse(Iterable::class.java.isAssignableFrom(HistoryState::class.java), "nor anything to walk")
        val edits = LibraryRows::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(setOf("beans", "roasters", "grinders"), edits, "the UI state's edits carry no history rows")
    }

    @Test
    fun `no main source walks the UI state's history as a list`() {
        // A list operation on `<state>.history`: what reading every row from the UI state looks like.
        val walk = Regex(
            """\.history\s*\.\s*(filter|filterNot|map|mapNotNull|mapTo|forEach|firstOrNull|first|lastOrNull|last|single|singleOrNull|any|all|none|""" +
                """count\s*\{|associate\w*|groupBy|sorted\w*|take|drop|indexOf\w*|iterator|asSequence|toList|contains|plus|minus|withIndex|zip|sumOf)\b""",
        )
        val hits = MainSources.kotlinFiles().flatMap { (path, text) ->
            text.lineSequence().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") && walk.containsMatchIn(line) }
                .map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }
                .toList()
        }
        assertTrue(hits.isEmpty(), "reads every history row from the UI state:\n${hits.joinToString("\n")}")
    }

    @Test
    fun `no main source puts a list of rows into the UI state's history`() {
        val put = Regex("""history\s*=\s*(rowViewOf|it\.history\.with|rows\.history|listOf|emptyList)""")
        val hits = MainSources.kotlinFiles().flatMap { (path, text) ->
            text.lineSequence().withIndex().filter { (_, line) -> put.containsMatchIn(line) }.map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }.toList()
        }
        assertTrue(hits.isEmpty(), "writes a row list into ui.history:\n${hits.joinToString("\n")}")
    }
}
