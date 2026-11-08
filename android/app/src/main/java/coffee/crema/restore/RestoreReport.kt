package coffee.crema.restore

import coffee.crema.core.PlanCode
import coffee.crema.core.PlanNote
import coffee.crema.core.RestoreMode
import coffee.crema.core.RestorePlan

/*
 * A restore plan in words (restore-safety design, "User-visible changes"),
 * worded as the web words it: the refusal, the report before a restore that
 * needs the user's yes, and the line after.
 */

private fun plural(n: Int, one: String, many: String = "${one}s") = "$n ${if (n == 1) one else many}"

private fun counts(vararg parts: Pair<Int, String>): String? {
    val named = parts.filter { it.first > 0 }.map { plural(it.first, it.second) }
    return when (named.size) {
        0 -> null
        1 -> named[0]
        else -> named.dropLast(1).joinToString(", ") + " and " + named.last()
    }
}

private val STORE_NAMES = mapOf(
    "shots" to "shot history", "beans" to "bean library", "profiles" to "custom profiles",
    "recipes" to "brew recipes", "settings" to "settings", "maintenance" to "maintenance log",
    "profileMeta" to "profile pins",
)

private fun RestorePlan.note(code: PlanCode): PlanNote? = (refusals + warnings).firstOrNull { it.code == code }

/** Why nothing was changed. */
fun refusalMessage(plan: RestorePlan): String {
    val first = plan.refusals.firstOrNull()
    val replace = plan.mode == RestoreMode.Replace
    return when (first?.code) {
        PlanCode.NotABackup -> "Not a Crema backup file."
        PlanCode.ShotsExport -> "This is a shots export: use Import."
        PlanCode.NewerFormat -> "This backup needs a newer version of Crema. Nothing was changed."
        PlanCode.StoreUnavailable, PlanCode.StoreLoading ->
            "Couldn't read your ${STORE_NAMES[first.store] ?: "data"} just now, so nothing was changed."
        PlanCode.MachineBusy -> "Finish the shot or firmware update first. Nothing was changed."
        PlanCode.BadLines, PlanCode.HeaderDamaged, PlanCode.CountMismatch -> {
            val bad = plan.note(PlanCode.BadLines)?.count?.toInt() ?: 0
            val lines = if (bad > 0) " (${plural(bad, "line")} can't be read)" else ""
            "This backup is damaged$lines, so nothing was changed." + if (replace) " Merge can still add what's readable." else ""
        }
        PlanCode.OverCapacity -> "This backup has ${first.count ?: 0u} shots; Crema keeps 300 for now. Nothing was changed."
        PlanCode.EmptyBackup ->
            "This backup has no shots, beans or profiles, so Replace would empty this device. Nothing was changed. Use Merge, or Erase all data first."
        else -> "This backup can't be restored. Nothing was changed."
    }
}

private fun warningLines(plan: RestorePlan): List<String> = plan.warnings.mapNotNull { w ->
    when (w.code) {
        PlanCode.BadLines -> "${plural(w.count?.toInt() ?: 0, "line")} in this backup can't be read and will be skipped."
        PlanCode.HeaderDamaged -> "This backup's first line is damaged; what's readable will be added."
        PlanCode.CountMismatch -> "This backup's ${STORE_NAMES[w.store] ?: "data"} isn't all there."
        PlanCode.NotAdded -> {
            val added = plan.shots.add.size
            if (added == 0) "History is full at 300 shots, so no shots will be added. None of yours are removed."
            else "Adds the newest $added of ${added + (w.count?.toInt() ?: 0)} shots: Crema keeps 300 for now. None of yours are removed."
        }
        PlanCode.Omitted -> "This backup left out ${plural(w.count?.toInt() ?: 0, "item")}."
        else -> null
    }
}

private fun sourceLine(plan: RestorePlan): String {
    val from = plan.source.deviceLabel.ifBlank { "Crema" }
    if (plan.source.createdAt <= 0) return from
    val at = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(plan.source.createdAt))
    return "$from, $at"
}

