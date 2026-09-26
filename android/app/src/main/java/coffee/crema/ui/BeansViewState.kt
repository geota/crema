package coffee.crema.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The Beans library's view state — Bags vs Roasters, the bag facet filter and
 * the roaster scope (#86 shelf). Hoisted to MainActivity and shared by the
 * phone and tablet Beans screens so it survives both the bag-editor round trip
 * and a phone↔tablet host swap (rotating across the 840dp breakpoint).
 */
@Stable
class BeansViewState(tab: String = "bags", filter: String = "all", roasterScopeId: String? = null) {
    var tab by mutableStateOf(tab)
    var filter by mutableStateOf(filter)
    var roasterScopeId by mutableStateOf(roasterScopeId)

    /** Open [roasterId]'s shelf: the Bags tab scoped to it, unfiltered. */
    fun openShelf(roasterId: String) {
        roasterScopeId = roasterId
        filter = "all"
        tab = "bags"
    }

    /** Back from a shelf: drop the scope and return to the Roasters directory. */
    fun closeShelf() {
        roasterScopeId = null
        tab = "roasters"
    }

    companion object {
        val Saver = listSaver<BeansViewState, String?>(
            save = { listOf(it.tab, it.filter, it.roasterScopeId) },
            restore = { BeansViewState(it[0] ?: "bags", it[1] ?: "all", it[2]) },
        )
    }
}

@Composable
fun rememberBeansViewState(): BeansViewState = rememberSaveable(saver = BeansViewState.Saver) { BeansViewState() }
