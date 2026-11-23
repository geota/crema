package coffee.crema.ble.proxy

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The relay wire fixtures, shared with the core: one file per sample, holding
 * the exact text that crosses the WebSocket, under
 * `core/de1-relay-app/tests/golden/v4/` (a test resource directory here, see
 * build.gradle.kts; the core's cargo tests read the same files).
 *
 * A wire change fails the golden tests. When the change is intended, rerun
 * them with `CREMA_UPDATE_GOLDEN=1` in the environment: the fixtures are
 * rewritten from the current code, and the diff is the change to review. A
 * protocol bump keeps the old directory as the old-peer fixtures and adds the
 * next version's.
 */
internal object Golden {
    /** The protocol version these fixtures describe. */
    const val VERSION = 4
    private const val ROOT = "v$VERSION"

    /** The fixtures' directory, from android/app (where Gradle runs the tests). */
    const val SOURCE_DIR = "../../core/de1-relay-app/tests/golden"

    private val updating = System.getenv("CREMA_UPDATE_GOLDEN") == "1"

    /** The fixture text at [path] (relative to the version directory), without
     *  its trailing newline, or null when there is none. */
    fun read(path: String): String? =
        javaClass.classLoader?.getResource("$ROOT/$path")?.readText()?.trimEnd('\n')

    /** The fixture text at [path]; fails when it is missing. */
    fun require(path: String): String = read(path) ?: fail("Missing golden fixture $ROOT/$path. $HOW_TO_UPDATE")

    /** The fixture names (without `.json`) in directory [dir]. */
    fun list(dir: String): Set<String> {
        val url = javaClass.classLoader?.getResource("$ROOT/$dir") ?: return emptySet()
        return File(url.toURI()).listFiles { f -> f.name.endsWith(".json") }
            .orEmpty().map { it.name.removeSuffix(".json") }.toSet()
    }

    /** Assert that [actual] is the fixture at [path], byte for byte; in update
     *  mode, write it instead. */
    fun check(path: String, actual: String) {
        if (updating) {
            val file = File("$SOURCE_DIR/$ROOT/$path")
            file.parentFile?.mkdirs()
            file.writeText(actual + "\n")
            return
        }
        assertEquals(require(path), actual, "The wire form of $ROOT/$path changed. $HOW_TO_UPDATE")
    }

    private const val HOW_TO_UPDATE =
        "If the change is intended, rerun with CREMA_UPDATE_GOLDEN=1 and review the fixture diff " +
            "(peers already in the field still send and expect the old form)."
}
