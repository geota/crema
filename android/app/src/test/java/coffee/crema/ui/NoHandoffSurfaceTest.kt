package coffee.crema.ui

import coffee.crema.MainSources
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AC-1: hand-off, take-over and reclaim are gone from the app — the frames'
 * senders, the verb, the state machine, its timers and its UI. A scan of
 * `src/main` for every name that carried them. The retired
 * `Frame.HandoffOffer` stays decodable (a v3 codec drops the link on an
 * unknown type), so it may appear only where it is declared and where a
 * remote ignores it.
 */
class NoHandoffSurfaceTest {

    private val removed = listOf(
        "TakeOver", "requestHandoff", "offerHandoff", "acceptHandoffOffer", "declineHandoffOffer",
        "onHandoffOffer", "onHandoffOffered", "grantHandoff", "armHandoffReclaim", "handoffReleaseTimeoutMs",
        "awaitClientGone", "handoffTargets", "HandoffTarget", "pendingHandoffOffer", "HandoffOfferDialog",
        "handoffRefusal", "takeoverRefusedMessage", "onHandoffGranted", "promoteMirroredProfile",
        "refreshMirrorClients", "mirrorClients", "HANDOFF_IDLE_STATES", "autoMirrorDiscoveredPrimary",
        "setProxyRole", "onTakeOver", "onHandOff",
    )

    @Test
    fun `no handoff, take-over or reclaim name is left in the app`() {
        val words = removed.map { it to Regex("\\b$it\\b") }
        val hits = MainSources.kotlinFiles().flatMap { (path, text) ->
            text.lines().withIndex().flatMap { (i, line) ->
                words.filter { (_, re) -> re.containsMatchIn(line) }.map { (name, _) -> "$path:${i + 1}: $name" }
            }
        }
        assertEquals(emptyList(), hits)
    }

    @Test
    fun `the retired handoff offer is only declared and ignored`() {
        val hits = MainSources.kotlinFiles().flatMap { (path, text) ->
            text.lines().withIndex().filter { (_, line) -> "HandoffOffer" in line }.map { (_, line) -> path to line.trim() }
        }
        val allowed = hits.filter { (path, line) ->
            (path == "coffee/crema/ble/proxy/Frame.kt" && line.startsWith("data class HandoffOffer(")) ||
                (path == "coffee/crema/ble/proxy/ProxyTransport.kt" && line.startsWith("is Frame.HandoffOffer -> Log."))
        }
        assertEquals(emptyList(), hits - allowed.toSet(), "HandoffOffer outside its declaration and the ignore branch")
        assertEquals(2, allowed.size, "the declaration and the ignore branch: $hits")
    }
}
