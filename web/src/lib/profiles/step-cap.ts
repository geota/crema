/**
 * `#lib/profiles/step-cap.js` — profiles saved before Crema adopted the DE1's
 * 20-step limit (cohort review 2026-10-28, #6 #6).
 *
 * Crema accepted up to 32 steps until 2026-10; the DE1 runs at most 20 (de1app
 * refuses a 21st step, pyDE1 `_MAX_SHOT_FRAMES = 20`), so the core now refuses
 * to upload more. A library profile with 21–32 steps is kept as it is —
 * readable, editable, exportable — and flagged: the card and the editor say
 * how many steps to remove, and a start refuses with the same message instead
 * of a generic upload failure. Android's `StepCap.kt` mirrors this.
 */

import { ProfileBounds } from './bounds';

/** How many steps over the DE1's limit a profile with `count` steps is (0 = fits). */
export function excessSteps(count: number): number {
	return Math.max(0, count - ProfileBounds.MAX_PROFILE_STEPS);
}

/** Short card / editor note for an over-limit profile, or `null` when it fits. */
export function stepCapNote(count: number): string | null {
	const excess = excessSteps(count);
	if (excess === 0) return null;
	return (
		`${count} steps — the DE1 runs at most ${ProfileBounds.MAX_PROFILE_STEPS}. ` +
		`Remove ${excess} to brew it.`
	);
}

/** Why a start with this profile is refused, or `null` when it fits. */
export function stepCapRefusal(name: string, count: number): string | null {
	const excess = excessSteps(count);
	if (excess === 0) return null;
	return (
		`“${name || 'Untitled profile'}” has ${count} steps; the DE1 runs at most ` +
		`${ProfileBounds.MAX_PROFILE_STEPS}. Remove ${excess} in the profile editor to brew it.`
	);
}
