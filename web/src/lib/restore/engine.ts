/**
 * `#lib/restore/engine` — a web restore through the core's plan
 * (restore-safety design, sections 1 and 2, PR7w).
 *
 * The core reads the backup's bytes, strictly, and decides per store and mode;
 * this file reads the file or zip, says what the device holds, and writes the
 * plan in its order. Each store computes its next value and lands in one
 * write: nothing is cleared, and `remove` names ids, so a shot that lands
 * during the restore survives it.
 *
 * Other tabs (PR7t's rules, and the design's six for PR7w):
 * 1. `crema-restore` is taken before `started` is posted and held until
 *    after `done`.
 * 2. `done` is posted from a `finally`, on failure too.
 * 3. `done` follows the last write.
 * 4. `progress` goes at least every {@link PROGRESS_MS}, inside long steps
 *    too: it's the heartbeat a tab without Web Locks waits on.
 * 5. Every message goes through `announceRestore`, which carries this tab's id.
 * 6. The busy lock at step 8 isn't proof that no shot runs: a shot that starts
 *    while step 8 waits is refused the lock, and a frozen tab stashes it.
 */
import {
	commonIsPristine,
	importBackupBytes,
	planRestore,
	scanBackupBytes
} from '#lib/wasm/de1_wasm.js';
import {
	PlanAction,
	PlanCode,
	RestoreMode as CoreMode,
	Verdict,
	type LocalState,
	type PlanNote,
	type RestorePlan,
	type StorePlan
} from '#lib/core/crema-core.js';
import { getBeanStore } from '#lib/bean/index.js';
import type { Bean, Roaster } from '#lib/bean/model.js';
import { getBeanImageStore, refForBean } from '#lib/bean/image-storage.js';
import { getHistoryStore, type StoredShot } from '#lib/history/index.js';
import { coerceStoredShot } from '#lib/history/store.svelte.js';
import { getProfileStore } from '#lib/profiles/index.js';
import type { CremaProfile } from '#lib/profiles/model.js';
import { getRecipeStore } from '#lib/brew/recipes.svelte.js';
import { getCustomMethodStore } from '#lib/brew/custom-methods.svelte.js';
import type { BrewRecipe, CustomBrewMethod } from '#lib/core/crema-core.js';
import { getSettingsStore, settingsToCommon } from '#lib/settings/index.js';
import { getMaintenanceStore } from '#lib/maintenance/index.js';
import { visualizerSyncPrefs } from '#lib/visualizer/sync-config.js';
import { dequeueEntry } from '#lib/services/queue-store.js';
import { cancelUnboundTombstone } from '#lib/visualizer/upload-marks.js';
import { newestFirst } from '#lib/history/brew-date.js';
import { storeHealth } from '#lib/utils/storage.js';
import { assertImportSize, unzipImportSync } from '#lib/utils/import-limits.js';
import {
	MACHINE_BUSY_LOCK,
	RESTORE_LOCK,
	announceRestore,
	getMachineBusy,
	markRestoringHere,
	shotIsOpen,
	type LockManagerLike
} from './tab-safety';
import { beginMarker, finishMarker } from './marker';

/** The most time between two `progress` posts (rule 4: under 10 s). */
export const PROGRESS_MS = 5_000;
/** How long step 8 waits for a shot or flash to end. */
export const MACHINE_WAIT_MS = 120_000;

const BACKUP_MEMBER = 'backup.jsonl';
const LEGACY_MEMBER = 'crema.jsonl';

/** A backup read off its file: the JSONL bytes and the zip's photos. */
export interface PreparedBackup {
	/** For the marker and the report: the file's name. */
	readonly name: string;
	readonly jsonl: Uint8Array;
	/** Bean id → photo bytes. */
	readonly photos: ReadonlyMap<string, Uint8Array>;
	/** The core's `BackupManifest`, as JSON. */
	readonly manifestJson: string;
}

function isZip(b: Uint8Array): boolean {
	return b.length >= 4 && b[0] === 0x50 && b[1] === 0x4b && b[2] === 0x03 && b[3] === 0x04;
}

/**
 * Read a backup: a `.crema.zip` (its `backup.jsonl` and `images/<beanId>`) or
 * bare JSONL. The bytes go to the core undecoded: one bad byte is one bad line.
 */
