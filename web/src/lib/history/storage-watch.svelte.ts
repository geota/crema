/**
 * `#lib/history/storage-watch.svelte.js` — the storage notices (#310 P6w,
 * SPEC §1). With no row cap, the real limit is disk, so the app watches it:
 *
 * | Level          | `storage.estimate()` | What shows                                      |
 * | -------------- | -------------------- | ----------------------------------------------- |
 * | `getting-full` | 80% of quota         | One notice, with Back up and Free up space      |
 * | `nearly-full`  | 95% of quota         | A lasting banner                                |
 *
 * The 80% notice shows once: dismissed, it stays away until use drops below
 * 80% and climbs back. A write that fails (`QuotaExceededError`) has its own
 * banner, the history's unsaved one. Nothing here deletes anything.
 */
import { readJson, writeJson } from '#lib/utils/storage.js';

export type StorageLevel = 'ok' | 'getting-full' | 'nearly-full';

export const GETTING_FULL = 0.8;
export const NEARLY_FULL = 0.95;
/** localStorage key: the 80% notice was dismissed, and use hasn't dropped below 80% since. */
export const GETTING_FULL_SEEN_KEY = 'crema.storage.gettingFullSeen';
/** How often the level is checked while the app is open. */
const CHECK_EVERY_MS = 5 * 60_000;
/** At most this often when a save asks. */
const SOON_MS = 30_000;

export type Estimate = () => Promise<{ usage?: number; quota?: number } | undefined>;

/** The level for `usage` of `quota`; null when the browser doesn't say. */
export function storageLevelOf(e: { usage?: number; quota?: number } | undefined): StorageLevel | null {
	if (!e || typeof e.usage !== 'number' || typeof e.quota !== 'number' || !(e.quota > 0)) return null;
	const used = e.usage / e.quota;
	return used >= NEARLY_FULL ? 'nearly-full' : used >= GETTING_FULL ? 'getting-full' : 'ok';
}

function defaultEstimate(): ReturnType<Estimate> {
	if (typeof navigator === 'undefined' || typeof navigator.storage?.estimate !== 'function') return Promise.resolve(undefined);
	return navigator.storage.estimate();
}

/** The storage level, checked now and then, for the notices. */
export class StorageWatch {
	level = $state<StorageLevel | null>(null);
	usage = $state<number | null>(null);
	quota = $state<number | null>(null);
	private seen = $state(readJson<boolean>(GETTING_FULL_SEEN_KEY, false, { valid: (v): v is boolean => typeof v === 'boolean' }));
	private lastCheck = 0;
	private checking: Promise<void> | null = null;

	constructor(
		private readonly estimate: Estimate = defaultEstimate,
		private readonly now: () => number = Date.now
	) {}

	/** Whether the one 80% notice shows now. */
	get gettingFullNotice(): boolean {
		return this.level === 'getting-full' && !this.seen;
	}

	/** Check the level now. */
	check(): Promise<void> {
		if (this.checking) return this.checking;
		this.lastCheck = this.now();
		this.checking = (async () => {
			try {
				const e = await this.estimate();
				const level = storageLevelOf(e);
				this.level = level;
				this.usage = e?.usage ?? null;
				this.quota = e?.quota ?? null;
				// Below 80% again: the next climb shows the notice again.
				if (level === 'ok' && this.seen) {
					this.seen = false;
					writeJson(GETTING_FULL_SEEN_KEY, false);
				}
			} catch {
				// Not known now; the next check tries again.
			} finally {
				this.checking = null;
			}
		})();
		return this.checking;
	}

	/** Check again, unless one ran in the last half minute: after a save. */
	checkSoon(): void {
		if (this.now() - this.lastCheck < SOON_MS) return;
		void this.check();
	}

	/** The 80% notice was dismissed: it stays away until use drops below 80%. */
	dismissGettingFull(): void {
		this.seen = true;
		writeJson(GETTING_FULL_SEEN_KEY, true);
	}

	/** Check now, every few minutes, and when the tab comes back. Returns the stop. */
	start(): () => void {
		void this.check();
		const timer = setInterval(() => void this.check(), CHECK_EVERY_MS);
		const onShow = () => {
			if (document.visibilityState === 'visible') this.checkSoon();
		};
		document.addEventListener('visibilitychange', onShow);
		return () => {
			clearInterval(timer);
			document.removeEventListener('visibilitychange', onShow);
		};
	}
}

let watch: StorageWatch | null = null;
/** The app's one storage watch. */
export function getStorageWatch(): StorageWatch {
	watch ??= new StorageWatch();
	return watch;
}

/** The design size (SPEC §1): past it History still works and keeps every row, more slowly. */
export const HISTORY_DESIGN_SIZE = 5_000;
