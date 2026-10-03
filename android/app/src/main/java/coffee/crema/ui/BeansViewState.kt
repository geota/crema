package coffee.crema.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import coffee.crema.beans.BeanFacets

/**
 * The Beans library's view state — Bags vs Roasters, the bag facets and the
 * roaster scope (#86 shelf). Hoisted to MainActivity and shared by the
 * phone and tablet Beans screens so it survives both the bag-editor round trip
 * and a phone↔tablet host swap (rotating across the 840dp breakpoint).
 */
@Stable
class BeansViewState(
    tab: String = "bags",
    status: String = "all",
    roasterScopeId: String? = null,
    detailBeanId: String? = null,
    showDuplicates: Boolean = false,
    dismissedDuplicates: Set<String> = emptySet(),
    roast: String? = null,
    tags: Set<String> = emptySet(),
    includeArchived: Boolean = false,
) {
    var tab by mutableStateOf(tab)
    var roasterScopeId by mutableStateOf(roasterScopeId)

    // The bag facets (geota/crema#124) — independent axes that compose, so
    // Archived + Light is "archived light roasts" rather than one chip
    // replacing the other (the old single `filter` string).

    /** Status chip: all / active / favourite / frozen / archived. */
    var status by mutableStateOf(status)

    /** Roast chip: light / medium / dark, or null for none. */
    var roast by mutableStateOf(roast)

    /** Tag chips — all must be on the bag. */
    var tags by mutableStateOf(tags)

    /** Mix archived bags (dimmed) into All / Favourite. Off by default. */
    var includeArchived by mutableStateOf(includeArchived)

    /** The selections as the core filter's input, scoped to [scopeId]. */
    fun facets(scopeId: String?): BeanFacets = BeanFacets(
        status = status,
        includeArchived = includeArchived,
        roast = roast,
        tags = tags,
        roasterId = scopeId,
    )

    /** Status chip tap: re-tapping the active one returns to All. */
    fun selectStatus(id: String) {
        status = if (status == id && id != "all") "all" else id
    }

    /** Roast chip tap: re-tapping the active one clears the roast filter. */
    fun selectRoast(id: String) {
        roast = if (roast == id) null else id
    }

    fun toggleTag(tag: String) {
        tags = if (tag in tags) tags - tag else tags + tag
    }

    fun toggleIncludeArchived() {
        includeArchived = !includeArchived
    }

    /**
     * The phone's one-row chip rail addresses every axis by a prefixed id:
     * `s:<status>`, `r:<roast>`, `t:<tag>`, `x:include-archived`, `x:clear`.
     */
    fun isChipSelected(id: String): Boolean = when {
        id.startsWith("s:") -> status == id.removePrefix("s:")
        id.startsWith("r:") -> roast == id.removePrefix("r:")
        id.startsWith("t:") -> id.removePrefix("t:") in tags
        id == "x:include-archived" -> includeArchived
        else -> false
    }

    /** Dispatch a prefixed chip id (see [isChipSelected]) to its axis. */
    fun onChip(id: String) {
        when {
            id.startsWith("s:") -> selectStatus(id.removePrefix("s:"))
            id.startsWith("r:") -> selectRoast(id.removePrefix("r:"))
            id.startsWith("t:") -> toggleTag(id.removePrefix("t:"))
            id == "x:include-archived" -> toggleIncludeArchived()
            id == "x:clear" -> clearFilters()
        }
    }

    /** Whether any facet is narrowing (or widening) the default list. */
    val hasFilters: Boolean
        get() = status != "all" || roast != null || tags.isNotEmpty() || includeArchived

    /** Back to the default list: All, no roast, no tags, archived hidden. */
    fun clearFilters() {
        status = "all"
        roast = null
        tags = emptySet()
        includeArchived = false
    }

    /**
     * The bag whose read-only detail is open (phone: swapped-in screen,
     * tablet: side sheet), or null. Shared so the detail — and the Brew Log
     * form its "Log a brew" action opens (issue #10) — comes back on the other
     * host after a rotation across 840dp.
     */
    var detailBeanId by mutableStateOf(detailBeanId)

    /** Roasters tab: include rows tagged as merged duplicates (web "Show dupes"; off by default). */
    var showDuplicates by mutableStateOf(showDuplicates)

    /** Dupe ids whose merge banner was answered "Keep separate" — hidden for the session. */
    var dismissedDuplicates by mutableStateOf(dismissedDuplicates)

    /** Open [roasterId]'s shelf: the Bags tab scoped to it, unfiltered. */
    fun openShelf(roasterId: String) {
        roasterScopeId = roasterId
        clearFilters()
        tab = "bags"
    }

    /** Back from a shelf: drop the scope and return to the Roasters directory. */
    fun closeShelf() {
        roasterScopeId = null
        tab = "roasters"
    }

    companion object {
        val Saver = listSaver<BeansViewState, String?>(
            save = {
                listOf(
                    it.tab, it.status, it.roasterScopeId, it.detailBeanId,
                    it.showDuplicates.toString(), it.dismissedDuplicates.joinToString("\n"),
                    it.roast, it.tags.joinToString("\n"), it.includeArchived.toString(),
                )
            },
            restore = {
                // Index 1 held the old single facet, which could be a roast
                // band ("light"); split such a value onto the roast axis.
                val legacy = it[1] ?: "all"
                val legacyRoast = legacy.takeIf { f -> f in ROAST_BANDS }
                BeansViewState(
                    it[0] ?: "bags", if (legacyRoast != null) "all" else legacy, it[2], it.getOrNull(3),
                    showDuplicates = it.getOrNull(4) == "true",
                    dismissedDuplicates = it.getOrNull(5).splitLines(),
                    roast = it.getOrNull(6) ?: legacyRoast,
                    tags = it.getOrNull(7).splitLines(),
                    includeArchived = it.getOrNull(8) == "true",
                )
            },
        )
    }
}

private val ROAST_BANDS = setOf("light", "medium", "dark")

private fun String?.splitLines(): Set<String> = this?.split('\n')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

@Composable
fun rememberBeansViewState(): BeansViewState = rememberSaveable(saver = BeansViewState.Saver) { BeansViewState() }
