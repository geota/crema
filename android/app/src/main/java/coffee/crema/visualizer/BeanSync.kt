package coffee.crema.visualizer

import coffee.crema.core.Bean
import coffee.crema.core.BeanPushItem
import coffee.crema.core.BeanSyncScope
import coffee.crema.core.RoasterPushItem
import coffee.crema.core.Roaster
import coffee.crema.core.RoasterLinkPatch
import coffee.crema.ui.UploadTargetId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/*
 * Visualizer bean / roaster sync — the Android port of the web `BeanSync.runSync`
 * (`web/src/lib/services/bean-sync.ts`).
 *
 * Order (identical to the web):
 *   0. core `bean_sync_scope` turns the two direction settings into the legs
 *      that run (backup = push only, pull = pull only, two-way = both);
 *   1. pull every remote roaster (paginated) → core `reconcile_roasters` →
 *      apply (`merge_pulled_roaster` for update / bind, `roaster_from_wire` for add);
 *   2. push local roasters per core `plan_roaster_push` (Premium-gated);
 *   2b. PATCH the catalogue link onto bound roasters whose remote row lacks it
 *      (core `plan_roaster_link_patches`);
 *   3. pull every remote bag (the full detail for bags new here — list rows
 *      are thin) → core `reconcile_beans` merges by key presence → apply;
 *   4. push bags per core `plan_bean_push` (unbound → POST, edited since the
 *      last sync → PATCH);
 *   5. stamp the sync time and the Premium flag.
 *
 * Last-write-wins against the previous sync (core reconcilers): a row edited
 * here since then keeps its edit and is pushed; otherwise the remote copy is
 * applied, keeping the local `updatedAt` so the pull never reads as an edit. Visualizer
 * bag / roaster WRITES are Premium-only: a cached `premium == false` starts the
 * run read-only (pull only, creates counted as skipped); the first 402/403
 * mid-run downshifts it the same way and logs one "Premium required" line.
 * The write bodies are the core's (`coffee_bag_write_request` /
 * `roaster_write_request`): catalogue links omitted when empty, the local
 * duplicate-of pointer never sent.
 *
 * Sans-store: [BeanSyncRunner.run] works on a snapshot of the library and
 * returns the rows it touched, which the caller merges back. The HTTP goes
 * through [call] (the caller's token-refreshing [VisualizerClient.request]);
 * the core goes through [BeanSyncCore] (a JVM fake in tests — the native core
 * isn't loadable there).
 */

/** The core calls the bean sync makes — JSON in / JSON out, exactly the FFI surface. */
interface BeanSyncCore {
    fun reconcileRoasters(payload: String): String
    fun reconcileBeans(payload: String): String
    fun roasterFromWire(wireJson: String, fallbackId: String, nowMs: Long): String
    fun remoteIdsNeedingDetail(payload: String): String
    fun remoteRoasterUnlinked(remoteJson: String): Boolean
    fun coffeeBagWriteRequest(beanJson: String, roasterRemoteId: String?): String
    fun roasterWriteRequest(roasterJson: String): String
    fun resolveRoasterCatalogueLink(payload: String): String?
    fun mergePulledRoaster(localJson: String, remoteJson: String, refresh: Boolean, lastSyncAt: Long?): String
    fun planBeanPush(payload: String): String
    fun planRoasterPush(payload: String): String
    fun planRoasterLinkPatches(payload: String): String
    fun beanSyncScope(beansDirection: String, roastersDirection: String): String

