/**
 * The shared restore matrix, and a restore stopped at every step, on the real
 * web stores (restore-safety design, "Tests": "Shared matrix" and "Crash
 * interruption"; required before PR7a ships).
 *
 * Each phase runs in a module registry of its own over the same localStorage
 * and IndexedDB, as page loads do: one writes the device's state through the
 * stores, the next plans and restores through the engine, and a third reads
 * what is left back from storage.
 */
import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { zipSync } from 'fflate';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeLocks } from '#lib/testing/fake-locks.js';
import type { RestorePlan, StorePlan, VisualizerSyncPrefs } from '#lib/core/crema-core.js';
import type { Bean, Roaster } from '#lib/bean/model.js';
import type { StoredShot } from '#lib/history/index.js';
import type { CremaProfile } from '#lib/profiles/model.js';
import type { BrewRecipe, CustomBrewMethod } from '#lib/core/crema-core.js';
import type { PreparedBackup, RestoreStep, WebMode } from './engine';

const FIXTURES = resolve(process.cwd(), '../core/de1-domain/tests/fixtures');
const WASM = new WebAssembly.Module(readFileSync(resolve(process.cwd(), 'src/lib/wasm/de1_wasm_bg.wasm')));

// ── Page loads ──────────────────────────────────────────────────────────────

/** A fresh page over the same storage: every module, and so every store, new. */
async function boot() {
	vi.resetModules();
	const wasm = await import('#lib/wasm/de1_wasm.js');
	wasm.initSync({ module: WASM });
	const [engine, backup, bean, model, images, history, series, profiles, recipes, methods, settings, maintenance, viz, queue, marks, marker, restoreBoot, tabs] =
		await Promise.all([
			import('./engine'),
			import('#lib/backup/index.js'),
			import('#lib/bean/index.js'),
			import('#lib/bean/model.js'),
			import('#lib/bean/image-storage.js'),
			import('#lib/history/index.js'),
			import('#lib/history/series-store.js'),
			import('#lib/profiles/index.js'),
			import('#lib/brew/recipes.svelte.js'),
			import('#lib/brew/custom-methods.svelte.js'),
			import('#lib/settings/index.js'),
			import('#lib/maintenance/index.js'),
			import('#lib/visualizer/sync-config.js'),
			import('#lib/services/queue-store.js'),
			import('#lib/visualizer/upload-marks.js'),
			import('./marker'),
			import('./boot'),
			import('./tab-safety')
		]);
	// What the app's start-up does before anything reads settings.
	settings.getSettingsStore().migrateHotWater();
	// Every store reads its keys as the app starts, so their health is known.
	bean.getBeanStore();
	profiles.getProfileStore();
	recipes.getRecipeStore();
	methods.getCustomMethodStore();
	maintenance.getMaintenanceStore();
	await history.getHistoryStore().seriesReady();
	return {
		wasm,
		engine,
		backup,
		model,
		images,
		series,
		viz,
		queue,
		marks,
		marker,
		restoreBoot,
		tabs,
		beans: bean.getBeanStore,
		history: history.getHistoryStore,
		profiles: profiles.getProfileStore,
		recipes: recipes.getRecipeStore,
		methods: methods.getCustomMethodStore,
		settings: settings.getSettingsStore,
		settingsToCommon: settings.settingsToCommon,
		maintenance: maintenance.getMaintenanceStore
	};
}
type App = Awaited<ReturnType<typeof boot>>;

function depsFor(app: App, more: Partial<Parameters<App['engine']['runRestore']>[3]> = {}) {
	return { singletons: app.backup.webSingletons().sink, locks: new FakeLocks(), machineWaitMs: 200, progressMs: 1_000, ...more };
}

beforeEach(() => {
	// One clock, so a store's defaults read the same on every page load.
	vi.useFakeTimers({ toFake: ['Date'], now: 1_800_000_000_000 });
	localStorage.clear();
	// A device with nothing in IndexedDB either.
	(globalThis as { indexedDB?: IDBFactory }).indexedDB = new IDBFactory();
	vi.stubGlobal(
		'BroadcastChannel',
		class {
			postMessage() {}
			close() {}
		}
	);
});
afterEach(() => {
	vi.useRealTimers();
	vi.unstubAllGlobals();
	vi.restoreAllMocks();
});

// ── Backups, built from the fixture's rows as tests/backup_plan.rs builds them ─

