package coffee.crema.profiles

/**
 * Profiles saved before Crema adopted the DE1's 20-step limit (cohort review
 * 2026-10-28, #6 #6). Mirrors the web `step-cap.ts`.
 *
 * Crema accepted up to 32 steps until 2026-10; the DE1 runs at most 20 (de1app
 * refuses a 21st step, pyDE1 `_MAX_SHOT_FRAMES = 20`), so the core refuses to
 * upload more. Such a profile stays in the library — readable, editable — and
 * is flagged: the library row and the editors say how many steps to remove,
 * the editors stop adding steps at the limit, and a start refuses with the
 * same message instead of a generic upload failure.
 */
object StepCap {
    /** The DE1's frame limit (core `MAX_PROFILE_STEPS`). */
    val max: Int get() = ProfileBounds.INSTANCE.maxProfileSteps

    /** How many steps over the limit a profile with [count] steps is (0 = fits). */
    fun excess(count: Int, limit: Int = max): Int = (count - limit).coerceAtLeast(0)

    /** Whether one more step may be added to a profile with [count] steps. */
    fun canAdd(count: Int, limit: Int = max): Boolean = count < limit

    /** Short library / editor note, or null when the profile fits. */
    fun note(count: Int, limit: Int = max): String? {
        val excess = excess(count, limit)
        if (excess == 0) return null
        return "$count steps — the DE1 runs at most $limit. Remove $excess to brew it."
    }

    /** Why a start with this profile is refused, or null when it fits. */
    fun refusal(name: String, count: Int, limit: Int = max): String? {
        val excess = excess(count, limit)
        if (excess == 0) return null
        val title = name.ifBlank { "Untitled profile" }
        return "“$title” has $count steps; the DE1 runs at most $limit. " +
            "Remove $excess in the profile editor to brew it."
    }
}
