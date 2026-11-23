/**
 * `#lib/grinder/connected-link.svelte.js` — which owned grinder record a connected
 * grinder is (spec `.scratch/grinder-ble/SPEC.md` §6.1, owner decision 7).
 *
 * The connected grinder and the grinder record are different things with
 * different ids. The link between them is kept the way the scale and the DE1
 * keep their identity (decision 7: the same fields and rules, no new scheme):
 * device-local, keyed by the browser's Bluetooth id (`BluetoothDevice.id`,
 * per origin and profile), so it is not library data, not in a backup and
 * not synced. The serial stays in memory only, as a scale's does.
 *
 * Resolution on `GrinderReady` (§6.1):
 *  1. this device's link names a live record → that one;
 *  2. else exactly one live record from the connected model's catalogue entry
 *     that no other device links to → link it, with a message;
 *  3. else several such records → ask on Devices ("Which grinder is this?");
 *  4. else none → offer to add the model to the grinders.
 * A link to an archived record is kept (Restore brings it back), never
 * replaced silently. Unlink and relink are on Devices → Grinder.
 */

import { isJsonObject, readJson, writeJson } from '#lib/utils/storage.js';
import type { Grinder } from '#lib/core/crema-core.js';

/** localStorage key for the links (device-local, not in backups). */
export const GRINDER_LINKS_KEY = 'crema.grinder.links.v1';

/** One device's link: the grinder record it is. */
export interface GrinderLink {
	readonly grinderId: string;
	/** Unix ms of the link. */
	readonly linkedAt: number;
}

/** Links by browser Bluetooth id. */
export type GrinderLinks = Readonly<Record<string, GrinderLink>>;

function isLink(v: unknown): v is GrinderLink {
	return isJsonObject(v) && typeof v.grinderId === 'string' && typeof v.linkedAt === 'number';
}

/** Read the stored links; a malformed entry is dropped. */
export function readGrinderLinks(): GrinderLinks {
	const raw = readJson<Record<string, unknown> | null>(GRINDER_LINKS_KEY, null, {
		valid: isJsonObject
	});
	const out: Record<string, GrinderLink> = {};
	if (raw) for (const [id, v] of Object.entries(raw)) if (isLink(v)) out[id] = v;
	return out;
}

/** What {@link resolveGrinderLink} found. */
export type LinkResolution =
	/** This device is linked to a live record. */
	| { readonly kind: 'linked'; readonly grinder: Grinder }
	/** This device is linked to an archived record: kept, offered for Restore. */
	| { readonly kind: 'archived'; readonly grinder: Grinder }
	/** One record fits and nothing links it: link it (rule 2). */
	| { readonly kind: 'auto'; readonly grinder: Grinder }
	/** Several records fit: the user picks (rule 3). */
	| { readonly kind: 'choose'; readonly candidates: readonly Grinder[] }
	/** None fits: offer to add one (rule 4). */
	| { readonly kind: 'add' };

/**
 * Which record the connected grinder `deviceId` of `model` (its catalogue id)
 * is. `grinders` is every record (archived and tombstones included). Pure.
 */
export function resolveGrinderLink(input: {
	readonly deviceId: string;
	readonly model: string;
	readonly grinders: readonly Grinder[];
	readonly links: GrinderLinks;
}): LinkResolution {
	const { deviceId, model, grinders, links } = input;
	const isLive = (g: Grinder) => g.archivedAt == null && g.deletedAt == null;
	const own = links[deviceId];
	if (own) {
		const g = grinders.find((r) => r.id === own.grinderId && r.deletedAt == null);
		if (g) return isLive(g) ? { kind: 'linked', grinder: g } : { kind: 'archived', grinder: g };
	}
	const taken = new Set(
		Object.entries(links)
			.filter(([id]) => id !== deviceId)
			.map(([, l]) => l.grinderId)
	);
	const candidates = grinders.filter((g) => isLive(g) && g.presetId === model && !taken.has(g.id));
	if (candidates.length === 1) return { kind: 'auto', grinder: candidates[0]! };
	if (candidates.length > 1) return { kind: 'choose', candidates };
	return { kind: 'add' };
}

/** The links, reactive: Devices → Grinder renders from them. */
export class GrinderLinkStore {
	#links = $state<GrinderLinks>(readGrinderLinks());

	get links(): GrinderLinks {
		return this.#links;
	}

	/** The record id `deviceId` is linked to, or null. */
	linkOf(deviceId: string): string | null {
		return this.#links[deviceId]?.grinderId ?? null;
	}

	/** Link `deviceId` to `grinderId` (replacing its link, and any other device's to that record). */
	link(deviceId: string, grinderId: string, nowMs = Date.now()): void {
		const next: Record<string, GrinderLink> = {};
		for (const [id, l] of Object.entries(this.#links)) if (l.grinderId !== grinderId) next[id] = l;
		next[deviceId] = { grinderId, linkedAt: nowMs };
		this.#save(next);
	}

	/** Drop `deviceId`'s link (Unlink, Forget). */
	unlink(deviceId: string): void {
		if (!(deviceId in this.#links)) return;
		const next = { ...this.#links };
		delete next[deviceId];
		this.#save(next);
	}

	/** Re-read the store (tests). */
	reload(): void {
		this.#links = readGrinderLinks();
	}

	#save(next: GrinderLinks): void {
		this.#links = next;
		writeJson(GRINDER_LINKS_KEY, next);
	}
}

let store: GrinderLinkStore | null = null;

/** The app's one link store. */
export function getGrinderLinkStore(): GrinderLinkStore {
	return (store ??= new GrinderLinkStore());
}