/** The web fixture's first row of each kind. */
const TEMPLATES = new Map<string, Record<string, unknown>>();
for (const line of readFileSync(`${FIXTURES}/backup/web-backup.crema.jsonl`, 'utf8').split('\n')) {
	if (!line) continue;
	const row = JSON.parse(line) as Record<string, unknown>;
	if (!TEMPLATES.has(row.kind as string)) TEMPLATES.set(row.kind as string, row);
}
const template = (kind: string): Record<string, any> => structuredClone(TEMPLATES.get(kind)!);
const SHOT_SAMPLES = (template('shot').record as { samples: unknown[] }).samples;
const BEAN_DEBIT = template('shot').bagDebit as number;

interface ShotSpec {
	id: string;
	samples?: boolean;
	completedAt?: number;
	pinned?: boolean;
	bean?: string;
}
interface BackupSpec {
	beans?: string[];
	roasters?: string[];
	profiles?: string[];
	recipes?: string[];
	customMethods?: string[];
	shots?: ShotSpec[];
	photos?: string[];
	omitCounts?: string[];
	settings?: { _shell?: string };
	profileMeta?: Record<string, unknown>;
	recipeMeta?: Record<string, unknown>;
	maintenance?: boolean;
	visualizerPrefs?: boolean;
}

/** Whose copy a row is: the backup's or this device's. */
const tag = (from: 'backup' | 'here', id: string) => `${from} ${id}`;

/**
 * A case's rows as JSONL, as `backup_of` in backup_plan.rs writes them. Each
 * row names its origin (`name`, `label`, the shot's notes), which the plan
 * never reads, so the read-back can tell a kept row from an overwritten one.
 */
function jsonlOf(spec: BackupSpec, from: 'backup' | 'here'): Uint8Array {
	const ids = (key: 'beans' | 'roasters' | 'profiles' | 'recipes' | 'customMethods') => spec[key] ?? [];
	const shots = spec.shots ?? [];
	const header = template('crema-backup/v1');
	for (const [key, n] of [
		['beanCount', ids('beans').length],
		['roasterCount', ids('roasters').length],
		['shotCount', shots.length],
		['profileCount', ids('profiles').length],
		['recipeCount', ids('recipes').length],
		['customMethodCount', ids('customMethods').length]
	] as const) {
		header[key] = n;
	}
	for (const key of spec.omitCounts ?? []) delete header[key];
	const lines: Record<string, unknown>[] = [header];
	if (spec.settings) {
		const line = template('settings');
		if (spec.settings._shell !== undefined) line._shell = spec.settings._shell;
		else delete line._shell;
		lines.push(line);
	}
	for (const key of ['profileMeta', 'recipeMeta'] as const) {
		if (spec[key]) lines.push({ ...spec[key], kind: key });
	}
	for (const key of ['maintenance', 'visualizerPrefs'] as const) {
		if (spec[key] === true) lines.push(template(key));
	}
	const row = (kind: string, id: string, label: 'name' | 'label' = 'name') => ({
		...template(kind),
		id,
		[label]: tag(from, id)
	});
	lines.push(...ids('roasters').map((id) => row('roaster', id)));
	lines.push(...ids('beans').map((id) => row('bean', id)));
	for (const shot of shots) {
		const line = row('shot', shot.id);
		delete line.name;
		line.metadata = { ...line.metadata, notes: tag(from, shot.id) };
		if (shot.bean !== undefined) line.bean = { ...line.bean, beanId: shot.bean };
		else delete line.bean;
		if (shot.samples !== true) line.record = { ...line.record, samples: [] };
		line.completedAt = shot.completedAt ?? 1;
		if (shot.pinned === true) line.pinned = true;
		lines.push(line);
	}
	lines.push(...ids('customMethods').map((id) => row('brewMethod', id, 'label')));
	lines.push(...ids('recipes').map((id) => row('recipe', id)));
	lines.push(...ids('profiles').map((id) => row('profile', id)));
	return new TextEncoder().encode(lines.map((l) => JSON.stringify(l) + '\n').join(''));
}

/** A `.crema.zip` of the case's rows and its photos, read the way the file picker reads one. */
function backupFile(spec: BackupSpec): Uint8Array {
	const files: Record<string, Uint8Array> = { 'backup.jsonl': jsonlOf(spec, 'backup') };
	for (const id of spec.photos ?? []) files[`images/${id}`] = new TextEncoder().encode(`photo of ${id}`);
	return zipSync(files);
}