    /** The production implementation — the Rust core over UniFFI. */
    object Native : BeanSyncCore {
        override fun reconcileRoasters(payload: String) = coffee.crema.core.reconcileRoasters(payload)
        override fun reconcileBeans(payload: String) = coffee.crema.core.reconcileBeans(payload)
        override fun roasterFromWire(wireJson: String, fallbackId: String, nowMs: Long) =
            coffee.crema.core.roasterFromWire(wireJson, fallbackId, nowMs)
        override fun remoteIdsNeedingDetail(payload: String) = coffee.crema.core.remoteIdsNeedingDetail(payload)
        override fun remoteRoasterUnlinked(remoteJson: String) = coffee.crema.core.remoteRoasterUnlinked(remoteJson)
        override fun coffeeBagWriteRequest(beanJson: String, roasterRemoteId: String?) =
            coffee.crema.core.coffeeBagWriteRequest(beanJson, roasterRemoteId)
        override fun roasterWriteRequest(roasterJson: String) = coffee.crema.core.roasterWriteRequest(roasterJson)
        override fun resolveRoasterCatalogueLink(payload: String) = coffee.crema.core.resolveRoasterCatalogueLink(payload)
        override fun mergePulledRoaster(localJson: String, remoteJson: String, refresh: Boolean, lastSyncAt: Long?) =
            coffee.crema.core.mergePulledRoaster(localJson, remoteJson, refresh, lastSyncAt)
        override fun planBeanPush(payload: String) = coffee.crema.core.planBeanPush(payload)
        override fun planRoasterPush(payload: String) = coffee.crema.core.planRoasterPush(payload)
        override fun planRoasterLinkPatches(payload: String) = coffee.crema.core.planRoasterLinkPatches(payload)
        override fun beanSyncScope(beansDirection: String, roastersDirection: String) =
            coffee.crema.core.beanSyncScope(beansDirection, roastersDirection)
    }
}

/** The persisted bookkeeping a run reads (web `VisualizerSyncSettings`). */
data class BeanSyncSettings(
    /** Unix ms of the last successful bean sync; bound bags edited after it are PATCHed. */
    val lastSyncAt: Long?,
    /** Cached Premium tier: false = free (writes skipped), null = not probed yet. */
    val premium: Boolean?,
    /** Beans / roasters directions (`off | backup | pull | two-way`) — which legs run. */
    val beansDirection: String = "two-way",
    val roastersDirection: String = "two-way",
)

/** The aggregate outcome of one run (web `SyncResult`), plus the rows it touched. */
data class BeanSyncResult(
    val ok: Boolean = false,
    val pulled: Int = 0,
    val pushed: Int = 0,
    val skipped: Int = 0,
    /** True when the run ended read-only (free tier). */
    val premiumLocked: Boolean = false,
    /** Activity lines in run order (web `SyncResult.log`). */
    val log: List<SyncLogEntry> = emptyList(),
    /** The run-ending failure (auth / network on a pull), or a write's last error. */
    val error: String? = null,
    /** Every bean / roaster the run created or changed, in its final form. */
    val beans: List<Bean> = emptyList(),
    val roasters: List<Roaster> = emptyList(),
    /** The Premium flag to cache afterwards (unchanged when the run never learned anything). */
    val premium: Boolean? = null,
    /** The new last-sync stamp, or null when the run didn't complete. */
    val lastSyncAt: Long? = null,
)

