package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.Roaster
import coffee.crema.core.exportCremaJsonl
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/*
 * The bean library's Crema file (`crema/v1` JSONL) and the import merge. The
 * core writes and reads the file (`exportCremaJsonl` / `importCremaJsonl`, the
 * calls web makes), so a library exported on either shell imports on the other.
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
 * What an import adds to the library: the new beans and roasters, the import's
 * bean id → the library id its shots should reference, and how many beans were
 * already there.
 */
data class BeanImportMerge(
    val newBeans: List<Bean>,
    val newRoasters: List<Roaster>,
    val beanIdRemap: Map<String, String>,
    val skipped: Int,
)

/**
 * Merge an import plan's [wireBeans] / [wireRoasters] against the library
 * ([libBeans] / [libRoasters]) with web's dedup rules: skip a bean whose id or
 * `beanconquerorId` is already present (a Crema re-import, a resumable
 * Beanconqueror re-import); repoint a new bean to an existing same-named
 * roaster (dropping the duplicate roaster); skip a bean matching an existing
 * (roaster, name, roastedOn) triple. A roaster whose id is already present is
 * never added twice. Skipped beans remap to the library row they matched.
 */
fun mergeBeanImport(
    wireBeans: List<Bean>,
    wireRoasters: List<Roaster>,
    libBeans: List<Bean>,
    libRoasters: List<Roaster>,
): BeanImportMerge {
    val existingRoasterByLc = libRoasters.associateBy { it.name.trim().lowercase() }
    val existingById = libBeans.associateBy { it.id }
    val existingByBcId = libBeans.filter { it.beanconquerorId != null }.associateBy { it.beanconquerorId!! }
    val existingRoasterIds = libRoasters.map { it.id }.toHashSet()
    val wireRoasterById = wireRoasters.associateBy { it.id }

    val newBeans = ArrayList<Bean>()
    val keptRoasterIds = HashSet<String>()
    val beanIdRemap = HashMap<String, String>()
    var skipped = 0
    for (bean in wireBeans) {
        val prior = existingById[bean.id] ?: bean.beanconquerorId?.let { existingByBcId[it] }
        if (prior != null) { beanIdRemap[bean.id] = prior.id; skipped++; continue }
        val wireRoaster = bean.roasterId?.let { wireRoasterById[it] }
        val libRoaster = wireRoaster?.name?.let { existingRoasterByLc[it.trim().lowercase()] }
        val resolved = when {
            libRoaster != null && wireRoaster.id != libRoaster.id -> bean.copy(roasterId = libRoaster.id)
            else -> { if (wireRoaster != null) keptRoasterIds.add(wireRoaster.id); bean }
        }
        val tripleDupe = libRoaster != null && libBeans.firstOrNull {
            it.roasterId == libRoaster.id &&
                it.name.trim().equals(bean.name.trim(), ignoreCase = true) &&
                (it.roastedOn ?: "") == (bean.roastedOn ?: "")
        }?.also { beanIdRemap[bean.id] = it.id } != null
        if (tripleDupe) { skipped++; continue }
        beanIdRemap[bean.id] = bean.id
        newBeans.add(resolved)
    }
    val newRoasters = wireRoasters.filter { it.id in keptRoasterIds && it.id !in existingRoasterIds }
    return BeanImportMerge(newBeans, newRoasters, beanIdRemap, skipped)
}