// ── A device's state, written through the stores ───────────────────────────

interface LocalShotSpec {
	id: string;
	hasSamples?: boolean;
	pinned?: boolean;
	deleted?: boolean;
	completedAt?: number;
}
interface LocalSpec {
	shell?: string;
	historyCapacity?: number;
	machineBusy?: boolean;
	health?: { store: string; health: 'damaged' | 'unavailable' }[];
	beans?: string[];
	roasters?: string[];
	profiles?: string[];
	recipes?: string[];
	customMethods?: string[];
	shots?: LocalShotSpec[];
	photos?: string[];
	beansMissingPhoto?: string[];
	profileMeta?: { pinned?: string[]; hiddenBuiltins?: string[] };
	recipeMeta?: { defaults?: Record<string, string>; hiddenBuiltins?: string[] };
	common?: { themeMode?: string };
	visualizerPrefs?: Partial<VisualizerSyncPrefs>;
	totalLitres?: number;
	activeBeanId?: string;
}

/** The web store a matrix health entry names, by the key it reads. */
const HEALTH_KEYS: Record<string, { key: string; web: string }> = {
	'bean library': { key: 'crema.beans.v1', web: 'beans' }
};

/** Write `spec` through the stores, as a user's edits and imports would. */
async function writeLocal(spec: LocalSpec): Promise<void> {
	const known = new Set([
		'shell',
		'historyCapacity',
		'machineBusy',
		'health',
		'beans',
		'roasters',
		'profiles',
		'recipes',
		'customMethods',
		'shots',
		'photos',
		'beansMissingPhoto',
		'profileMeta',
		'recipeMeta',
		'common',
		'visualizerPrefs',
		'totalLitres',
		'activeBeanId'
	]);
	for (const key of Object.keys(spec)) if (!known.has(key)) throw new Error(`a local field this test can't build: ${key}`);
	for (const key of Object.keys(spec.common ?? {})) if (key !== 'themeMode') throw new Error(`a common field this test can't build: ${key}`);

	const app = await boot();
	const shots = spec.shots ?? [];
	// The shell's own rows, decoded by the engine's reader from the same template.
	const rows = app.engine.readRecords(
		app.engine.prepareBackup(
			jsonlOf(
				{
					beans: spec.beans,
					roasters: spec.roasters,
					profiles: spec.profiles,
					recipes: spec.recipes,
					customMethods: spec.customMethods,
					shots: shots.map((s) => ({ id: s.id, samples: s.hasSamples, completedAt: s.completedAt ?? 2, pinned: s.pinned }))
				},
				'here'
			),
			'local'
		)
	);
	const withPhoto = new Set([...(spec.photos ?? []), ...(spec.beansMissingPhoto ?? [])]);
	const beans = rows.beans.map((b) => (withPhoto.has(b.id) ? { ...b, imageRef: app.images.refForBean(b.id) } : b));
	expect(app.beans().restoreLibrary(beans, rows.roasters, false)).toBe(true);
	if (spec.activeBeanId) app.beans().setActiveBean(spec.activeBeanId);
	for (const id of spec.photos ?? []) {
		await app.images.getBeanImageStore().put(app.images.refForBean(id), new Blob([`here ${id}`]));
	}
	expect(app.profiles().restoreCustom(rows.profiles)).toBe(true);
	if (spec.profileMeta) {
		expect(app.profiles().restoreMeta(spec.profileMeta.pinned ?? [], spec.profileMeta.hiddenBuiltins ?? [])).toBe(true);
	}
	expect(app.methods().restore(rows.customMethods)).toBe(true);
	const meta = spec.recipeMeta
		? { defaults: spec.recipeMeta.defaults ?? {}, hidden: spec.recipeMeta.hiddenBuiltins ?? [] }
		: null;
	expect(app.recipes().restore(rows.recipes, meta)).toBe(true);

	// History, in one write, with the curves in IndexedDB.
	const history = app.history();
	const room = spec.historyCapacity == null ? 0 : history.capacity - spec.historyCapacity;
	// Rows that only take up room: curveless, and in no backup.
	const filler = fillerShot(app);
	const fillers = Array.from({ length: room }, (_, i) => ({ ...filler, id: `filler-${i}` }));
	// A shot deleted here keeps its tombstone only if it reached Visualizer.
	const deleted = new Set(shots.filter((x) => x.deleted).map((x) => x.id));
	const live = [...rows.shots, ...fillers].map((s) => (deleted.has(s.id) ? { ...s, visualizerId: `viz-${s.id}` } : s));
	if (live.length > 0) {
		const r = await history.restoreRows(() => ({ next: live, series: live }));
		expect(r).toEqual({ stored: true, curvesFailed: 0 });
	}
	for (const id of deleted) {
		history.delete(id, { deleteRemote: true });
		app.queue.enqueueEntry({ entity: 'shot', id, op: 'delete', visualizerId: `viz-${id}` });
	}

	const settings = app.settings();
	if (spec.common?.themeMode) settings.set('theme', spec.common.themeMode as never);
	if (spec.visualizerPrefs) app.viz.applyVisualizerSyncPrefs({ ...app.viz.visualizerSyncPrefs(), ...spec.visualizerPrefs });
	if (spec.totalLitres != null) {
		expect(app.maintenance().replaceAll({ ...app.maintenance().current, totalLitres: spec.totalLitres })).toBe(true);
	}
	await history.seriesReady();
}

