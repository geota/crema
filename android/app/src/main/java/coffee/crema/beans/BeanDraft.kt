package coffee.crema.beans

import coffee.crema.core.Bean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The editor's Save, onto the bag as it stands now. The form holds every field
 * as it was at [opened], and [draft] is that plus the user's changes, so a field
 * changes here only if the user changed it. A debit or a sync that landed while
 * the form was open stands (geota/crema#324). The origin merges field by field.
 */
fun applyBeanDraft(current: Bean, draft: Bean, opened: Bean, json: Json): Bean {
    fun tree(bean: Bean) = json.encodeToJsonElement(Bean.serializer(), bean).jsonObject
    return json.decodeFromJsonElement(Bean.serializer(), changesOver(tree(current), tree(draft), tree(opened)))
}

/** [current] with the keys that differ between [opened] and [draft] taken from [draft]. */
private fun changesOver(current: JsonObject, draft: JsonObject, opened: JsonObject): JsonObject = buildJsonObject {
    for (key in current.keys + draft.keys + opened.keys) {
        val merged = merged(current[key], draft[key], opened[key]) ?: continue
        put(key, merged)
    }
}

private fun merged(current: JsonElement?, draft: JsonElement?, opened: JsonElement?): JsonElement? = when {
    draft == opened -> current
    current is JsonObject && draft is JsonObject && opened is JsonObject -> changesOver(current, draft, opened)
    else -> draft
}