export function prepareBackup(data: ArrayBuffer | Uint8Array | string, name: string): PreparedBackup {
	const bytes =
		typeof data === 'string'
			? new TextEncoder().encode(data)
			: data instanceof Uint8Array
				? data
				: new Uint8Array(data);
	assertImportSize(bytes.length, 'The backup');
	let jsonl = bytes;
	const photos = new Map<string, Uint8Array>();
	if (isZip(bytes)) {
		const files = unzipImportSync(
			bytes,
			(n) => n === BACKUP_MEMBER || n === LEGACY_MEMBER || n.startsWith('images/')
		);
		const member = files[BACKUP_MEMBER] ?? files[LEGACY_MEMBER];
		if (!member) throw new Error('Not a Crema backup zip (no backup.jsonl inside).');
		jsonl = member;
		for (const [path, file] of Object.entries(files)) {
			if (path.startsWith('images/') && file.length > 0) photos.set(path.slice('images/'.length), file);
		}
	}
	const manifestJson = scanBackupBytes(jsonl, JSON.stringify([...photos.keys()]));
	return { name, jsonl, photos, manifestJson };
}

/** Whether another tab has a shot or flash: the busy lock is held somewhere. */
async function busyElsewhere(locks: LockManagerLike | undefined): Promise<boolean> {
	const query = (locks as { query?: () => Promise<{ held?: { name?: string }[] }> } | undefined)?.query;
	if (!query) return false;
	try {
		const snapshot = await query.call(locks);
		return (snapshot.held ?? []).some((l) => l.name === MACHINE_BUSY_LOCK);
	} catch {
		return false;
	}
}

function defaultLocks(): LockManagerLike | undefined {
	return typeof navigator !== 'undefined' && 'locks' in navigator
		? (navigator.locks as unknown as LockManagerLike)
		: undefined;
}

/** Bean ids a stored photo exists for. */
async function storedPhotoBeans(beans: readonly Bean[]): Promise<{ have: string[]; missing: string[] }> {
	let keys: Set<string>;
	try {
		keys = new Set(await getBeanImageStore().keys());
	} catch {
		keys = new Set();
	}
	const have: string[] = [];
	const missing: string[] = [];
	for (const b of beans) {
		if (keys.has(refForBean(b.id))) have.push(b.id);
		else if (b.imageRef) missing.push(b.id);
	}
	return { have, missing };
}

/**
 * What the device holds, for the plan. After the series have loaded: before
 * that every row looks curveless, and Merge would offer to fill them all.
 */
export async function buildLocalState(locks: LockManagerLike | undefined = defaultLocks()): Promise<LocalState> {
	const history = getHistoryStore();
	await history.seriesReady();
	const beans = getBeanStore();
	const profiles = getProfileStore();
	const recipes = getRecipeStore();
	const methods = getCustomMethodStore();
	const settings = getSettingsStore();
	const photos = await storedPhotoBeans(beans.beans);
	const profileMeta = profiles.backupMeta();
	const recipeMeta = recipes.backupMeta();
	const health = (
		[
			['shots', 'crema.history.v2'],
			['beans', 'crema.beans.v1'],
			['profiles', 'crema.profiles.custom.v1'],
			['recipes', 'crema.brewRecipes.v1'],
			['customMethods', 'crema.brewMethods.custom.v1']
		] as const
	).map(([store, key]) => ({ store, health: storeHealth(key) }));
	return {
		shell: 'web',
		historyCapacity: history.capacity,
		machineBusy: getMachineBusy().busy || shotIsOpen() || (await busyElsewhere(locks)),
		health,
		beans: beans.beans.map((b) => b.id),
		roasters: beans.roasters.map((r) => r.id),
		profiles: profiles.customProfiles.map((p) => p.id),
		recipes: recipes.ownRecipes.map((r) => r.id),
		customMethods: methods.everything.map((m) => m.id),
		shots: history.rawAll.map((s) => ({
			id: s.id,
			hasSamples: s.record.samples.length > 0,
			pinned: false,
			deleted: s.deletedAt != null
		})),
		photos: photos.have,
		beansMissingPhoto: photos.missing,
		profileMeta: { pinned: profileMeta.pinned, hiddenBuiltins: profileMeta.hiddenBuiltins },
		recipeMeta: { defaults: recipeMeta.defaults ?? {}, hiddenBuiltins: recipeMeta.hiddenBuiltins ?? [] },
		common: settingsToCommon(settings.current),
		visualizerPrefs: visualizerSyncPrefs(),
		totalLitres: getMaintenanceStore().current.totalLitres ?? 0,
		activeBeanId: beans.activeBeanId
	} as unknown as LocalState;
}