/** A history row for a device with no shots of its own named. */
function fillerShot(app: App): StoredShot {
	const [shot] = app.engine.readRecords(
		app.engine.prepareBackup(jsonlOf({ shots: [{ id: 'filler', completedAt: 2 }] }, 'here'), 'filler')
	).shots;
	return shot;
}

// ── Reading a device back ───────────────────────────────────────────────────

const IMAGE_PREFIX = 'idb:bean-image:';
const sorted = (xs: Iterable<string>) => [...xs].sort();
const byId = <T extends { id: string }>(rows: readonly T[]) =>
	Object.fromEntries([...rows].sort((a, b) => a.id.localeCompare(b.id)).map((r) => [r.id, r]));

/** Everything a restore writes, as storage holds it, by the step that writes it. */
async function readBack(app: App) {
	const history = app.history();
	await history.seriesReady();
	const recipes = app.recipes();
	const recipeMeta = recipes.backupMeta();
	const profileMeta = app.profiles().backupMeta();
	return {
		marker: app.marker.readMarker(),
		tombstones: {
			queued: sorted(
				app.queue
					.readQueue()
					.entries.filter((e) => e.entity === 'shot' && e.op === 'delete')
					.map((e) => e.id)
			),
			parked: sorted(app.marks.parkedTombstones())
		},
		photos: sorted(
			(await app.images.getBeanImageStore().keys())
				.filter((k) => k.startsWith(IMAGE_PREFIX))
				.map((k) => k.slice(IMAGE_PREFIX.length))
		),
		recipes: {
			methods: byId(app.methods().everything as CustomBrewMethod[]),
			recipes: byId(recipes.ownRecipes as BrewRecipe[]),
			meta: { defaults: recipeMeta.defaults ?? {}, hidden: sorted(recipeMeta.hiddenBuiltins ?? []) }
		},
		beans: {
			beans: byId(app.beans().beans as Bean[]),
			roasters: byId(app.beans().roasters as Roaster[]),
			active: app.beans().activeBeanId
		},
		profiles: {
			profiles: byId(app.profiles().customProfiles as CremaProfile[]),
			meta: { pinned: sorted(profileMeta.pinned), hidden: sorted(profileMeta.hiddenBuiltins) }
		},
		shots: {
			rows: byId(history.rawAll),
			series: sorted((await app.series.getAllSeries()).keys())
		},
		settings: {
			settings: app.settings().current,
			maintenance: app.maintenance().current,
			visualizer: app.viz.visualizerSyncPrefs()
		}
	};
}
type Device = Awaited<ReturnType<typeof readBack>>;

/** A fresh page's view of storage. */
const reload = async () => readBack(await boot());

// ── The matrix ─────────────────────────────────────────────────────────────

interface MatrixCase {
	name: string;
	store: string;
	mode: 'merge' | 'replace';
	opts?: { reviveDeleted?: boolean };
	local: LocalSpec;
	backup: BackupSpec;
	expect: Record<string, unknown>;
}
const MATRIX = JSON.parse(readFileSync(`${FIXTURES}/restore/matrix.json`, 'utf8')) as { cases: MatrixCase[] };

/**
 * Cases a web device can't be: say why. Keep these few; each is covered by
 * the Rust run and, where it can be, by Android's.
 */
