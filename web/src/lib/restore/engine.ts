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
	BackupImporter,
	BackupScanner,
	commonIsPristine,
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
import { coerceBean, coerceRoaster, type Bean, type Roaster } from '#lib/bean/model.js';
import { getBeanImageStore, refForBean } from '#lib/bean/image-storage.js';
import { getHistoryStore, type StoredShot } from '#lib/history/index.js';
import { coerceStoredShot } from '#lib/history/store.svelte.js';
import { getProfileStore } from '#lib/profiles/index.js';
import type { CremaProfile } from '#lib/profiles/model.js';
import { getRecipeStore } from '#lib/brew/recipes.svelte.js';
import { getCustomMethodStore, isCustomMethodId } from '#lib/brew/custom-methods.svelte.js';
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
	BACKUP_READ_LIMITS,
	BackupReadError,
	backupLines,
	blobBytes,
	checkEntries,
	crc32,
	entryBytes,
	isZipStart,
	looksLikeZip,
	readCentralDirectory,
	type BackupLinePiece,
	type BackupReadLimits,
	type ZipEntry
} from '#lib/backup/zip-reader.js';
import {
	MACHINE_BUSY_LOCK,
	RESTORE_LOCK,
	announceRestore,
	getMachineBusy,
	markRestoringHere,
	shotIsOpen,
	type LockManagerLike
} from './tab-safety';
import { beginMarker, dropMarker, finishMarker, isRunOf, readMarker } from './marker';

/** The most time between two `progress` posts (rule 4: under 10 s). */
export const PROGRESS_MS = 5_000;
/** How long step 8 waits for a shot or flash to end. */
export const MACHINE_WAIT_MS = 120_000;

const BACKUP_MEMBER = 'backup.jsonl';
const LEGACY_MEMBER = 'crema.jsonl';

/**
 * A backup read off its file and scanned: what a plan needs, and a way back
 * to its lines and photos for the run. A file is read a piece at a time
 * ({@link prepareBackupFile}), so a restore holds a line, not the backup.
 */
export interface PreparedBackup {
	/** For the marker and the report: the file's name. */
	readonly name: string;
	/** The core's `BackupManifest`, as JSON. */
	readonly manifestJson: string;
	/**
	 * Which backup this is, by its header (`createdAt`, device, app version),
	 * so the same backup from Drive or from a file is the same; the file name
	 * stands in only when the header didn't read.
	 */
	readonly identity: string;
	/** The backup's lines, read again from the start. */
	lines(): AsyncIterable<BackupLinePiece>;
	/** A bean's photo from the zip, or null. */
	photo(beanId: string): Promise<Uint8Array | null>;
}

/** A backup held in memory whole: its JSONL bytes and the zip's photos. */
export interface InMemoryBackup extends PreparedBackup {
	readonly jsonl: Uint8Array;
	/** Bean id → photo bytes. */
	readonly photos: ReadonlyMap<string, Uint8Array>;
}

function identityOf(manifestJson: string, name: string): string {
	const header = (
		JSON.parse(manifestJson) as {
			header?: { createdAt?: number; deviceLabel?: string; appVersion?: string } | null;
		}
	).header;
	return header
		? JSON.stringify([header.createdAt ?? null, header.deviceLabel ?? '', header.appVersion ?? ''])
		: JSON.stringify(['file', name]);
}

/** `bytes` split on `0x0A`, as the core splits them. */
async function* linesOfBytes(bytes: Uint8Array): AsyncGenerator<BackupLinePiece> {
	let from = 0;
	for (let nl = bytes.indexOf(0x0a); nl >= 0; nl = bytes.indexOf(0x0a, from)) {
		yield { line: bytes.subarray(from, nl) };
		from = nl + 1;
	}
	yield { line: bytes.subarray(from) };
}

/**
 * Read a backup held in memory: a `.crema.zip` (its `backup.jsonl` and
 * `images/<beanId>`) or bare JSONL. The bytes go to the core undecoded: one
 * bad byte is one bad line. For tests and small imports; a file the user
 * picks, or one from Drive, goes through {@link prepareBackupFile}.
 */
