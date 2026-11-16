package coffee.crema.restore

import coffee.crema.core.RestorePlan
import coffee.crema.history.ShotRow
import coffee.crema.history.ShotsRestore

/** Rows per merge batch on a store that takes batches (#310 P3a, R1a). */
internal const val RESTORE_BATCH = 250

/**
 * Step 7 on a store (P3 spec §7, SQLite SPEC §9, #310 R1a): merge, then
 * remove, through [to], P3a's `shots.db` or the SQLite store.
 *
 * Every row the plan leaves is written in batches, incoming rows over local
 * ones with the same id; then one explicit removal of the rows the plan
 * names that the merge left unlisted. A kill between leaves the union:
 * re-running the restore finishes it. A row that arrives meanwhile was never
 * in the plan, so it is never removed; on the SQLite store, the wipe also
 * takes only rows made before the run began (M3).
 *
 * The backup's ids, which the wipe leaves, are the plan's: in Replace every
 * backup shot is an overwrite, an add or a revive, and Merge removes nothing.
 * Returns the curves that failed, or null when the rows didn't land.
 */
internal suspend fun restoreShots(
    to: ShotsRestore,
    plan: RestorePlan,
    records: RestoreRecords,
    replace: Boolean,
    afterSeries: suspend () -> Unit = {},
    beforeRemove: suspend () -> Unit = {},
): Int? {
    fun next(local: List<ShotRow>) = nextShots(local, records.parsed.shots, plan.shots, replace, to::hasCurve, to::rowFor)
    val first = next(to.rows())
    // Series first, before any row names them.
    var failed = to.writeSeries(first.series)
    afterSeries()
    // Then over the rows as they are now.
    val second = to.settle(::next)
    // Their heavy parts wait for the merge (#310 P4a), staged only now: staged
    // before, they'd read as curves here and the recompute wouldn't fill them.
    second.series.forEach(to::stage)
    // Merge: every row, in batches, none deleted.
    for (batch in second.next.chunked(RESTORE_BATCH)) {
        if (!to.merge(batch)) return null
    }
    // A curve the recompute found anew: its file now, before done.
    val written = first.series.mapTo(HashSet()) { it.id }
    failed += to.writeSeries(second.series.filterNot { it.id in written })
    beforeRemove()
    // Then remove: the rows the plan names, and only those, if still not listed.
    val listed = second.next.mapTo(HashSet()) { it.id }
    val backupIds = with(plan.shots) { (overwrite + add + revive).toHashSet() }
    to.remove(plan.shots.remove.filterNot { it in listed }, backupIds)
    return failed
}
