package coffee.crema.restore

import coffee.crema.core.ShotPlan
import coffee.crema.core.StorePlan
import coffee.crema.history.StoredShot

/** `local − remove + overwrite + add`, from the backup's rows. First of a repeated id wins. */
fun <T> nextRows(local: List<T>, backup: List<T>, plan: StorePlan, id: (T) -> String): List<T> {
    val theirs = LinkedHashMap<String, T>()
    for (r in backup) theirs.putIfAbsent(id(r), r)
    val remove = plan.remove.toHashSet()
    val overwrite = plan.overwrite.toHashSet()
    val kept = local.filterNot { id(it) in remove }.map { r -> if (id(r) in overwrite) theirs[id(r)] ?: r else r }
    val here = local.mapTo(HashSet(), id)
    val added = plan.add.filterNot { it in here }.mapNotNull { theirs[it] }
    return added + kept
}

/** The history a plan leaves, and the rows whose curve to write. */
class NextShots(val next: List<StoredShot>, val series: List<StoredShot>)

/**
 * The history a plan leaves over [local]. Merge keeps local rows and fills a
 * lost curve; a shot on a bean already here pays nothing from this bag; a shot
 * deleted here that the plan revives comes back as the backup's row. A row
 * this device made meanwhile with a backup row's id is kept, once.
 */
fun nextShots(local: List<StoredShot>, backup: List<StoredShot>, plan: ShotPlan): NextShots {
    val theirs = LinkedHashMap<String, StoredShot>()
    for (s in backup) theirs.putIfAbsent(s.id, s)
    val remove = plan.remove.toHashSet()
    val replaced = (plan.overwrite + plan.revive).toHashSet()
    val fill = plan.fill.toHashSet()
    val zero = plan.zeroDebit.toHashSet()
    val series = mutableListOf<StoredShot>()
    val kept = local.filterNot { it.id in remove }.map { s ->
        val b = theirs[s.id] ?: return@map s
        when {
            s.id in replaced -> b.also { series += it }
            s.id in fill && s.samples.isEmpty() && b.samples.isNotEmpty() ->
                // The backfill rule: the curve only, never over a curve here.
                s.copy(samples = b.samples, durationMs = if (s.durationMs > 0) s.durationMs else b.durationMs).also { series += it }
            else -> s
        }
    }
    val here = local.mapTo(HashSet()) { it.id }
    // Android keeps no row for a shot deleted here: a revived one is added.
    val added = (plan.add + plan.revive.filterNot { it in here }).filterNot { it in here }.distinct().mapNotNull { theirs[it] }
        .map { if (it.id in zero) it.copy(bagDebitG = 0f) else it }
    series += added
    return NextShots((added + kept).sortedByDescending { it.completedAtMs }, series)
}
