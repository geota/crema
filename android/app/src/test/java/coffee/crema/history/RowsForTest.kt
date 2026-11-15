package coffee.crema.history

/*
 * Rows for tests (#310 P4a): a whole [StoredShot] as the History row the app
 * would hold, its summary worked out here (no core, so no spark).
 */

/** This shot as its row. */
fun StoredShot.row(): ShotRow = toRow(summaryHere(this))

/** These shots as their rows. */
fun List<StoredShot>.rows(): List<ShotRow> = map { it.row() }

/** A backup's whole-shot read over [shots], as the store would answer it: each row's heavy parts from its shot. */
fun wholeFrom(shots: List<StoredShot>): (ShotRow) -> StoredShot {
    val byId = shots.associateBy { it.id }
    return { r -> byId[r.id]?.let(r::joined) ?: r.withHeavy(emptyList(), null, null) }
}
