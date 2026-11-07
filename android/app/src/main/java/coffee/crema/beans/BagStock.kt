package coffee.crema.beans

import coffee.crema.core.BagStock
import coffee.crema.core.Bean
import coffee.crema.core.bagStockJson
import coffee.crema.ui.MainUiState
import coffee.crema.ui.modeLabel
import kotlinx.serialization.json.Json

/*
 * How much is left in a bag: the Android side of `de1_domain::bag_stock`
 * (geota/crema#302), the same rule the web PWA calls.
 */

/** Wire codec for the stock FFI round-trip. */
private val stockJson = Json { ignoreUnknownKeys = true }

/**
 * The stock of [bean]. [recentDoses] are its brews' doses, newest first; only
 * the Low count reads them. A failing call reads as untracked: a warning must
 * come from a real answer.
 */
fun bagStock(bean: Bean, recentDoses: List<Float> = emptyList()): BagStock = runCatching {
    val json = bagStockJson(bean.remaining ?: 0f, bean.bagSize ?: 0f, recentDoses)
    stockJson.decodeFromString(BagStock.serializer(), json)
}.getOrDefault(BagStock.Untracked)

/**
 * The bag the Brew banner names, or null when it shouldn't show: only on an
 * empty bag, and out of the way while a shot runs. It never gates a shot, which
 * can start from the machine's own button.
 *
 * A remote's library is its own, so its copy of a bag says nothing about the
 * hub's: there the hub's word decides ([MainUiState.mirroredBagEmpty]), and the
 * name falls back to the hub's summary for a bag this device doesn't hold.
 */
fun MainUiState.emptyBagName(): String? {
    if (shotInProgress) return null
    val bean = beans.firstOrNull { it.id == activeBeanId }
    return when {
        mirror != null -> if (mirroredBagEmpty) bean?.name ?: mirroredBeanSummary.orEmpty() else null
        bean != null && bagStock(bean) == BagStock.Empty -> bean.name
        else -> null
    }
}

/**
 * Whether the bag-empty prompt waits: it stays off a running shot's Stop and a
 * steam, hot-water or flush run's Stop chips, which it would cover.
 */
fun MainUiState.holdsBagPrompt(): Boolean = shotInProgress || modeLabel(machineStateName) != null

/** The empty-bag banner's text. */
fun emptyBagBannerText(name: String): String = "${name.trim().ifEmpty { "This bag" }} is at 0 g. Shots still log to it."

/** The Brew screen's question after a shot empties a bag. */
data class BagEmptyPrompt(
    val beanId: String,
    val name: String,
    /** The bag has no rating: ask for one once the bag's fate is settled. */
    val unrated: Boolean,
    /** Showing the rating question rather than the archive one. */
    val askingRating: Boolean = false,
)
