package coffee.crema.beans

import coffee.crema.core.Bean
import coffee.crema.core.Roaster
import coffee.crema.core.RoasterDuplicate
import coffee.crema.core.RoasterMergePlan
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Roaster duplicate detection + merge (web Roasters tab parity). The rules are
 * the core's (`de1_domain::bean_sync`, via UniFFI) so both shells suggest and
 * merge identically:
 *
 *  - [detectRoasterDuplicates] — rows sharing a normalised name that aren't
 *    already tagged; the most recently updated row of each group is canonical.
 *  - [planRoasterMerge] — the bags to move before the dupe is tagged
 *    (`canonicalRoasterId = canonical`). Tagging keeps the row, so clearing
 *    the pointer un-merges it.
 *
 * The directory filter and the banner list below are plain shell logic.
 */

/** Probable duplicate pairs (core `detect_roaster_duplicates`). */
fun detectRoasterDuplicates(json: Json, roasters: List<Roaster>): List<RoasterDuplicate> =
    json.decodeFromString(
        ListSerializer(RoasterDuplicate.serializer()),
        coffee.crema.core.detectRoasterDuplicates(json.encodeToString(ListSerializer(Roaster.serializer()), roasters)),
    )

/** The merge plan, or null when it can't be done (core `plan_roaster_merge`). */
fun planRoasterMerge(json: Json, roasters: List<Roaster>, beans: List<Bean>, canonicalId: String, dupeId: String): RoasterMergePlan? {
    val payload = buildJsonObject {
        put("roasters", json.encodeToJsonElement(ListSerializer(Roaster.serializer()), roasters))
        put("beans", json.encodeToJsonElement(ListSerializer(Bean.serializer()), beans))
        put("canonicalId", canonicalId)
        put("dupeId", dupeId)
    }
    val out = coffee.crema.core.planRoasterMerge(payload.toString())
    return if (out == "null") null else json.decodeFromString(RoasterMergePlan.serializer(), out)
}

/** The Roasters directory: tagged duplicates hidden unless "Show dupes" is on (web default off). */
fun directoryRoasters(roasters: List<Roaster>, showDuplicates: Boolean): List<Roaster> =
    if (showDuplicates) roasters else roasters.filter { it.canonicalRoasterId == null }

/** A merge suggestion resolved to its rows, for the banner. */
data class MergeSuggestion(val canonical: Roaster, val dupe: Roaster, val bagCount: Int)

/**
 * The merge banners to show: each core suggestion resolved to its rows (a
 * stale id drops out), minus the ones dismissed with "Keep separate" this
 * session (keyed by the dupe id).
 */
fun mergeSuggestions(
    pairs: List<RoasterDuplicate>,
    roasters: List<Roaster>,
    beans: List<Bean>,
    dismissed: Set<String>,
): List<MergeSuggestion> {
    val byId = roasters.associateBy { it.id }
    return pairs.mapNotNull { p ->
        if (p.dupeId in dismissed) return@mapNotNull null
        val canonical = byId[p.canonicalId] ?: return@mapNotNull null
        val dupe = byId[p.dupeId] ?: return@mapNotNull null
        MergeSuggestion(canonical, dupe, beans.count { it.roasterId == dupe.id })
    }
}

// ── Roaster delete (web RoasterDeleteSplit parity) ───────────────────────────

/** The delete plan — detach or cascade + the Visualizer ids (core `plan_roaster_delete`); null = unknown roaster. */
fun planRoasterDelete(json: Json, roasters: List<Roaster>, beans: List<Bean>, roasterId: String, cascade: Boolean): coffee.crema.core.RoasterDeletePlan? {
    val payload = buildJsonObject {
        put("roasters", json.encodeToJsonElement(ListSerializer(Roaster.serializer()), roasters))
        put("beans", json.encodeToJsonElement(ListSerializer(Bean.serializer()), beans))
        put("roasterId", roasterId)
        put("cascade", cascade)
    }
    val out = coffee.crema.core.planRoasterDelete(payload.toString())
    return if (out == "null") null else json.decodeFromString(coffee.crema.core.RoasterDeletePlan.serializer(), out)
}

/**
 * Apply a roaster delete plan to the library: drop the roaster, delete the
 * cascaded bags, detach the rest (roaster cleared, stamped so the next sync
 * PATCHes it), and clear the active bag if it was deleted. Pure.
 */
fun applyRoasterDelete(library: BeanLibrary, plan: coffee.crema.core.RoasterDeletePlan, nowMs: Long): BeanLibrary {
    val deleted = plan.deletedBeanIds.toSet()
    val detached = plan.detachedBeanIds.toSet()
    return library.copy(
        roasters = library.roasters.filterNot { it.id == plan.roasterId },
        beans = library.beans
            .filterNot { it.id in deleted }
            .map { if (it.id in detached) it.copy(roasterId = null, updatedAt = nowMs) else it },
        activeBeanId = library.activeBeanId?.takeUnless { it in deleted },
    )
}

/**
 * Whether "also delete on Visualizer" has anything to remove: the roaster is
 * synced, or (for a cascade) one of its bags is (web `remoteAvailable`).
 */
fun roasterRemoteDeleteAvailable(roaster: Roaster, beans: List<Bean>, cascade: Boolean): Boolean =
    roaster.visualizerId != null || (cascade && beans.any { it.roasterId == roaster.id && it.visualizerId != null })