/** The web's two restore buttons, in the core's words. */
export type WebMode = 'merge' | 'wipe';
const coreMode = (m: WebMode): CoreMode => (m === 'wipe' ? CoreMode.Replace : CoreMode.Merge);

/** The plan for `prepared` against `local`. */
export function planFor(
	prepared: PreparedBackup,
	mode: WebMode,
	local: LocalState,
	reviveDeleted = false
): RestorePlan {
	return JSON.parse(
		planRestore(prepared.manifestJson, JSON.stringify(local), coreMode(mode), JSON.stringify({ reviveDeleted }))
	) as RestorePlan;
}

/** Plan against this device as it is now. */
export async function planNow(
	prepared: PreparedBackup,
	mode: WebMode,
	reviveDeleted = false,
	locks: LockManagerLike | undefined = defaultLocks()
): Promise<RestorePlan> {
	return planFor(prepared, mode, await buildLocalState(locks), reviveDeleted);
}

/** Whether a fresh install's settings are still as it left them (the core's rule). */
export function settingsPristine(): boolean {
	return commonIsPristine(JSON.stringify(settingsToCommon(getSettingsStore().current)));
}

// ── Records ─────────────────────────────────────────────────────────────────

/** A backup's records, from the same strict line rules the plan counted. */
export interface BackupRecords {
	profiles: CremaProfile[];
	beans: Bean[];
	roasters: Roaster[];
	shots: StoredShot[];
	recipes: BrewRecipe[];
	customMethods: CustomBrewMethod[];
	settings: Record<string, unknown> | null;
	maintenance: Record<string, unknown> | null;
	visualizerPrefs: Record<string, unknown> | null;
}

/**
 * The records, each decoded to the shell's own type before the first write.
 * A shot the shell can't take is a refusal for the whole restore: the plan
 * counted it.
 */
export function readRecords(prepared: PreparedBackup): BackupRecords {
	const raw = JSON.parse(importBackupBytes(prepared.jsonl)) as Partial<
		Record<keyof BackupRecords, unknown>
	>;
	const list = <T>(v: unknown): T[] => (Array.isArray(v) ? (v as T[]) : []);
	const shots: StoredShot[] = [];
	for (const s of list<Record<string, unknown>>(raw.shots)) {
		const shot = coerceStoredShot(s);
		if (!shot) throw new Error(`A shot in this backup can't be read here (${String(s.id ?? '?')}). Nothing was changed.`);
		shots.push(shot);
	}
	const obj = (v: unknown) => (typeof v === 'object' && v !== null ? (v as Record<string, unknown>) : null);
	return {
		profiles: list<CremaProfile>(raw.profiles).filter((p) => typeof p?.id === 'string'),
		beans: list<Bean>(raw.beans),
		roasters: list<Roaster>(raw.roasters),
		shots,
		recipes: list<BrewRecipe>(raw.recipes),
		customMethods: list<CustomBrewMethod>(raw.customMethods),
		settings: obj(raw.settings),
		maintenance: obj(raw.maintenance),
		visualizerPrefs: obj(raw.visualizerPrefs)
	};
}

/** `local − remove + overwrite + add`, from the backup's rows. First of a repeated id wins. */
export function nextRows<T extends { id: string }>(
	local: readonly T[],
	backup: readonly T[],
	plan: StorePlan
): T[] {
	const theirs = new Map<string, T>();
	for (const r of backup) if (!theirs.has(r.id)) theirs.set(r.id, r);
	const remove = new Set(plan.remove);
	const overwrite = new Set(plan.overwrite);
	const kept = local
		.filter((r) => !remove.has(r.id))
		.map((r) => (overwrite.has(r.id) ? (theirs.get(r.id) ?? r) : r));
	const added = plan.add.map((id) => theirs.get(id)).filter((r): r is T => r !== undefined);
	return [...added, ...kept];
}

