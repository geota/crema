package coffee.crema

import java.io.File
import kotlin.test.fail

/**
 * The app's main Kotlin sources, for tests that pin what the code no longer
 * contains. Gradle runs unit tests from the module directory (`android/app`);
 * the second path covers a run from `android/`.
 */
internal object MainSources {
    private val root: File by lazy {
        listOf("src/main/java", "app/src/main/java").map(::File).firstOrNull { it.isDirectory }
            ?: fail("Can't find the app's main sources from ${File(".").absolutePath}")
    }

    /** Every `.kt` file under `src/main/java`, as (path relative to it, text). */
    fun kotlinFiles(): List<Pair<String, String>> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            .sortedBy { it.first }
            .toList()
}
