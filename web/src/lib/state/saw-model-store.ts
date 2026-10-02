/**
 * Persistence for the core's learned SAW drip model
 * (`de1_domain::saw_learning`) — an opaque core-owned JSON blob in
 * localStorage, seeded at boot and saved after every completed shot.
 *
 * A stored blob that no longer parses is QUARANTINED rather than lost (port
 * of Decenza 95146a0c, `loadSawMap`): the core starts a fresh model and hands
 * back the raw text, and this module copies it to
 * {@link SAW_MODEL_QUARANTINE_KEY} (with an ISO timestamp beside it at
 * {@link SAW_MODEL_QUARANTINE_AT_KEY}) before clearing the store, so the next
 * save cannot overwrite the only copy. Newest capture wins, as in Decenza.
 * No UI: the bytes are kept for manual recovery and a warning is logged.
 */
import type { SawModelLoad } from '$lib/core/crema-core';

/** localStorage key for the persisted model blob. */
export const SAW_MODEL_KEY = 'crema.saw-model.v1';
/** Where a blob that failed to parse is kept aside (newest capture wins). */
export const SAW_MODEL_QUARANTINE_KEY = `${SAW_MODEL_KEY}.corrupt`;
/** ISO-8601 time the quarantined blob was captured. */
export const SAW_MODEL_QUARANTINE_AT_KEY = `${SAW_MODEL_KEY}.corruptAt`;

/** The one core call the seed needs. */
export interface SawModelLoader {
	loadSawModelJson(json: string | null): Promise<SawModelLoad>;
}

/**
 * The stored blob as the core should see it. The store holds the model JSON
 * wrapped as a JSON string (`writeJson`); if that outer layer is itself
 * damaged the raw text is passed through unchanged, so the core reports it
 * Corrupt instead of it reading as Absent.
 */
export function readSawModelBlob(): string | null {
	if (typeof localStorage === 'undefined') return null;
	let raw: string | null;
	try {
		raw = localStorage.getItem(SAW_MODEL_KEY);
	} catch {
		return null;
	}
	if (raw == null) return null;
	try {
		const inner: unknown = JSON.parse(raw);
		return typeof inner === 'string' ? inner : raw;
	} catch {
		return raw;
	}
}

/**
 * Seed the core's SAW model from localStorage, quarantining a corrupt blob.
 * Returns the core's verdict.
 */
export async function seedSawModel(
	core: SawModelLoader,
	now: () => Date = () => new Date()
): Promise<SawModelLoad> {
	const outcome = await core.loadSawModelJson(readSawModelBlob());
	if (outcome.type === 'Corrupt') {
		quarantineSawModel(outcome.content.raw, outcome.content.error, now());
	}
	return outcome;
}

/** Keep `raw` aside, then clear the store — only once the copy is safe. */
function quarantineSawModel(raw: string, error: string, at: Date): void {
	try {
		localStorage.setItem(SAW_MODEL_QUARANTINE_KEY, raw);
		localStorage.setItem(SAW_MODEL_QUARANTINE_AT_KEY, at.toISOString());
		localStorage.removeItem(SAW_MODEL_KEY);
		console.warn(
			`[SAW] Corrupt ${SAW_MODEL_KEY}: ${error} — ${raw.length} chars quarantined at ` +
				`${SAW_MODEL_QUARANTINE_KEY}, learning starts fresh`
		);
	} catch {
		// Quota / unavailable storage: never delete the original when no copy
		// was made — it is still the only one.
		console.warn(`[SAW] Corrupt ${SAW_MODEL_KEY}: ${error} — quarantine write failed`);
	}
}
