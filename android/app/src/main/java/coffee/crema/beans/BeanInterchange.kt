package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.BeanImportMerge
import coffee.crema.core.Roaster
import coffee.crema.core.exportCremaJsonl
import coffee.crema.core.mergeBeanImportJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/*
 * The bean library's Crema file (`crema/v1` JSONL) and the import merge. The
 * core writes and reads the file (`exportCremaJsonl` / `importCremaJsonl`, the
 * calls web makes) and merges it ([mergeBeanImport], the core's rule), so a
 * library exported on either shell imports the same way on the other.
 */

private val interchangeJson = Json { ignoreUnknownKeys = true }

/**
 * The bean library as a Crema `crema/v1` JSONL export: header, roasters, beans,
 * no shots (they have their own export) — the core's `export_jsonl`, the same
 * bytes as the `crema.jsonl` in web's `.crema.zip`. [export] is the UniFFI
 * `exportCremaJsonl` (injectable for tests).
 */
fun beanLibraryJsonl(
    beans: List<Bean>,
    roasters: List<Roaster>,
    nowMs: Long,
    cremaVersion: String,
    export: (envelope: String, nowMs: Long, cremaVersion: String) -> String = ::exportCremaJsonl,
): String {
    val envelope = buildJsonObject {
        put("beans", interchangeJson.encodeToJsonElement(ListSerializer(Bean.serializer()), beans))
        put("roasters", interchangeJson.encodeToJsonElement(ListSerializer(Roaster.serializer()), roasters))
        put("shots", JsonArray(emptyList()))
    }
    return export(envelope.toString(), nowMs, cremaVersion)
}

/** `true` when [text] is a Crema `crema/v1` JSONL export: its first record is the `crema/v1` header. */
fun isCremaLibraryJsonl(text: String): Boolean {
    val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return false
    return runCatching {
        (interchangeJson.parseToJsonElement(first).jsonObject["kind"] as? JsonPrimitive)?.content == "crema/v1"
    }.getOrDefault(false)
}

/**
 * Merge an import plan's [wireBeans] / [wireRoasters] into the library
 * ([libBeans] / [libRoasters]) through the core's `merge_bean_import`, the rule
 * web uses: a shared id goes to the newer `updatedAt` (a tie keeps local; an
 * older row never resurrects a local tombstone), an earlier Beanconqueror
 * import and a (roaster, name, roast date) match are skipped, and a same-named
 * library roaster is reused. [core] is the UniFFI `mergeBeanImportJson`
 * (injectable for tests).
 */
fun mergeBeanImport(
    wireBeans: List<Bean>,
    wireRoasters: List<Roaster>,
    libBeans: List<Bean>,
    libRoasters: List<Roaster>,
    core: (payload: String) -> String = ::mergeBeanImportJson,
): BeanImportMerge {
    val beans = ListSerializer(Bean.serializer())
    val roasters = ListSerializer(Roaster.serializer())
    val payload = buildJsonObject {
        put("incomingBeans", interchangeJson.encodeToJsonElement(beans, wireBeans))
        put("incomingRoasters", interchangeJson.encodeToJsonElement(roasters, wireRoasters))
        put("libraryBeans", interchangeJson.encodeToJsonElement(beans, libBeans))
        put("libraryRoasters", interchangeJson.encodeToJsonElement(roasters, libRoasters))
    }
    return interchangeJson.decodeFromString(BeanImportMerge.serializer(), core(payload.toString()))
}
