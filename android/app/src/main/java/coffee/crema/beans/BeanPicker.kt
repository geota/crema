package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.BeanPickerQuery
import coffee.crema.core.BeanPickerResult
import coffee.crema.core.Roaster
import coffee.crema.core.beanPicker as coreBeanPicker
import kotlinx.serialization.json.Json

/*
 * What a bean picker lists — the Android side of `de1_domain::bean_picker`
 * (geota/crema#301). Which bags show, in what order, and how a search reorders
 * them run in the core, the same function the web PWA calls, so the two apps
 * agree. This file only carries the call across the FFI.
 */

/** Wire codec for the picker FFI round-trip. */
private val pickerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** One picker's rows, in display order. */
data class BeanPickerRows(
    val beans: List<Bean>,
    /** Whether "Include archived" could add anything; the toggle hides otherwise. */
    val hasArchived: Boolean,
)

/**
 * The rows for [currentId] (the bag the picker opened on), [includeArchived]
 * and the [search] box. [core] is the FFI call, injectable because the native
 * library is not loaded on the JVM. A failing call degrades to the library
 * unsearched and unranked: a picker must never make the beans vanish.
 */
fun beanPickerRows(
    beans: List<Bean>,
    roasters: List<Roaster>,
    currentId: String?,
    includeArchived: Boolean,
    search: String,
    core: (String, String, String) -> String = { b, r, q -> coreBeanPicker(b, r, q) },
): BeanPickerRows = runCatching {
    val query = pickerJson.encodeToString(BeanPickerQuery.serializer(), BeanPickerQuery(currentId, includeArchived, search))
    val result = pickerJson.decodeFromString(
        BeanPickerResult.serializer(),
        core(libraryBeansJson(beans), libraryRoastersJson(roasters), query),
    )
    val byId = beans.associateBy { it.id }
    BeanPickerRows(result.ids.mapNotNull(byId::get), result.hasArchived)
}.getOrElse {
    val live = beans.filter { it.deletedAt == null }
    BeanPickerRows(
        live.filter { includeArchived || it.archivedAt == null || it.id == currentId },
        live.any { it.archivedAt != null && it.id != currentId },
    )
}

/**
 * The core's picker order over a list the caller already filtered: [activeId]
 * first, then favourites, then the rest oldest first.
 */
fun rankBeansForPicker(
    beans: List<Bean>,
    activeId: String?,
    core: (String, String, String) -> String = { b, r, q -> coreBeanPicker(b, r, q) },
): List<Bean> = beanPickerRows(beans, emptyList(), activeId, includeArchived = true, search = "", core).beans

/** A picker row's second line: what tells two similar bags apart. */
fun beanPickerMeta(bean: Bean): String = listOfNotNull(
    "Archived".takeIf { bean.archivedAt != null },
    beanDaysOffRoast(bean)?.let { "${it}d off roast" },
    bean.remaining?.takeIf { (bean.bagSize ?: 0f) > 0f }?.let { "${it.toInt()} g left" },
    bean.grinderSetting?.takeIf { it.isNotBlank() }?.let { "Grind $it" },
).joinToString(" · ").ifEmpty { "—" }
