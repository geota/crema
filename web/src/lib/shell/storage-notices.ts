/**
 * `#lib/shell/storage-notices` — turns `$lib/utils/storage` notices into toasts.
 *
 * Stores load at module init, before the root layout mounts, so a corrupt
 * value found then is queued; {@link installStorageNotices} drains the queue
 * once (several unreadable stores fold into one message) and then shows later
 * notices as they happen. A corrupt store's toast stays until dismissed and
 * offers "Save copy", which downloads the kept-aside raw values. A store whose
 * saves are refused is a different thing: that has its own strip, which stays
 * for as long as the refusal does (`RefusedStoresStrip`).
 */
import { toast } from '#lib/components/shared/toast.svelte.js';
import { downloadBlob } from '#lib/utils/download.js';
import {
	corruptKeyOf,
	onStorageNotice,
	skippedKeyOf,
	storageNoticeMessage,
	takeStorageNotices,
	type RefusedStore,
	type StorageNotice
} from '#lib/utils/storage.js';

type Corrupt = Extract<StorageNotice, { kind: 'corrupt' }>;

/** "shot history", or "shot history, settings and bean library". */
function nameList(what: readonly string[]): string {
	const names = [...new Set(what)];
	return names.length === 1 ? names[0] : `${names.slice(0, -1).join(', ')} and ${names.at(-1)}`;
}

/** The banner's line while saves to `stores` are refused. */
export function refusedMessage(stores: readonly RefusedStore[]): string {
	const list = nameList(stores.map((s) => s.what));
	return `Couldn’t read your ${list}, so changes aren’t being saved.`;
}

/** One line for several unreadable stores ("shot history and bean library"). */
export function corruptMessage(items: readonly Corrupt[]): string {
	if (items.length === 1) return storageNoticeMessage(items[0]);
	const list = nameList(items.map((n) => n.what));
	return items.every((n) => n.kept)
		? `Couldn’t read your ${list}; the damaged data was kept aside.`
		: `Couldn’t read your ${list}; nothing was overwritten.`;
}

/** The kept-aside raw values, as one JSON file the user can keep or send. */
export function keptAsideBundle(keys: readonly string[]): string {
	const out: Record<string, string | null> = {};
	for (const key of keys) {
		try {
			out[corruptKeyOf(key)] = localStorage.getItem(corruptKeyOf(key)) ?? localStorage.getItem(key);
			const skipped = localStorage.getItem(skippedKeyOf(key));
			if (skipped !== null) out[skippedKeyOf(key)] = skipped;
		} catch {
			out[corruptKeyOf(key)] = null;
		}
	}
	return JSON.stringify(out, null, 2);
}

/** Offer the kept-aside values of `keys` behind `message`, until dismissed. */
function offerCopy(message: string, keys: readonly string[]): void {
	toast.action(
		message,
		'Save copy',
		() =>
			downloadBlob(
				'crema-damaged-data.json',
				new Blob([keptAsideBundle(keys)], { type: 'application/json' })
			),
		0
	);
}

function showCorrupt(items: readonly Corrupt[]): void {
	offerCopy(
		corruptMessage(items),
		items.map((n) => n.key)
	);
}

function show(n: StorageNotice): void {
	if (n.kind === 'corrupt') showCorrupt([n]);
	else if (n.kind === 'skipped') offerCopy(storageNoticeMessage(n), [n.key]);
	else toast.error(storageNoticeMessage(n), 10_000);
}

/** Show queued notices and subscribe to new ones; returns the unsubscribe. */
export function installStorageNotices(): () => void {
	const queued = takeStorageNotices();
	const corrupt = queued.filter((n): n is Corrupt => n.kind === 'corrupt');
	if (corrupt.length > 0) showCorrupt(corrupt);
	for (const n of queued) if (n.kind !== 'corrupt') show(n);
	return onStorageNotice(show);
}