export function prepareBackup(data: ArrayBuffer | Uint8Array | string, name: string): InMemoryBackup {
	const bytes =
		typeof data === 'string'
			? new TextEncoder().encode(data)
			: data instanceof Uint8Array
				? data
				: new Uint8Array(data);
	assertImportSize(bytes.length, 'The backup');
	let jsonl = bytes;
	const photos = new Map<string, Uint8Array>();
	if (isZipStart(bytes)) {
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
	return {
		name,
		jsonl,
		photos,
		manifestJson,
		identity: identityOf(manifestJson, name),
		lines: () => linesOfBytes(jsonl),
		photo: async (beanId) => photos.get(beanId) ?? null
	};
}

/** Feed `lines` to a scan, a line at a time. */
async function scanLines(lines: AsyncIterable<BackupLinePiece>, photoIds: readonly string[]): Promise<string> {
	const scanner = new BackupScanner();
	try {
		for await (const piece of lines) {
			if ('line' in piece) scanner.pushLine(piece.line);
			else scanner.pushOversizedLine();
		}
	} catch (e) {
		scanner.free();
		throw e;
	}
	return scanner.finish(JSON.stringify(photoIds));
}

/**
 * Read a backup from a `File` or `Blob` (#310 P2w-b): a `.crema.zip` through
 * its central directory, or bare JSONL, a piece at a time. Every byte the
 * restore will use is read here once, checked (each zip entry's CRC and size,
 * the limits) and scanned, before a plan is made; the run reads it again.
 */
export async function prepareBackupFile(
	file: Blob,
	name: string,
	limits: BackupReadLimits = BACKUP_READ_LIMITS
): Promise<PreparedBackup> {
	if (!(await looksLikeZip(file))) {
		if (file.size > limits.maxFileBytes) {
			throw new BackupReadError(
				`This backup is ${Math.ceil(file.size / 2 ** 20)} MB. Crema restores backups up to ${Math.ceil(limits.maxFileBytes / 2 ** 20)} MB.`
			);
		}
		// The first read keeps the file's CRC and length; every later read must
		// match them, or the run stops before its first write (REVIEW-394).
		let seen: { crc: number; length: number } | null = null;
		async function* checked(): AsyncGenerator<Uint8Array> {
			let crc = 0;
			let length = 0;
			for await (const piece of blobBytes(file)) {
				crc = crc32(crc, piece);
				length += piece.length;
				yield piece;
			}
			if (!seen) seen = { crc, length };
			else if (seen.crc !== crc || seen.length !== length) {
				throw new BackupReadError('This backup changed since it was checked. Nothing was changed.');
			}
		}
		const lines = () => backupLines(checked(), limits.maxLineBytes);
		const manifestJson = await scanLines(lines(), []);
		return { name, manifestJson, identity: identityOf(manifestJson, name), lines, photo: async () => null };
	}
	const entries = await readCentralDirectory(file, limits);
	const kind = (e: ZipEntry) =>
		e.name === BACKUP_MEMBER || e.name === LEGACY_MEMBER ? 'backup' : e.name.startsWith('images/') ? 'photo' : null;
	checkEntries(entries, kind, limits);
	const member =
		entries.find((e) => e.name === BACKUP_MEMBER) ?? entries.find((e) => e.name === LEGACY_MEMBER);
	if (!member) throw new Error('Not a Crema backup zip (no backup.jsonl inside).');
	if (member.size > limits.maxInflatedBytes) {
		throw new BackupReadError(`This backup unpacks to more than ${Math.ceil(limits.maxInflatedBytes / 2 ** 20)} MB.`);
	}
	const photos = new Map<string, ZipEntry>();
	for (const e of entries) {
		const beanId = kind(e) === 'photo' ? e.name.slice('images/'.length) : '';
		if (!beanId || e.size === 0) continue;
		// Its CRC now, so step 3 never meets a bad photo after the first write.
		for await (const piece of entryBytes(file, e)) void piece;
		photos.set(beanId, e);
	}
	const lines = () => backupLines(entryBytes(file, member), limits.maxLineBytes);
	const manifestJson = await scanLines(lines(), [...photos.keys()]);
	return {
		name,
		manifestJson,
		identity: identityOf(manifestJson, name),
		lines,
		photo: async (beanId) => {
			const e = photos.get(beanId);
			if (!e) return null;
			const parts: Uint8Array[] = [];
			for await (const piece of entryBytes(file, e)) parts.push(piece);
			const out = new Uint8Array(e.size);
			let at = 0;
			for (const p of parts) {
				out.set(p, at);
				at += p.length;
			}
			return out;
		}
	};
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
	// Each store reads its keys when it's made: their health is known after.
	const maintenance = getMaintenanceStore();
	const photos = await storedPhotoBeans(beans.beans);
	const profileMeta = profiles.backupMeta();
	const recipeMeta = recipes.backupMeta();
	const health = (
		[
			['shots', 'crema.history.v2'],
			['beans', 'crema.beans.v1'],
			['profiles', 'crema.profiles.custom.v1'],
			['recipes', 'crema.brewRecipes.v1'],
			['customMethods', 'crema.brewMethods.custom.v1'],
			['settings', 'crema.settings.v1'],
			['maintenance', 'crema.maintenance.v2'],
			['profileMeta', 'crema.profiles.builtinOverrides.v1'],
			['profileMeta', 'crema.profiles.hiddenBuiltins.v1'],
			['recipeMeta', 'crema.brewRecipes.lastUsed.v1'],
			['recipeMeta', 'crema.brewRecipes.hiddenBuiltins.v1']
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
			pinned: s.pinned === true,
			deleted: s.deletedAt != null
		})),
		photos: photos.have,
		beansMissingPhoto: photos.missing,
		profileMeta: { pinned: profileMeta.pinned, hiddenBuiltins: profileMeta.hiddenBuiltins },
		recipeMeta: { defaults: recipeMeta.defaults ?? {}, hiddenBuiltins: recipeMeta.hiddenBuiltins ?? [] },
		common: settingsToCommon(settings.current),
		visualizerPrefs: visualizerSyncPrefs(),
		totalLitres: maintenance.current.totalLitres ?? 0,
		activeBeanId: beans.activeBeanId,
		// Filled per backup by planNow, from the marker of a run of that same backup.
		addedBeans: []
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
	const local = await buildLocalState(locks);
	// A re-run after an interrupted one of this same backup, by its header
	// (DECISIONS §7): the beans it added aren't this device's own bag.
	const m = readMarker();
	const addedBeans = m && isRunOf(m, prepared.identity) ? (m.addedBeans ?? []) : [];
	return planFor(prepared, mode, { ...local, addedBeans } as LocalState, reviveDeleted);
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

/** A record the core's importer gives for a line: `BackupRecord`, tagged by `line`. */
type ImportedRecord =
	| { line: 'shot'; shot: Record<string, unknown> }
	| { line: 'bean'; bean: unknown }
	| { line: 'roaster'; roaster: unknown }
	| { line: 'recipe'; recipe: unknown }
	| { line: 'brewMethod'; method: unknown }
	| { line: 'profile'; profile: unknown }
	| { line: 'singleton'; kind: string; value: unknown };

/**
 * The records, read from the backup's lines through the core's importer and
 * each decoded to the shell's own type before the first write. A row the
 * shell can't take is a refusal for the whole restore: the plan counted it.
 *
 * Only the shots in `shots` are kept, as those are all a plan's step 7 can
 * write: every shot is still decoded, but a large backup's others go as they
 * are read, so what a restore holds is bounded by the history it keeps, not
 * by the backup.
 */
export async function readRecords(prepared: PreparedBackup, shots?: ReadonlySet<string>): Promise<BackupRecords> {
	const importer = new BackupImporter();
	const out: BackupRecords = {
		profiles: [],
		beans: [],
		roasters: [],
		shots: [],
		recipes: [],
		customMethods: [],
		settings: null,
		maintenance: null,
		visualizerPrefs: null
	};
	const obj = (v: unknown) => (typeof v === 'object' && v !== null ? (v as Record<string, unknown>) : null);
	/** A row the stores keep as the core typed it: an object with an id. */
	const withId = <T>(row: unknown): T | null => {
		const o = obj(row);
		return o && typeof o.id === 'string' && o.id.length > 0 ? (o as T) : null;
	};
	const one = <T>(row: unknown, what: string, decode: (row: unknown) => T | null): T => decodeRows([row], what, decode)[0];
	try {
		for await (const piece of prepared.lines()) {
			if (!('line' in piece)) {
				importer.pushOversizedLine();
				continue;
			}
			const json = importer.pushLine(piece.line);
			if (json === undefined) continue;
			const r = JSON.parse(json) as ImportedRecord;
			switch (r.line) {
				case 'shot': {
					const shot = coerceStoredShot(r.shot);
					if (!shot) {
						throw new Error(`A shot in this backup can't be read here (${String(r.shot.id ?? '?')}). Nothing was changed.`);
					}
					if (!shots || shots.has(shot.id)) out.shots.push(shot);
					break;
				}
				case 'bean':
					out.beans.push(one(r.bean, 'bean', coerceBean));
					break;
				case 'roaster':
					out.roasters.push(one(r.roaster, 'roaster', coerceRoaster));
					break;
				case 'recipe':
					out.recipes.push(one(r.recipe, 'recipe', withId<BrewRecipe>));
					break;
				case 'brewMethod':
					out.customMethods.push(
						one(r.method, 'brew method', (m) => {
							const method = withId<CustomBrewMethod>(m);
							return method && isCustomMethodId(method.id) ? method : null;
						})
					);
					break;
				case 'profile':
					out.profiles.push(one(r.profile, 'profile', withId<CremaProfile>));
					break;
				case 'singleton':
					if (r.kind === 'settings') out.settings = obj(r.value);
					else if (r.kind === 'maintenance') out.maintenance = obj(r.value);
					else if (r.kind === 'visualizerPrefs') out.visualizerPrefs = obj(r.value);
					break;
				default: {
					const never: never = r;
					throw new Error(`An unknown record in this backup: ${JSON.stringify(never)}`);
				}
			}
		}
	} finally {
		importer.free();
	}
	return out;
}

/** The backup shots a plan's step 7 can write. */
export function shotsTheRunWrites(plan: RestorePlan): Set<string> {
	const s = plan.shots;
	return new Set([...s.add, ...s.overwrite, ...s.revive, ...s.fill]);
}

/**
 * Every row decoded to the shell's type, or the whole restore refused before
 * its first write: the plan counted them all, so a row dropped here would be
 * one the report promised.
 */
export function decodeRows<T>(rows: unknown, what: string, decode: (row: unknown) => T | null): T[] {
	return (Array.isArray(rows) ? rows : []).map((row) => {
		const out = decode(row);
		if (out === null) {
			const id = typeof row === 'object' && row !== null ? (row as { id?: unknown }).id : undefined;
			throw new Error(`A ${what} in this backup can't be read here (${String(id ?? '?')}). Nothing was changed.`);
		}
		return out;
	});
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
	plan: RestorePlan['shots'],
	replace = false
): { next: StoredShot[]; series: StoredShot[] } {
	const theirs = new Map<string, StoredShot>();
	for (const s of backup) if (!theirs.has(s.id)) theirs.set(s.id, s);
	const remove = new Set(plan.remove);
	const here = new Set(local.map((s) => s.id));
	// A row the plan adds that has turned up here since (a pull's stub landing
	// during step 7) gets what the plan would have given it, had it been here:
	// Replace overwrites it; Merge keeps it, and fills its curve.
	const arrived = plan.add.filter((id) => here.has(id));
	const replaced = new Set([...plan.overwrite, ...plan.revive, ...(replace ? arrived : [])]);
	const fill = new Set([...plan.fill, ...(replace ? [] : arrived)]);
	const zero = new Set(plan.zeroDebit);
	const series: StoredShot[] = [];
	const kept = local
		.filter((s) => !remove.has(s.id))
		.map((s) => {
			const b = theirs.get(s.id);
			if (!b) return s;
			if (replaced.has(s.id)) {
				// The backup's row; with no curve of its own it keeps the one here,
				// as the same shot's curve: memory then matches what a reload shows.
				const row =
					b.record.samples.length === 0 && s.record.samples.length > 0
						? { ...b, record: { ...b.record, samples: s.record.samples } }
						: b;
				series.push(row);
				return row;
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
		.filter((id) => !here.has(id))
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
	/** The plan asks for the user's yes, and none was given: nothing ran. */
	| { kind: 'unconfirmed'; plan: RestorePlan }
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
			/** The marker couldn't be moved to `finishing`, so it was removed. */
			markerDropped: boolean;
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
	/** Test seam: the series writes of step 7, batched by default. */
	writeSeries?: (rows: readonly StoredShot[]) => Promise<void>;
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
	// Only a plan the core calls `ok` runs without the user having seen it.
	if (!opts.expected && plan.verdict !== Verdict.Ok) return { kind: 'unconfirmed', plan };
	// Read again, and checked again: a file changed since the plan is refused here.
	const records = await readRecords(prepared, shotsTheRunWrites(plan));

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
				now(),
				plan.beans.add,
				prepared.identity
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
				try {
					const bytes = await prepared.photo(beanId);
					if (!bytes) continue;
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
			// Computed again after the series land, over whatever this tab
			// recorded or edited meanwhile: the restore's own rows only change
			// the ids the plan names.
			const r = await getHistoryStore().restoreRows(
				(rows) => nextShots(rows, records.shots, plan.shots, replace),
				deps.writeSeries
			);
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

	// 9. Finishing: the deletes wait their hour. A marker left at `applying`
	// would say "didn't finish" at every boot, so a failed write tries again,
	// then goes: its deletes are lost, which only leaves old curves on disk.
	let markerDropped = false;
	if (!finishMarker(now()) && !finishMarker(now())) {
		dropMarker();
		markerDropped = true;
	}
	return { kind: 'done', plan, curvesFailed, singletonsSkipped, settingsApplied, markerDropped };
}