const SKIP: Record<string, string> = {
	// A web device's LocalState always says `shell: "web"`: an Android device's
	// extras rule is RestoreMatrixTest.kt's.
	'settings: replace applies, extras only from the same shell': 'the device is Android',
	"settings: a line with no _shell is Android's": 'the device is Android'
};

/**
 * Pointers that read differently on web, and why. `null` drops the pointer;
 * a function gives the web's value.
 */
const ON_WEB: Record<string, Record<string, ((want: unknown, room: number) => unknown) | null>> = {
	// Web's cap is fixed (300): the case's cap is made by filling the backup
	// with that many more unpinned rows, which the refusal counts.
	'shots: replace refuses more unpinned rows than history keeps': {
		'/refusals/0/count': (want, room) => (want as number) + room
	},
	// The shell names its stores: web's bean library is `beans`.
	"refused: a store that couldn't be read": {
		'/refusals/0/store': (want) => HEALTH_KEYS[want as string].web
	}
};

function pointer(value: unknown, path: string): unknown {
	let at = value;
	for (const raw of path.split('/').slice(1)) {
		const key = raw.replace(/~1/g, '/').replace(/~0/g, '~');
		if (at === null || typeof at !== 'object') return undefined;
		at = (at as Record<string, unknown>)[key];
	}
	return at;
}

const webMode = (m: MatrixCase['mode']): WebMode => (m === 'replace' ? 'wipe' : 'merge');

/** The rows a store plan leaves: `local − remove + overwrite + add`, each with whose copy it is. */
function rowsAfter(before: Record<string, { id: string }>, plan: StorePlan, label: (r: any) => unknown): Record<string, unknown> {
	const want: Record<string, unknown> = Object.fromEntries(Object.entries(before).map(([id, r]) => [id, label(r)]));
	for (const id of plan.remove) delete want[id];
	for (const id of [...plan.overwrite, ...plan.add]) want[id] = tag('backup', id);
	return Object.fromEntries(Object.entries(want).sort(([a], [b]) => a.localeCompare(b)));
}
const labels = (rows: Record<string, { id: string }>, label: (r: any) => unknown) =>
	Object.fromEntries(Object.entries(rows).map(([id, r]) => [id, label(r)]));

