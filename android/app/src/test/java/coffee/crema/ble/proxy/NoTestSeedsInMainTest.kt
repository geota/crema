package coffee.crema.ble.proxy

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-23 (second line behind CI's release-symbols check): the deterministic relay
 * constructors appear in no main source, Android `src/main` or non-test
 * `web/src`. Only the unit tests (through the test-only bindings) and the
 * relay vitest (through the test-seeds bundle) may name them.
 */
class NoTestSeedsInMainTest {
    private val constructors = Regex(
        "\\b(generate|new|pair|resume)(ForTests|_for_tests)\\b",
    )

    private fun isTestFile(f: File): Boolean =
        f.name.endsWith(".test.ts") || f.name.endsWith(".vitest.ts") || f.name.endsWith(".spec.ts")

    private fun scan(root: File, extensions: Set<String>): List<String> {
        assertTrue("$root must exist (run from android/app)", root.isDirectory)
        return root.walkTopDown()
            .filter { it.isFile && it.extension in extensions && !isTestFile(it) }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    constructors.find(line)?.let { "${f.path}:${i + 1}: ${it.value}" }
                }
            }
            .toList()
    }

    @Test
    fun noTestConstructorsInMainSources() {
        val hits = scan(File("src/main"), setOf("kt", "java")) +
            scan(File("../../web/src"), setOf("ts", "js", "svelte"))
        assertTrue("test-seeds constructors in main sources:\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    @Test
    fun thePatternCatchesEveryConstructor() {
        for (name in listOf("generateForTests", "newForTests", "pairForTests", "resumeForTests", "pair_for_tests")) {
            assertTrue(name, constructors.containsMatchIn("RelayKeys.$name(seed)"))
        }
        assertTrue(!constructors.containsMatchIn("resetIosBridgeForTests()"))
    }
}
