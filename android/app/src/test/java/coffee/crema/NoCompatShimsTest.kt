package coffee.crema

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relay v4 R1–R5 (spec §4.7, AC-40/41/42/44): the v3 compatibility shims are
 * gone from `android/app/src/main`. A source scan, so a shim put back fails
 * here even if nothing calls it. The one-time upgrade migration still names
 * the v3 files it deletes ([allowed]).
 */
class NoCompatShimsTest {

    /** Files that may name a v3 leftover: the migration that deletes them. */
    private val allowed = setOf("RelayUpgradeMigration.kt")

    private fun hits(vararg patterns: Regex): List<String> {
        val root = File("src/main")
        assertTrue("$root must exist (run from android/app)", root.isDirectory)
        return root.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") && it.name !in allowed }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    patterns.firstOrNull { it.containsMatchIn(line) }?.let { "${f.path}:${i + 1}: ${line.trim()}" }
                }
            }
            .toList()
    }

    private fun assertNone(what: String, vararg patterns: Regex) {
        val found = hits(*patterns)
        assertTrue("$what:\n${found.joinToString("\n")}", found.isEmpty())
    }

    /** AC-44 (R5): no v3 pairing secret, HMAC proof, v3 handshake frame or pairing store. */
    @Test
    fun v3PairingGone() = assertNone(
        "v3 pairing left in main",
        Regex("\\bPairingCrypto\\b"),
        Regex("\\bPairingRequest\\b"),
        Regex("\\bPairingStore\\b"),
        Regex("\\bPairingChoice\\b"),
        Regex("\\bDenyReason\\b"),
        Regex("\\bpairingSecret\\b"),
        Regex("HmacSHA256"),
        Regex("Frame\\.(Hello|Challenge|Auth|Denied)\\b"),
        Regex("@SerialName\\(\"(hello|challenge|auth|denied)\"\\)"),
        Regex("\\bPAIRED_DEVICES\\b"),
    )

    @Test
    fun thePatternsMatchWhatTheyGuard() {
        assertTrue(Regex("\\bPairingRequest\\b").containsMatchIn("class PairingRequest("))
        assertTrue(!Regex("\\bPairingRequest\\b").containsMatchIn("class HubPairingRequest("))
        assertTrue(Regex("@SerialName\\(\"(hello|challenge|auth|denied)\"\\)").containsMatchIn("    @SerialName(\"hello\")"))
    }
}