/** What the device must hold once `plan` has run over `before`, store by store. */
function expectApplied(plan: RestorePlan, before: Device, after: Device, backup: BackupSpec, app: App): void {
	const name = (r: { name: string }) => r.name;
	const label = (r: { label: string }) => r.label;
	expect(labels(after.beans.beans, name), 'beans').toEqual(rowsAfter(before.beans.beans, plan.beans, name));
	expect(labels(after.beans.roasters, name), 'roasters').toEqual(rowsAfter(before.beans.roasters, plan.roasters, name));
	expect(labels(after.profiles.profiles, name), 'profiles').toEqual(rowsAfter(before.profiles.profiles, plan.profiles, name));
	expect(labels(after.recipes.recipes, name), 'recipes').toEqual(rowsAfter(before.recipes.recipes, plan.recipes, name));
	expect(labels(after.recipes.methods, label), 'methods').toEqual(rowsAfter(before.recipes.methods, plan.customMethods, label));
	expect(after.beans.active, 'active bean').toBe(plan.clearActiveBean ? null : before.beans.active);

	// History: what each row is, whose copy, and whether its curve is stored.
	type Row = { from: unknown; deleted: boolean; curve: boolean; debit: unknown };
	const row = (s: StoredShot): Row => ({
		from: s.metadata.notes,
		deleted: s.deletedAt != null,
		curve: s.record.samples.length > 0,
		debit: s.bagDebit ?? null
	});
	const theirs = new Map((backup.shots ?? []).map((s) => [s.id, s]));
	const fromBackup = (id: string, debit: unknown = BEAN_DEBIT): Row => ({
		from: tag('backup', id),
		deleted: false,
		curve: theirs.get(id)?.samples === true,
		debit
	});
	const want: Record<string, Row> = Object.fromEntries(
		Object.entries(before.shots.rows).map(([id, s]) => [id, row(s)])
	);
	for (const id of plan.shots.remove) delete want[id];
	// The backup's row; with no curve of its own it keeps the device's, the
	// same shot's curve, in memory and after a reload alike.
	for (const id of [...plan.shots.overwrite, ...plan.shots.revive]) {
		const mine = before.shots.rows[id];
		want[id] = { ...fromBackup(id), curve: fromBackup(id).curve || (mine?.record.samples.length ?? 0) > 0 };
	}
	for (const id of plan.shots.fill) want[id] = { ...want[id], curve: true };
	for (const id of plan.shots.add) want[id] = fromBackup(id, plan.shots.zeroDebit.includes(id) ? 0 : BEAN_DEBIT);
	expect(labels(after.shots.rows, row), 'shots').toEqual(Object.fromEntries(Object.entries(want).sort(([a], [b]) => a.localeCompare(b))));
	// Every listed curve is in IndexedDB; removed rows' curves wait for the marker.
	const curves = Object.entries(want).filter(([, r]) => r.curve).map(([id]) => id);
	expect(after.shots.series, 'series').toEqual(sorted(new Set([...before.shots.series, ...curves])));

	// Photos: put now, removed only by the marker, an hour on.
	expect(after.photos, 'photos').toEqual(sorted(new Set([...before.photos, ...plan.photos.put])));

	const meta = plan.profileMeta.action === 'apply'
		? { pinned: sorted(plan.profileMeta.pinned), hidden: sorted(plan.profileMeta.hiddenBuiltins) }
		: before.profiles.meta;
	expect(after.profiles.meta, 'profile meta').toEqual(meta);
	const recipeMeta = plan.recipeMeta.action === 'apply'
		? { defaults: plan.recipeMeta.defaults, hidden: sorted(plan.recipeMeta.hiddenBuiltins.filter((id) => id.startsWith('builtin:'))) }
		: before.recipes.meta;
	expect(after.recipes.meta, 'recipe meta').toEqual(recipeMeta);

	// The singletons: the backup's line where the plan applies it, this device's where it keeps.
	const common = (s: Device['settings']['settings']) => app.settingsToCommon(s);
	if (backup.settings && plan.settings.common === 'apply') {
		const line = template('settings').common as Record<string, unknown>;
		expect(common(after.settings.settings), 'settings').toMatchObject({
			themeMode: line.themeMode,
			tempUnit: line.tempUnit,
			maxShotDurationS: line.maxShotDurationS
		});
	} else {
		expect(after.settings.settings, 'settings').toEqual(before.settings.settings);
	}
	const maintenance = template('maintenance');
	delete maintenance.kind;
	if (backup.maintenance && plan.maintenance === 'apply') {
		expect(after.settings.maintenance, 'maintenance').toMatchObject(maintenance);
	} else {
		expect(after.settings.maintenance, 'maintenance').toEqual(before.settings.maintenance);
	}
	if (backup.visualizerPrefs && plan.visualizerPrefs === 'apply') {
		const line = template('visualizerPrefs');
		delete line.kind;
		const guarded = JSON.parse(
			app.wasm.guardRestoredVisualizerPrefs(JSON.stringify(before.settings.visualizer), JSON.stringify(line))
		) as { apply: VisualizerSyncPrefs };
		expect(after.settings.visualizer, 'visualizer prefs').toEqual(guarded.apply);
	} else {
		expect(after.settings.visualizer, 'visualizer prefs').toEqual(before.settings.visualizer);
	}
	expect(after.tombstones.queued.filter((id) => plan.tombstones.cancel.includes(id)), 'tombstones').toEqual([]);
}

