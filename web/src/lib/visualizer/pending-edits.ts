/**
 * `#lib/visualizer/pending-edits.js` — what a History edit changed on a shot
 * already on Visualizer, until a PATCH carrying it lands.
 *
 * A PATCH sends only what is marked here: the journal body for `annotations`,
 * `barista` for a barista edit, and a tag list built from the server's own
 * (`tags: { add, remove }`, so a change made on the website isn't undone). A
 * pull reads the marks too: it never fills a barista or merges tags into a row
 * whose edit hasn't landed. The set is the PATCH retry list, walked by the
 * drain on `nextAttemptAt`. Android keeps the same set in
 * `VisualizerState.pendingEdits`.
 *
 * Reads go to storage each time, since another tab may have changed it. A mark
 * storage refused is kept in memory for this tab instead. The old
 * `baristaPending` id list migrates in on the first read.
 */

import type { TagMarks } from '#lib/core/crema-core.js';
import { markTagEdit as wasmMarkTagEdit } from '#lib/wasm/de1_wasm.js';
import { isJsonObject, readJson, writeJson, writeJsonChecked } from '../utils/storage.ts';

/** localStorage key (cleared by a factory reset with every other `crema.*` key). */
export const PENDING_EDITS_KEY = 'crema.visualizer.pendingEdits.v1';
/** The set this replaced: a list of shot ids whose barista edit was pending. */
export const LEGACY_BARISTA_PENDING_KEY = 'crema.visualizer.baristaPending.v1';

/** One shot's edits waiting to reach Visualizer. */
export interface PendingEdit {
	/** The rating, notes, privacy, bean, grind or grinder changed. */
	readonly annotations?: true;
	/** The barista changed. */
	readonly barista?: true;
	/** What a tag edit added and removed. */
	readonly tags?: TagMarks;
	/**
	 * Visualizer didn't keep the tags sent: the tier is checked (at most once a
	 * day) before tags are sent again.
	 */
	readonly tierSignal?: true;
	/** Failed attempts since the last success. */
	readonly attempts?: number;
	/** Unix ms before which the drain leaves this shot alone. */
	readonly nextAttemptAt?: number;
}

/** What one History edit changed. */
export type ShotEdit =
	| 'annotations'
	| 'barista'
	| { readonly tags: { readonly before: readonly string[]; readonly after: readonly string[] } };

type Edits = Record<string, PendingEdit>;

/** Marks this tab couldn't persist, by shot id. */
const memory = new Map<string, PendingEdit>();

const isEdits = (v: unknown): v is Edits =>
	isJsonObject(v) && Object.values(v).every((e) => isJsonObject(e));

const isIdList = (v: unknown): v is string[] =>
	Array.isArray(v) && v.every((x) => typeof x === 'string');

/** Whether a tag mark still has anything to send. */
export function hasTagMarks(marks: TagMarks | undefined): marks is TagMarks {
	return (marks?.add?.length ?? 0) + (marks?.remove?.length ?? 0) > 0;
}

/** Whether `edit` still owes Visualizer anything. */
function owes(edit: PendingEdit): boolean {
	return edit.annotations === true || edit.barista === true || hasTagMarks(edit.tags);
}

function stored(): Edits {
	const edits = readJson<Edits>(PENDING_EDITS_KEY, {}, { valid: isEdits });
	const legacy = readJson<string[]>(LEGACY_BARISTA_PENDING_KEY, [], { valid: isIdList });
	if (legacy.length === 0) return edits;
	const merged: Edits = { ...edits };
	for (const id of legacy) merged[id] = { ...merged[id], barista: true };
	if (writeJsonChecked(PENDING_EDITS_KEY, merged)) writeJson(LEGACY_BARISTA_PENDING_KEY, []);
	return merged;
}

/** Store `edit` for `id`, or drop it once it owes nothing. */
function put(id: string, edit: PendingEdit | undefined): void {
	const next = { ...stored() };
	if (edit && owes(edit)) next[id] = edit;
	else delete next[id];
	if (writeJsonChecked(PENDING_EDITS_KEY, next)) {
		memory.delete(id);
	} else if (edit && owes(edit)) {
		memory.set(id, edit);
	} else {
		memory.delete(id);
	}
}

/** Every shot with an edit pending, read from storage once. */
export function pendingEdits(): ReadonlyMap<string, PendingEdit> {
	const out = new Map(Object.entries(stored()));
	for (const [id, edit] of memory) out.set(id, edit);
	return out;
}

/** `id`'s pending edit, if any. */
export function pendingEdit(id: string): PendingEdit | undefined {
	return memory.get(id) ?? stored()[id];
}

/**
 * Record an edit of a shot already on Visualizer. Call it before the row is
 * saved, so a kill between the two leaves only a harmless extra mark.
 */
export function markShotEdit(id: string, edit: ShotEdit): void {
	const current = pendingEdit(id) ?? {};
	if (edit === 'annotations') {
		put(id, { ...current, annotations: true, nextAttemptAt: undefined, attempts: undefined });
		return;
	}
	if (edit === 'barista') {
		put(id, { ...current, barista: true, nextAttemptAt: undefined, attempts: undefined });
		return;
	}
	const tags = JSON.parse(
		wasmMarkTagEdit(
			JSON.stringify(current.tags ?? {}),
			JSON.stringify(edit.tags.before),
			JSON.stringify(edit.tags.after)
		)
	) as TagMarks;
	put(id, { ...current, tags, nextAttemptAt: undefined, attempts: undefined });
}

/** Replace `id`'s pending edit with `next` (dropped once it owes nothing). */
export function settlePendingEdit(id: string, next: PendingEdit | undefined): void {
	put(id, next);
}

/** Forget every mark: they were owed to an account this device has left. */
export function clearPendingEdits(): void {
	memory.clear();
	writeJson(PENDING_EDITS_KEY, {});
	writeJson(LEGACY_BARISTA_PENDING_KEY, []);
}

/** Test seam: forget marks held only in memory. */
export function resetPendingEditsForTest(): void {
	memory.clear();
}