/** The history a plan leaves, and the rows whose curve to write first. */
export function nextShots(
	local: readonly StoredShot[],
	backup: readonly StoredShot[],
	plan: RestorePlan['shots']
): { next: StoredShot[]; series: StoredShot[] } {
	const theirs = new Map<string, StoredShot>();
	for (const s of backup) if (!theirs.has(s.id)) theirs.set(s.id, s);
	const remove = new Set(plan.remove);
	const replaced = new Set([...plan.overwrite, ...plan.revive]);
	const fill = new Set(plan.fill);
	const zero = new Set(plan.zeroDebit);
	const series: StoredShot[] = [];
	const kept = local
		.filter((s) => !remove.has(s.id))
		.map((s) => {
			const b = theirs.get(s.id);
			if (!b) return s;
			if (replaced.has(s.id)) {
				series.push(b);
				return b;
			}
			if (fill.has(s.id) && s.record.samples.length === 0 && b.record.samples.length > 0) {
				// The backfill rule: the curve only, never over a curve here.
				const filled = {
					...s,
					record: { duration: b.record.duration || s.record.duration, samples: [...b.record.samples] }
				};
				series.push(filled);
				return filled;
			}
			return s;
		});
	const added = plan.add
		.map((id) => theirs.get(id))
		.filter((s): s is StoredShot => s !== undefined)
		// A bean already here never paid for a shot from elsewhere.
		.map((s) => (zero.has(s.id) ? { ...s, bagDebit: 0 } : s));
	series.push(...added);
	return { next: newestFirst([...added, ...kept]), series };
}

// ── Applying ────────────────────────────────────────────────────────────────

/** Where a restore that stopped got to. */
export type RestoreStep =
	| 'marker'
	| 'tombstones'
	| 'photos'
	| 'recipes'
	| 'beans'
	| 'profiles'
	| 'shots'
	| 'settings';

/** How a restore ended. */
export type RestoreOutcome =
	| { kind: 'refused'; plan: RestorePlan }
	/** The device changed while the report was open: here are the new numbers. */
	| { kind: 'changed'; plan: RestorePlan }
	/** Another tab is restoring. */
	| { kind: 'busy' }
	| {
			kind: 'done';
			plan: RestorePlan;
			curvesFailed: number;
			/** Step 8 timed out: settings, maintenance and Visualizer prefs weren't changed. */
			singletonsSkipped: boolean;
			/** What the settings line held for the user's OK. */
			settingsApplied: boolean;
	  }
	| { kind: 'stopped'; plan: RestorePlan; at: RestoreStep; landed: RestoreStep[] };

/** The settings-like lines, applied at step 8. Kept in `backup/index.ts`, beside the webhook rules. */
export interface SingletonSink {
	settings(settings: Record<string, unknown>, plan: RestorePlan['settings'], replace: boolean): boolean;
	maintenance(state: Record<string, unknown>): boolean;
	visualizerPrefs(prefs: Record<string, unknown>): boolean;
}

export interface RestoreDeps {
	singletons: SingletonSink;
	locks?: LockManagerLike;
	/** Test seam: throw at a step, as a kill or a full disk would stop it there. */
	failAt?: RestoreStep;
	machineWaitMs?: number;
	progressMs?: number;
	now?: () => number;
}

/** Comparable: what the user saw against what would run now. */
function sameNumbers(a: RestorePlan, b: RestorePlan): boolean {
	return JSON.stringify(a) === JSON.stringify(b);
}

/**
 * Run a restore. With `expected`, the plan the user confirmed: if the device
 * changed since, nothing runs and the new plan comes back. Holds
 * `crema-restore` from before `started` until after `done`.
 */
export async function runRestore(
	prepared: PreparedBackup,
	mode: WebMode,
	opts: { reviveDeleted?: boolean; expected?: RestorePlan | null },
	deps: RestoreDeps
): Promise<RestoreOutcome> {
	const locks = 'locks' in deps ? deps.locks : defaultLocks();
	if (locks) {
		let outcome: RestoreOutcome = { kind: 'busy' };
		let granted = false;
		try {
			await locks.request(RESTORE_LOCK, { mode: 'exclusive', ifAvailable: true }, async (lock) => {
				if (lock === null) return;
				granted = true;
				outcome = await holdingRestore(prepared, mode, opts, deps, locks);
			});
		} catch (e) {
			if (granted) throw e;
			// The request itself was refused (an opaque origin): run without.
			return runWithoutLocks(prepared, mode, opts, deps);
		}
		return outcome;
	}
	return runWithoutLocks(prepared, mode, opts, deps);
}

/** A restore running in this tab without Web Locks: one at a time, on a flag. */
let restoringNoLocks = false;

async function runWithoutLocks(
	prepared: PreparedBackup,
	mode: WebMode,
	opts: { reviveDeleted?: boolean; expected?: RestorePlan | null },
	deps: RestoreDeps
): Promise<RestoreOutcome> {
	if (restoringNoLocks) return { kind: 'busy' };
	restoringNoLocks = true;
	try {
		return await holdingRestore(prepared, mode, opts, deps, undefined);
	} finally {
		restoringNoLocks = false;
	}
}