/** One run of the bidirectional bean / roaster sync. Stateless between runs. */
class BeanSyncRunner(
    private val core: BeanSyncCore,
    private val json: Json,
    /** One authenticated Visualizer request (method, path, body) → parsed body; throws [VisualizerError]. */
    private val call: suspend (method: String, path: String, body: JsonElement?) -> JsonElement?,
    private val now: () -> Long = System::currentTimeMillis,
    private val mintBeanId: () -> String = { "bean:" + UUID.randomUUID() },
    private val mintRoasterId: () -> String = { "roaster:" + UUID.randomUUID() },
) {
    companion object {
        /** Pull safety cap — the web's 50 pages of `items=100` (the spec max). */
        const val MAX_PAGES = 50
        const val PAGE_SIZE = 100
        /** Concurrent `GET /…/{id}` detail fetches (list rows are thin). */
        const val DETAIL_CONCURRENCY = 4
        const val PREMIUM_BANNER =
            "Premium required — beans + roasters disabled from push. Upgrade at visualizer.coffee/premium."
    }

    private val beanList = ListSerializer(Bean.serializer())
    private val roasterList = ListSerializer(Roaster.serializer())

    suspend fun run(beans: List<Bean>, roasters: List<Roaster>, settings: BeanSyncSettings): BeanSyncResult =
        Run(beans, roasters, settings).execute()

    /** The mutable state of one run — a working copy of the library + the tallies. */
    private inner class Run(beans0: List<Bean>, roasters0: List<Roaster>, val settings: BeanSyncSettings) {
        val beans = beans0.toMutableList()
        val roasters = roasters0.toMutableList()
        val changedBeans = LinkedHashSet<String>()
        val changedRoasters = LinkedHashSet<String>()
        val log = mutableListOf<SyncLogEntry>()
        var pulled = 0
        var pushed = 0
        var skipped = 0
        var error: String? = null
        var premium: Boolean? = settings.premium
        var premiumLocked = settings.premium == false
        var bannerLogged = settings.premium == false

        fun entry(direction: String, entity: String, id: String, name: String, err: String? = null) {
            log += SyncLogEntry(UploadTargetId.Visualizer, direction, entity, id, name, now(), err)
        }

        fun roaster(id: String?) = id?.let { r -> roasters.firstOrNull { it.id == r } }
        fun bean(id: String) = beans.firstOrNull { it.id == id }

        /** Replace (or prepend) a roaster, stamping `updatedAt` like the web store. */
        /**
         * Replace (or prepend) a roaster exactly as given. Rows applied from a
         * pull keep the stamp the core hands back — a pull is not a local edit,
         * and re-stamping it would make the next sync push it straight back.
         * [stamp] = a local change made by this run (binding a pushed row's id).
         */
        fun putRoaster(r: Roaster, stamp: Boolean = false) {
            val next = if (stamp) r.copy(updatedAt = now()) else r
            val i = roasters.indexOfFirst { it.id == r.id }
            if (i >= 0) roasters[i] = next else roasters.add(0, next)
            changedRoasters += r.id
        }

        fun putBean(b: Bean, stamp: Boolean = false) {
            val next = if (stamp) b.copy(updatedAt = now()) else b
            val i = beans.indexOfFirst { it.id == b.id }
            if (i >= 0) beans[i] = next else beans.add(0, next)
            changedBeans += b.id
        }

        fun lockPremium() {
            premiumLocked = true
            premium = false
            if (!bannerLogged) {
                bannerLogged = true
                entry("skip", "bean", "", "Premium required", PREMIUM_BANNER)
            }
        }

        suspend fun execute(): BeanSyncResult {
            var ok = false
            try {
                program()
                ok = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e)
            }
            val finalPremium = when {
                ok -> if (premiumLocked) false else (settings.premium ?: true)
                else -> premium
            }
            return BeanSyncResult(
                ok = ok,
                pulled = pulled,
                pushed = pushed,
                skipped = skipped,
                premiumLocked = premiumLocked,
                log = log.toList(),
                error = error,
                beans = beans.filter { it.id in changedBeans },
                roasters = roasters.filter { it.id in changedRoasters },
                premium = finalPremium,
                lastSyncAt = if (ok) now() else null,
            )
        }

        suspend fun program() {
            // Which legs run (core `bean_sync_scope`): backup pushes only, pull
            // pulls only and never writes remote, two-way both, off neither.
            val scope = json.decodeFromString(
                BeanSyncScope.serializer(),
                core.beanSyncScope(settings.beansDirection, settings.roastersDirection),
            )
            val lastSync = settings.lastSyncAt
            // Rows this run took from the remote (added roasters, every applied
            // bag): they already match it, so the push legs skip them.
            val pulledRoasterIds = mutableListOf<String>()
            val pulledBeanIds = mutableListOf<String>()
            // Remote → local roaster ids for the bag decode, seeded from the
            // already-bound roasters so a beans-only pull still files bags.
            val remoteToLocal = HashMap<String, String>()
            roasters.forEach { r -> r.visualizerId?.let { remoteToLocal[it] = r.id } }
            val unlinked = mutableListOf<String>()

            // 1) Pull remote roasters → reconcile (core) → apply.
            if (scope.pullRoasters) {
                // The list is thin (id + name): the full detail for roasters new to
                // this device; bound ones merge the summary only.
                val remoteRoasters = withDetails("/roasters", pullPaged("/roasters"), roasterList, roasters) { id, why ->
                    entry("pull", "roaster", id, "Roaster details", why)
                }
                val roasterActions = json.parseToJsonElement(
                    core.reconcileRoasters(
                        buildJsonObject {
                            put("local", json.encodeToJsonElement(roasterList, roasters))
                            put("remote", JsonArray(remoteRoasters))
                        }.toString(),
                    ),
                ) as JsonArray
                for (el in roasterActions) {
                    val action = el as? JsonObject ?: continue
                    val wire = action["remote"] as? JsonObject ?: continue
                    val remoteId = wire.str("id") ?: continue
                    val name = wire.str("name").orEmpty()
                    // Only a row that CARRIES an empty link is known to be unlinked.
                    if (core.remoteRoasterUnlinked(wire.toString())) unlinked += remoteId
                    when (val kind = action.str("kind")) {
                        "update", "bind" -> {
                            val localId = action.str("localId") ?: continue
                            roaster(localId)?.let { local ->
                                // Core merge: an update takes the remote fields unless the
                                // local was edited since the last sync; updatedAt is kept.
                                putRoaster(
                                    json.decodeFromString(
                                        Roaster.serializer(),
                                        core.mergePulledRoaster(
                                            json.encodeToString(Roaster.serializer(), local),
                                            wire.toString(),
                                            kind == "update",
                                            lastSync,
                                        ),
                                    ),
                                )
                            }
                            // Not skipped by the push leg: a row edited here since the
                            // last sync kept its edit, and that must push.
                            remoteToLocal[remoteId] = localId
                            if (kind == "bind") entry("pull", "roaster", localId, name)
                        }
                        else -> {
                            val fresh = json.decodeFromString(
                                Roaster.serializer(),
                                core.roasterFromWire(wire.toString(), mintRoasterId(), now()),
                            )
                            putRoaster(fresh)
                            remoteToLocal[remoteId] = fresh.id
                            pulledRoasterIds += fresh.id
                            pulled++
                            entry("pull", "roaster", fresh.id, name)
                        }
                    }
                }
            }

            // 2) Push roasters (Premium-gated, core plan): unbound → POST, edited since the last sync → PATCH.
            val pushedRoasterIds = HashSet<String>()
            if (scope.pushRoasters) {
                val plan = json.decodeFromString(
                    ListSerializer(RoasterPushItem.serializer()),
                    core.planRoasterPush(
                        buildJsonObject {
                            put("roasters", json.encodeToJsonElement(roasterList, roasters))
                            lastSync?.let { put("lastSyncAt", it) }
                            put("skipIds", buildJsonArray { pulledRoasterIds.forEach { add(JsonPrimitive(it)) } })
                        }.toString(),
                    ),
                )
                for (item in plan) {
                    val local = roaster(item.localId) ?: continue
                    if (premiumLocked) {
                        if (item.create) {
                            skipped++
                            entry("skip", "roaster", local.id, local.name, "premium required")
                        }
                        continue
                    }
                    try {
                        val vid = uploadRoaster(withCatalogueLink(local))
                        if (item.create) {
                            putRoaster(local.copy(visualizerId = vid), stamp = true)
                            remoteToLocal[vid] = local.id
                        }
                        pushedRoasterIds += local.id
                        pushed++
                        premium = true
                        entry("push", "roaster", local.id, local.name)
                    } catch (e: VisualizerError.PremiumGated) {
                        lockPremium()
                        entry("skip", "roaster", local.id, local.name, "premium required")
                    } catch (e: VisualizerError) {
                        error = describe(e)
                        entry("skip", "roaster", local.id, local.name, error)
                    }
                }

                // 2b) Link already-synced roasters to the catalogue (core plan).
                val patches = json.decodeFromString(
                    ListSerializer(RoasterLinkPatch.serializer()),
                    core.planRoasterLinkPatches(
                        buildJsonObject {
                            put("roasters", json.encodeToJsonElement(roasterList, roasters))
                            put("beans", json.encodeToJsonElement(beanList, beans))
                            put("unlinkedRemoteIds", buildJsonArray { unlinked.forEach { add(JsonPrimitive(it)) } })
                        }.toString(),
                    ),
                )
                for (patch in patches) {
                    if (premiumLocked) break
                    if (patch.localId in pushedRoasterIds) continue
                    val local = roaster(patch.localId) ?: continue
                    try {
                        uploadRoaster(local.copy(catalogueRoasterId = patch.catalogueRoasterId))
                        if (local.catalogueRoasterId.isNullOrEmpty()) {
                            // updatedAt kept: the link now matches the remote.
                            putRoaster(local.copy(catalogueRoasterId = patch.catalogueRoasterId))
                        }
                        pushed++
                        entry("push", "roaster", local.id, local.name)
                    } catch (e: VisualizerError) {
                        if (e is VisualizerError.PremiumGated) lockPremium()
                        entry("skip", "roaster", local.id, local.name, describe(e))
                    }
                }
            }

            // 3) Pull remote bags → decode (core) → reconcile against the last-sync baseline (core) → apply.
            if (scope.pullBeans) {
                // The list is thin (id, name, roaster, catalogue link): the full detail
                // for bags new to this device; bound bags merge the summary only.
                val remoteBags = withDetails("/coffee_bags", pullPaged("/coffee_bags"), beanList, beans) { id, why ->
                    entry("pull", "bean", id, "Bag details", why)
                }
                // RAW rows go to the core (key presence matters: an absent key never
                // clears a local value); it decodes new bags and merges matched ones.
                val beanActions = json.parseToJsonElement(
                    core.reconcileBeans(
                        buildJsonObject {
                            put("local", json.encodeToJsonElement(beanList, beans))
                            put(
                                "remote",
                                buildJsonArray {
                                    remoteBags.forEach { wire ->
                                        add(
                                            buildJsonObject {
                                                put("wire", wire)
                                                wire.str("roaster_id")?.let { remoteToLocal[it] }?.let { put("localRoasterId", it) }
                                                put("fallbackId", mintBeanId())
                                            },
                                        )
                                    }
                                },
                            )
                            put("roasterNames", buildJsonObject { roasters.forEach { put(it.id, it.name) } })
                            lastSync?.let { put("lastSyncAt", it) }
                            put("nowMs", now())
                        }.toString(),
                    ),
                ) as JsonArray
                for (el in beanActions) {
                    val action = el as? JsonObject ?: continue
                    val remote = action["remote"]?.let { json.decodeFromJsonElement(Bean.serializer(), it) } ?: continue
                    // Stored as the core hands it back (a replace keeps the local id + updatedAt).
                    putBean(remote)
                    pulledBeanIds += remote.id
                    if (action.str("kind") == "replace") {
                        entry("pull", "bean", remote.id, remote.name)
                    } else {
                        pulled++
                        entry("pull", "bean", remote.id, remote.name)
                    }
                }
            }

            // 4) Push bags per the core plan (unbound → POST, edited since the last sync → PATCH), minus step 3's pulls.
            if (scope.pushBeans) {
                val plan = json.decodeFromString(
                    ListSerializer(BeanPushItem.serializer()),
                    core.planBeanPush(
                        buildJsonObject {
                            put("beans", json.encodeToJsonElement(beanList, beans))
                            lastSync?.let { put("lastSyncAt", it) }
                            put("skipIds", buildJsonArray { pulledBeanIds.forEach { add(JsonPrimitive(it)) } })
                        }.toString(),
                    ),
                )
                for (item in plan) {
                    val local = bean(item.localId) ?: continue
                    if (premiumLocked) {
                        if (item.create) {
                            skipped++
                            entry("skip", "bean", local.id, local.name, "premium required")
                        }
                        continue
                    }
                    val remoteRoasterId = roaster(local.roasterId)?.visualizerId
                    try {
                        val vid = uploadBean(local, remoteRoasterId)
                        if (item.create) putBean(local.copy(visualizerId = vid), stamp = true)
                        pushed++
                        entry("push", "bean", local.id, local.name)
                    } catch (e: VisualizerError.PremiumGated) {
                        lockPremium()
                        entry("skip", "bean", local.id, local.name, if (item.create) "premium required" else describe(e))
                    } catch (e: VisualizerError) {
                        entry("skip", "bean", local.id, local.name, describe(e))
                    }
                }
            }
        }

        /** The roaster with its catalogue link resolved for a write (core). */
        fun withCatalogueLink(r: Roaster): Roaster {
            val link = core.resolveRoasterCatalogueLink(
                buildJsonObject {
                    put("roaster", json.encodeToJsonElement(Roaster.serializer(), r))
                    put("beans", json.encodeToJsonElement(beanList, beans))
                }.toString(),
            )
            return if (link != null && link != r.catalogueRoasterId) r.copy(catalogueRoasterId = link) else r
        }
    }

    /** `GET base?items=100&page=N` until the last page / an empty page / the cap. */
    /**
     * Swap each list row with no locally-bound counterpart (core
     * `remote_ids_needing_detail`) for its `GET base/{id}` detail, at most
     * [DETAIL_CONCURRENCY] at a time on the client's retry policy. A failed
     * fetch keeps the summary row (the merge applies only what it carries),
     * reports through [onFailure], and never fails the run.
     */
    private suspend fun <T> withDetails(
        base: String,
        rows: List<JsonObject>,
        localSerializer: kotlinx.serialization.KSerializer<List<T>>,
        local: List<T>,
        onFailure: (id: String, why: String) -> Unit,
    ): List<JsonObject> {
        val wanted = json.decodeFromString(
            ListSerializer(String.serializer()),
            core.remoteIdsNeedingDetail(
                buildJsonObject {
                    put("local", json.encodeToJsonElement(localSerializer, local))
                    put("remote", JsonArray(rows))
                }.toString(),
            ),
        ).toSet()
        if (wanted.isEmpty()) return rows
        val gate = kotlinx.coroutines.sync.Semaphore(DETAIL_CONCURRENCY)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val out = kotlinx.coroutines.coroutineScope {
            rows.map { row ->
                val id = row.str("id")
                if (id == null || id !in wanted) {
                    kotlinx.coroutines.CompletableDeferred(row)
                } else {
                    async {
                        gate.withPermit {
                            try {
                                val detail = call("GET", "$base/$id", null) as? JsonObject
                                if (detail?.str("id") == id) detail else row.also { failures += id to "unexpected detail body" }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failures += id to (e.message ?: e.javaClass.simpleName)
                                row
                            }
                        }
                    }
                }
            }.map { it.await() }
        }
        failures.forEach { (id, why) -> onFailure(id, why) }
        return out
    }

    private suspend fun pullPaged(base: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var page = 1
        while (page <= MAX_PAGES) {
            val body = call("GET", "$base?items=$PAGE_SIZE&page=$page", null) as? JsonObject
            val data = (body?.get("data") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            out += data
            val pages = (body?.get("paging") as? JsonObject)?.get("pages")?.let { (it as? JsonPrimitive)?.intOrNull } ?: page
            if (page >= pages || data.isEmpty()) break
            page++
        }
        return out
    }

    /** Create or update a bag; returns its Visualizer id. */
    private suspend fun uploadBean(bean: Bean, remoteRoasterId: String?): String {
        val body = json.parseToJsonElement(
            core.coffeeBagWriteRequest(json.encodeToString(Bean.serializer(), bean), remoteRoasterId),
        )
        bean.visualizerId?.let { vid ->
            call("PATCH", "/coffee_bags/$vid", body)
            return vid
        }
        return (call("POST", "/coffee_bags", body) as? JsonObject)?.str("id")
            ?: throw VisualizerError.Network("Visualizer accepted the bag but returned no id.")
    }

    /** Create or update a roaster; returns its Visualizer id. */
    private suspend fun uploadRoaster(roaster: Roaster): String {
        val body = json.parseToJsonElement(core.roasterWriteRequest(json.encodeToString(Roaster.serializer(), roaster)))
        roaster.visualizerId?.let { vid ->
            call("PATCH", "/roasters/$vid", body)
            return vid
        }
        return (call("POST", "/roasters", body) as? JsonObject)?.str("id")
            ?: throw VisualizerError.Network("Visualizer accepted the roaster but returned no id.")
    }

    private fun describe(e: Exception): String = when (e) {
        is VisualizerError.Auth -> "Not signed in to Visualizer"
        is VisualizerError.PremiumGated -> "premium required"
        else -> e.message ?: e.javaClass.simpleName
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/**
 * Merge a run's touched rows back into the live library: each touched row
 * replaces its current version (by id), and a touched row that was not in the
 * run's starting [snapshotIds] is a pulled addition (prepended, like the web
 * store's upsert). A snapshot row the library no longer holds was deleted by
 * the user mid-run and stays deleted.
 */
fun <T> mergeSyncedRows(current: List<T>, synced: List<T>, snapshotIds: Set<String>, id: (T) -> String): List<T> {
    val byId = synced.associateBy(id)
    val present = current.mapTo(HashSet(), id)
    val added = synced.filter { id(it) !in snapshotIds && id(it) !in present }
    return added + current.map { byId[id(it)] ?: it }
}
