/**
 * `#lib/core/bounds` — the stepper and editor ranges, from the core's one
 * table (`de1_domain::bounds`) so the web shell, Android and the core's own
 * enforcement can't disagree: Quick Controls, Settings → Brew defaults and
 * the profile editor. Hot water is `hotWaterBoundsJson`; the brew-temp range
 * for a profile is the core's `brew_temp_range`.
 *
 * Parsed once on first read (after the wasm has loaded), like `ProfileBounds`.
 * The profile editor's ranges apply when the user edits a field (the steppers
 * clamp on a press); a stored profile outside them shows its own value.
 */

import type {
	BrewDefaultsBounds,
	ProfileEditorBounds,
	QuickControlBounds
} from '#lib/core/crema-core.js';
import {
	brewDefaultsBoundsJson,
	profileEditorBoundsJson,
	quickControlBoundsJson
} from '#lib/wasm/de1_wasm.js';

let qc: QuickControlBounds | null = null;
let defaults: BrewDefaultsBounds | null = null;
let editor: ProfileEditorBounds | null = null;

/** The Quick Controls steppers' ranges. */
export function quickControlBounds(): QuickControlBounds {
	return (qc ??= JSON.parse(quickControlBoundsJson()) as QuickControlBounds);
}

/** Settings → Brew defaults' ranges. */
export function brewDefaultsBounds(): BrewDefaultsBounds {
	return (defaults ??= JSON.parse(brewDefaultsBoundsJson()) as BrewDefaultsBounds);
}

/** The profile editor's ranges. */
export function profileEditorBounds(): ProfileEditorBounds {
	return (editor ??= JSON.parse(profileEditorBoundsJson()) as ProfileEditorBounds);
}