/** Step 0's machine check: exclusive and never waiting, released once `started` is out. */
async function machineFree(locks: LockManagerLike | undefined, announce: () => void): Promise<boolean> {
	if (getMachineBusy().busy || shotIsOpen()) return false;
	if (!locks) {
		announce();
		return true;
	}
	let free = false;
	try {
		await locks.request(MACHINE_BUSY_LOCK, { mode: 'exclusive', ifAvailable: true }, (lock) => {
			if (lock === null) return;
			free = true;
			// `started` goes while this is held, so a shot can't slip in unseen.
			announce();
		});
	} catch {
		free = true;
		announce();
	}
	return free;
}

/** Step 8's wait: the busy lock, blocking, for at most `ms`; then `run` under it. */
async function underMachine(
	locks: LockManagerLike | undefined,
	ms: number,
	run: () => void
): Promise<boolean> {
	if (!locks) {
		const until = Date.now() + ms;
		while (getMachineBusy().busy || shotIsOpen()) {
			if (Date.now() > until) return false;
			await new Promise((r) => setTimeout(r, 250));
		}
		run();
		return true;
	}
	const abort = new AbortController();
	const timer = setTimeout(() => abort.abort(), ms);
	try {
		let ran = false;
		await locks.request(MACHINE_BUSY_LOCK, { mode: 'exclusive', signal: abort.signal }, () => {
			// Rule 6: this lock says no shot holds it now, not that none will
			// start; one that does is refused the lock and kept by its tab.
			if (shotIsOpen() || getMachineBusy().busy) return;
			run();
			ran = true;
		});
		return ran;
	} catch {
		return false;
	} finally {
		clearTimeout(timer);
	}
}

async function holdingRestore(
	prepared: PreparedBackup,
	mode: WebMode,
	opts: { reviveDeleted?: boolean; expected?: RestorePlan | null },
	deps: RestoreDeps,
	locks: LockManagerLike | undefined
): Promise<RestoreOutcome> {
	// Step 0: re-plan from the device as it is now.
	const plan = await planNow(prepared, mode, opts.reviveDeleted ?? false, locks);
	if (plan.verdict === Verdict.Refused) return { kind: 'refused', plan };
	if (opts.expected && !sameNumbers(opts.expected, plan)) return { kind: 'changed', plan };
	const records = readRecords(prepared);

	let started = false;
	const free = await machineFree(locks, () => {
		markRestoringHere(true);
		announceRestore('started');
		started = true;
	});
	if (!free) {
		return {
			kind: 'refused',
			plan: {
				...plan,
				verdict: Verdict.Refused,
				refusals: [{ code: PlanCode.MachineBusy, confirm: true } as PlanNote]
			}
		};
	}
	const heartbeat = setInterval(() => announceRestore('progress'), deps.progressMs ?? PROGRESS_MS);
	const resumeDeletes = getHistoryStore().pauseDeletes();
	try {
		return await applyPlan(prepared, plan, records, deps, locks);
	} finally {
		clearInterval(heartbeat);
		resumeDeletes();
		// After the last write (rule 3), from a finally (rule 2), under the lock (rule 1).
		if (started) announceRestore('done');
		markRestoringHere(false);
	}
}