describe('the shared matrix, on real web stores', () => {
	it('reads every case, and every web exception names one', () => {
		expect(MATRIX.cases.length).toBeGreaterThanOrEqual(40);
		const names = new Set(MATRIX.cases.map((c) => c.name));
		for (const name of [...Object.keys(SKIP), ...Object.keys(ON_WEB)]) expect(names, name).toContain(name);
	});

	for (const c of MATRIX.cases) {
		const skipped = SKIP[c.name];
		(skipped ? it.skip : it)(c.name, async () => {
			const mode = webMode(c.mode);
			const revive = c.opts?.reviveDeleted ?? false;
			// Web's cap is fixed: the case's room is made with rows of filler.
			const capacity = (await boot()).history().capacity;
			const room = c.local.historyCapacity == null ? 0 : capacity - c.local.historyCapacity;
			const local: LocalSpec = c.mode === 'merge' ? c.local : { ...c.local, historyCapacity: undefined };
			const backup: BackupSpec =
				c.mode === 'replace' && room > 0
					? { ...c.backup, shots: [...(c.backup.shots ?? []), ...Array.from({ length: room }, (_, i) => ({ id: `filler-${i}` }))] }
					: c.backup;

			await writeLocal(local);
			const before = await reload();
			for (const h of c.local.health?.filter((x) => x.health === 'damaged') ?? []) {
				localStorage.setItem(HEALTH_KEYS[h.store].key, '{"a damaged');
			}

			// A store that can't be read, from the page load on.
			const unavailable = new Set(
				(c.local.health ?? []).filter((h) => h.health === 'unavailable').map((h) => HEALTH_KEYS[h.store].key)
			);
			const proto = Object.getPrototypeOf(localStorage) as Storage;
			const getItem = proto.getItem;
			const spy = unavailable.size
				? vi.spyOn(proto, 'getItem').mockImplementation(function (this: Storage, key: string) {
						if (unavailable.has(key)) throw new Error('read failed');
						return getItem.call(this, key);
					})
				: null;
			const releases: (() => void)[] = [];
			let app: App;
			let prepared: PreparedBackup;
			let plan: RestorePlan;
			let photoPuts: ReturnType<typeof vi.spyOn>;
			try {
				app = await boot();
				photoPuts = vi.spyOn(app.images.getBeanImageStore(), 'put');
				prepared = app.engine.prepareBackup(backupFile(backup), c.name);
				const locks = new FakeLocks();
				// A shot running in another tab.
				if (c.local.machineBusy) releases.push(await locks.hold(app.tabs.MACHINE_BUSY_LOCK, 'shared'));
				plan = await app.engine.planNow(prepared, mode, revive, locks);
			} finally {
				spy?.mockRestore();
				for (const r of releases) r();
			}

			const changes = ON_WEB[c.name] ?? {};
			for (const [path, want] of Object.entries(c.expect)) {
				const adjust = changes[path];
				if (adjust === null) continue;
				const webWant = adjust ? adjust(want, room) : want;
				expect(pointer(plan, path), `${path}`).toEqual(webWant);
			}

			if (plan.verdict === 'refused') {
				// Nothing runs, and nothing changed.
				expect(await reload()).toEqual(before);
				// The zip's photos were read before the verdict: none reached the photo store
				// (fake-indexeddb drops a Blob's bytes, so the store's writes are watched instead).
				expect(photoPuts, 'photo writes').not.toHaveBeenCalled();
				return;
			}
			const out = await app.engine.runRestore(prepared, mode, { reviveDeleted: revive, expected: plan }, depsFor(app));
			expect(out.kind).toBe('done');
			const after = await reload();
			expectApplied(plan, before, after, backup, app);
			expect(after.marker).toMatchObject({
				phase: 'finishing',
				deletes: { shots: plan.shots.remove, photos: plan.photos.remove }
			});

			// An hour on, a boot runs what the marker owes: removed rows'
			// curves and removed beans' photos go, and nothing else.
			const later = await boot();
			const deleted = await later.marker.checkMarkerAtBoot({
				now: (after.marker!.finishingAt ?? 0) + later.marker.DELETE_AFTER_MS,
				restoreRunning: async () => false,
				listedShots: () => new Set(later.history().rawAll.map((s) => s.id)),
				listedBeans: () => new Set(later.beans().beans.map((b) => b.id)),
				deleteSeries: later.series.deleteSeries,
				deleteCapture: async () => {},
				deletePhoto: (id) => later.images.getBeanImageStore().delete(later.images.refForBean(id))
			});
			expect(deleted.kind).toBe('deleted');
			const settled = await reload();
			expect(settled.marker).toBeNull();
			expect(settled.photos, 'photos an hour on').toEqual(after.photos.filter((id) => !plan.photos.remove.includes(id)));
			expect(settled.shots.series, 'series an hour on').toEqual(
				after.shots.series.filter((id) => !plan.shots.remove.includes(id))
			);
		});
	}
});

// ── Stopped at every step ───────────────────────────────────────────────────

/** The engine's steps, in order; each writes the part of the device it names. */
const STEPS = ['marker', 'tombstones', 'photos', 'recipes', 'beans', 'profiles', 'shots', 'settings'] as const satisfies readonly RestoreStep[];
const BUILTIN_PROFILE = '019e5fb5-63b5-7333-a2ac-34b9f4f0e604';

/** A device with something in every store a restore writes. */
const DEVICE: LocalSpec = {
	beans: ['here-1', 'here-2'],
	roasters: ['r-here'],
	profiles: ['p-here'],
	recipes: ['recipe:here'],
	customMethods: ['custom:here'],
	shots: [
		{ id: 'sh1', hasSamples: true },
		{ id: 'dead', hasSamples: true, deleted: true }
	],
	photos: ['here-1', 'here-2'],
	activeBeanId: 'here-2'
};