/** The report before a restore that needs the user's yes. */
fun confirmText(plan: RestorePlan): Pair<String, String> {
    if (plan.mode == RestoreMode.Replace) {
        val gone = counts(
            plan.shots.remove.size to "shot", plan.beans.remove.size to "bean",
            plan.profiles.remove.size to "profile", plan.recipes.remove.size to "recipe",
        )
        val pinned = if (plan.shots.pinnedRemoved > 0u) " (${plan.shots.pinnedRemoved} pinned)" else ""
        val set = counts(
            plan.shots.add.size + plan.shots.overwrite.size + plan.shots.revive.size to "shot",
            plan.beans.add.size + plan.beans.overwrite.size to "bean",
            plan.profiles.add.size + plan.profiles.overwrite.size to "profile",
        )
        return "Replace everything on this device?" to listOfNotNull(
            "From ${sourceLine(plan)}.",
            gone?.let { "Deletes $it$pinned that aren't in this backup." },
            set?.let { "Sets up $it." },
            *warningLines(plan).toTypedArray(),
            "This can't be undone. Type ERASE to confirm.",
        ).joinToString(" ")
    }
    val adds = counts(
        plan.shots.add.size + plan.shots.revive.size to "shot", plan.beans.add.size to "bean",
        plan.roasters.add.size to "roaster", plan.profiles.add.size to "profile", plan.recipes.add.size to "recipe",
    )
    val keeps = counts(plan.shots.keep.toInt() to "shot", plan.beans.keep.toInt() to "bean")
    val deleted = plan.shots.deletedHere.size
    return "Restore from ${sourceLine(plan)}?" to listOfNotNull(
        adds?.let { "Adds $it." } ?: "Adds nothing new.",
        plan.shots.fill.size.takeIf { it > 0 }?.let { "Fills in ${plural(it, "missing curve")}." },
        keeps?.let { "Keeps $it and your settings as they are." },
        *warningLines(plan).toTypedArray(),
        if (deleted > 0) "${plural(deleted, "shot")} in this backup ${if (deleted == 1) "was" else "were"} deleted here and stay${if (deleted == 1) "s" else ""} deleted." else null,
    ).joinToString(" ")
}

private val STEP_NAMES = mapOf(
    RestoreStep.PHOTOS to "bean photos", RestoreStep.RECIPES to "recipes", RestoreStep.BEANS to "beans",
    RestoreStep.PROFILES to "profiles", RestoreStep.SHOTS to "shots", RestoreStep.SETTINGS to "settings",
)

/** The line after a restore, whichever way it ended. */
fun outcomeMessage(outcome: RestoreOutcome): String = when (outcome) {
    RestoreOutcome.Busy -> "A restore is already running."
    is RestoreOutcome.Refused -> refusalMessage(outcome.plan)
    is RestoreOutcome.Changed -> "This device changed while you were deciding. Here are the new numbers."
    is RestoreOutcome.Unconfirmed -> "Nothing was changed: this restore needs your yes first."
    is RestoreOutcome.Stopped -> {
        val landed = outcome.landed.mapNotNull { STEP_NAMES[it] }
        "Restore stopped: storage may be full." +
            (if (landed.isNotEmpty()) " ${landed.joinToString(", ")} were restored; the rest weren't." else "") +
            " Free up space and run it again."
    }
    is RestoreOutcome.Done -> {
        val p = outcome.plan
        val added = counts(
            p.shots.add.size + p.shots.revive.size + p.shots.overwrite.size to "shot",
            p.beans.add.size + p.beans.overwrite.size to "bean",
            p.profiles.add.size + p.profiles.overwrite.size to "profile",
            p.recipes.add.size + p.recipes.overwrite.size to "recipe",
        )
        listOfNotNull(
            added?.let { "Restored $it." } ?: "Restored nothing new: it's all here already.",
            p.shots.keep.toInt().takeIf { p.mode == RestoreMode.Merge && it > 0 }
                ?.let { "${plural(it, "shot")} already here ${if (it == 1) "was" else "were"} kept." },
            p.shots.fill.size.takeIf { it > 0 }?.let { "Filled in ${plural(it, "curve")}." },
            p.shots.notAdded.toInt().takeIf { it > 0 }?.let { left ->
                val added = p.shots.add.size
                if (added == 0) "History is full at 300 shots, so no shots were added. None of yours were removed."
                else "Added the newest $added of ${added + left} shots: Crema keeps 300 for now. None of yours were removed."
            },
            outcome.curvesFailed.takeIf { it > 0 }?.let { "${plural(it, "curve")} couldn't be saved." },
            if (outcome.singletonsSkipped) "A shot was still running, so settings and maintenance weren't changed. Run it again to apply them." else null,
        ).joinToString(" ")
    }
}
