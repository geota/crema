package coffee.crema.faults

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The release and nightly builds (release-signed, shipped) have no path to
 * the hardware run's fault hooks (DESIGN.md §7). The only way to an injector
 * is `installedFaults`, defined once per build: release and nightly compile
 * `src/shipped`, whose injector is [NoFaults] and whose Debug-screen controls
 * and banner draw nothing; the armable `DebugFaults` lives in `src/debug`,
 * which only the debug build compiles. This test pins each half of that.
 */
class ShippedHasNoFaultInjectionTest {
    private val app: File = listOf(".", "app").map(::File).firstOrNull { File(it, "src/shipped").isDirectory }
        ?: fail("Can't find the app module from ${File(".").absolutePath}")

    private fun kotlinUnder(dir: String): List<File> =
        File(app, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `NoFaults never drops anything`() {
        assertFalse(NoFaults.dropStop())
        assertFalse(NoFaults.dropRequestedState())
        assertFalse(NoFaults.dropWeightFrame(0L))
        assertFalse(NoFaults.dropWeightFrame(Long.MAX_VALUE))
    }

    @Test
    fun `the shipped twin returns NoFaults and draws nothing`() {
        val files = kotlinUnder("src/shipped/java")
        assertEquals(listOf("Faults.kt"), files.map { it.name }, "src/shipped holds only the no-op twin")
        val text = files.single().readText()
        assertTrue(Regex("""fun installedFaults\(log: \(String\) -> Unit\): FaultInjector = NoFaults""").containsMatchIn(text))
        assertTrue(Regex("""fun FaultInjectionPanel\(faults: FaultInjector\) \{\s*\}""").containsMatchIn(text), "an empty panel")
        assertTrue(Regex("""fun FaultInjectionBanner\(faults: FaultInjector\) \{\s*\}""").containsMatchIn(text), "an empty banner")
        assertFalse("DebugFaults" in text)
    }

    @Test
    fun `the armable injector is debug-only and the shared code never names it`() {
        assertTrue(File(app, "src/debug/java/coffee/crema/faults/DebugFaults.kt").isFile)
        val shared = kotlinUnder("src/main/java") + kotlinUnder("src/shipped/java")
        val leaks = shared.filter { f ->
            val t = f.readText()
            "DebugFaults" in t || "FaultStatus" in t || "armStops" in t || "armWeight" in t || "armRequest" in t
        }
        assertEquals(emptyList(), leaks.map { it.name })
        for (variant in listOf("release", "nightly")) {
            assertFalse(File(app, "src/$variant").exists(), "src/$variant would add sources to a shipped build")
        }
    }

    @Test
    fun `release and nightly compile src-shipped, and nothing else adds src-debug`() {
        val gradle = File(app, "build.gradle.kts").readText()
        assertTrue("""getByName("release").kotlin.directories.add("src/shipped/java")""" in gradle)
        assertTrue("""getByName("nightly").kotlin.directories.add("src/shipped/java")""" in gradle)
        assertFalse(Regex("""(srcDir|directories\.add)\("src/debug""").containsMatchIn(gradle), "src/debug is only the debug build's")
    }
}