/** A backup that changes every one of them, in both modes. */
const BACKUP: BackupSpec = {
	beans: ['here-1', 'new-1'],
	roasters: ['r-new'],
	profiles: ['p-new'],
	recipes: ['recipe:new'],
	customMethods: ['custom:new'],
	shots: [
		{ id: 'dead', samples: true, completedAt: 3 },
		{ id: 'sb1', samples: true, completedAt: 4, bean: 'here-1' },
		{ id: 'sb2', samples: true, completedAt: 5, bean: 'new-1' }
	],
	photos: ['here-1', 'new-1'],
	settings: { _shell: 'web' },
	maintenance: true,
	visualizerPrefs: true,
	profileMeta: { pinned: [BUILTIN_PROFILE], hiddenBuiltins: [] },
	recipeMeta: { defaults: { pourover: 'recipe:new' }, hiddenBuiltins: [] }
};

/** The device written afresh, on empty storage. */
async function freshDevice(): Promise<Device> {
	localStorage.clear();
	(globalThis as { indexedDB?: IDBFactory }).indexedDB = new IDBFactory();
	await writeLocal(DEVICE);
	return reload();
}

/** Plan on a fresh page and run what it says, stopping at `failAt`. */
async function restoreOnce(mode: WebMode, failAt?: RestoreStep) {
	const app = await boot();
	const prepared = app.engine.prepareBackup(backupFile(BACKUP), 'every store');
	// Merge brings the deleted shot back, so both modes cancel a tombstone.
	const revive = mode === 'merge';
	const plan = await app.engine.planNow(prepared, mode, revive, new FakeLocks());
	expect(plan.verdict).not.toBe('refused');
	const out = await app.engine.runRestore(prepared, mode, { reviveDeleted: revive, expected: plan }, depsFor(app, { failAt }));
	return { plan, out };
}

const asSets = (m: Device['marker']) => m && { phase: m.phase, shots: sorted(m.deletes.shots), photos: sorted(m.deletes.photos) };

describe.each(['merge', 'wipe'] as const)('a %s restore stopped at each step', (mode) => {
	it('changes every part of the device when it runs clean', async () => {
		const old = await freshDevice();
		const { out } = await restoreOnce(mode);
		expect(out.kind).toBe('done');
		const fresh = await reload();
		for (const part of STEPS) expect(fresh[part], part).not.toEqual(old[part]);
	});

	for (const [at, step] of STEPS.entries()) {
		it(`at ${step}: each store is wholly old or new, nothing is deleted, and a re-run finishes it`, async () => {
			// What a clean run leaves.
			const old = await freshDevice();
			expect((await restoreOnce(mode)).out.kind).toBe('done');
			const fresh = await reload();

			expect(await freshDevice()).toEqual(old);
			const { plan, out } = await restoreOnce(mode, step);
			expect(out).toMatchObject({ kind: 'stopped', at: step, landed: STEPS.slice(0, at) });

			const after = await reload();
			// The marker stays at `applying`, owing what the plan removes; a
			// restore stopped before its marker wrote nothing at all.
			if (step === 'marker') expect(after.marker).toBeNull();
			else
				expect(after.marker).toMatchObject({
					phase: 'applying',
					deletes: { shots: plan.shots.remove, photos: plan.photos.remove }
				});
			for (const [i, part] of STEPS.entries()) {
				if (part === 'marker') continue;
				expect(after[part], part).toEqual(i < at ? fresh[part] : old[part]);
			}
			// No delete ran: every curve and photo the device had is still stored.
			expect(after.shots.series).toEqual(expect.arrayContaining(old.shots.series));
			expect(after.photos).toEqual(expect.arrayContaining(old.photos));

			// The next boot says the restore didn't finish, and deletes nothing.
			expect((await (await boot()).restoreBoot.checkRestoreMarker()).kind).toBe(step === 'marker' ? 'none' : 'interrupted');
			expect(await reload()).toEqual(after);

			// Run again, it ends where a clean run ends.
			expect((await restoreOnce(mode)).out.kind).toBe('done');
			const final = await reload();
			expect({ ...final, marker: null }).toEqual({ ...fresh, marker: null });
			expect(asSets(final.marker)).toEqual(asSets(fresh.marker));
		});
	}
});