async function applyPlan(
	prepared: PreparedBackup,
	plan: RestorePlan,
	records: BackupRecords,
	deps: RestoreDeps,
	locks: LockManagerLike | undefined
): Promise<RestoreOutcome> {
	const landed: RestoreStep[] = [];
	const now = deps.now ?? Date.now;
	const replace = plan.mode === CoreMode.Replace;
	const step = async (name: RestoreStep, run: () => boolean | Promise<boolean>): Promise<boolean> => {
		if (deps.failAt === name) return false;
		const ok = await run();
		if (ok) landed.push(name);
		return ok;
	};
	const stopped = (at: RestoreStep): RestoreOutcome => ({ kind: 'stopped', plan, at, landed });

	// 1. The marker, with what this restore will owe to delete.
	if (
		!(await step('marker', () =>
			beginMarker(
				replace ? 'replace' : 'merge',
				prepared.name,
				{ shots: plan.shots.remove, photos: plan.photos.remove },
				now()
			)
		))
	) {
		return stopped('marker');
	}

	// 2. Tombstones the backup brings back: no remote DELETE may follow.
	if (
		!(await step('tombstones', () => {
			for (const id of plan.tombstones.cancel) {
				dequeueEntry('shot', id, 'delete');
				cancelUnboundTombstone(id);
			}
			return true;
		}))
	) {
		return stopped('tombstones');
	}

	// 3. Photos first, so a bean never points at one that isn't there.
	if (
		!(await step('photos', async () => {
			const images = getBeanImageStore();
			for (const beanId of plan.photos.put) {
				const bytes = prepared.photos.get(beanId);
				if (!bytes) continue;
				try {
					await images.put(refForBean(beanId), new Blob([bytes as BlobPart]));
				} catch {
					return false;
				}
			}
			return true;
		}))
	) {
		return stopped('photos');
	}

	// 4. Methods, then recipes, which name them.
	if (
		!(await step('recipes', () => {
			const methods = getCustomMethodStore();
			const recipes = getRecipeStore();
			const a = methods.restore(nextRows(methods.everything, records.customMethods, plan.customMethods));
			const meta =
				plan.recipeMeta.action === PlanAction.Apply
					? { defaults: plan.recipeMeta.defaults, hidden: plan.recipeMeta.hiddenBuiltins }
					: null;
			const b = recipes.restore(nextRows(recipes.ownRecipes, records.recipes, plan.recipes), meta);
			return a && b;
		}))
	) {
		return stopped('recipes');
	}

	// 5. Roasters and beans, one blob, and the active bean.
	if (
		!(await step('beans', () => {
			const beans = getBeanStore();
			return beans.restoreLibrary(
				nextRows(beans.beans, records.beans, plan.beans),
				nextRows(beans.roasters, records.roasters, plan.roasters),
				plan.clearActiveBean
			);
		}))
	) {
		return stopped('beans');
	}

	// 6. Profiles, and their pins and hidden built-ins.
	if (
		!(await step('profiles', () => {
			const profiles = getProfileStore();
			const a = profiles.restoreCustom(nextRows(profiles.customProfiles, records.profiles, plan.profiles));
			const b =
				plan.profileMeta.action === PlanAction.Apply
					? profiles.restoreMeta(plan.profileMeta.pinned, plan.profileMeta.hiddenBuiltins)
					: true;
			return a && b;
		}))
	) {
		return stopped('profiles');
	}

	// 7. Series, then rows, with nothing deleted.
	let curvesFailed = 0;
	if (
		!(await step('shots', async () => {
			const history = getHistoryStore();
			const { next, series } = nextShots(history.rawAll, records.shots, plan.shots);
			const r = await history.restoreRows(next, series);
			curvesFailed = r.curvesFailed;
			return r.stored;
		}))
	) {
		return stopped('shots');
	}

	// 8. Settings, maintenance, Visualizer prefs: never mid-shot or mid-flash,
	// as a settings push writes to the machine. At most two minutes.
	let settingsApplied = false;
	let singletonsOk = true;
	const wantsSingletons =
		(records.settings && plan.settings.common === PlanAction.Apply) ||
		(records.maintenance && plan.maintenance === PlanAction.Apply) ||
		(records.visualizerPrefs && plan.visualizerPrefs === PlanAction.Apply);
	let singletonsSkipped = false;
	if (wantsSingletons) {
		if (deps.failAt === 'settings') return stopped('settings');
		const ran = await underMachine(locks, deps.machineWaitMs ?? MACHINE_WAIT_MS, () => {
			if (records.settings && plan.settings.common === PlanAction.Apply) {
				singletonsOk = deps.singletons.settings(records.settings, plan.settings, replace) && singletonsOk;
				settingsApplied = true;
			}
			if (records.maintenance && plan.maintenance === PlanAction.Apply) {
				singletonsOk = deps.singletons.maintenance(records.maintenance) && singletonsOk;
			}
			if (records.visualizerPrefs && plan.visualizerPrefs === PlanAction.Apply) {
				singletonsOk = deps.singletons.visualizerPrefs(records.visualizerPrefs) && singletonsOk;
			}
		});
		singletonsSkipped = !ran;
		if (ran && !singletonsOk) return stopped('settings');
		if (ran) landed.push('settings');
	}

	// 9. Finishing: the deletes wait their hour.
	finishMarker(now());
	return { kind: 'done', plan, curvesFailed, singletonsSkipped, settingsApplied };
}
