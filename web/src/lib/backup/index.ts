/**
 * `#lib/backup/index.js` — whole-app local backup & restore, the web side of the
 * cross-shell `crema-backup/v1` bundle (core `export_backup_jsonl_from_json` /
 * the line-tagged JSONL; mirrors Android's `MainViewModel.backupBundleJson`).
 *
 * A `.crema.zip` bundles two parts:
 * - `backup.jsonl` — line-delimited JSON (NDJSON) = custom profiles +
 *   beans/roasters + shot history + the portable settings subset, serialised by
 *   the Rust core so web and Android emit **identical bytes** — a backup moves
 *   between devices.
 * - `images/<beanId>` — every bean's photo blob (from IndexedDB). Photos ARE
 *   bundled; restore unzips them and replays each back into IndexedDB keyed by
 *   the bean id.
 *
 * Excluded from the bundle: Visualizer OAuth tokens (separate storage) and any
 * per-device state. The web has no persisted BLE address (Web Bluetooth
 * re-pairs each session), so the whole settings bundle is portable; it's tagged
 * `_shell: 'web'` so a restore only re-applies settings on a matching shell
 * (the cross-shell DATA still restores either way).
 */

import { exportBackupBundle, guardRestoredVisualizerPrefs } from '#lib/wasm/de1_wasm.js';
import { getBeanStore } from '#lib/bean/index.js';
import { getHistoryStore } from '#lib/history/index.js';
import { refusedNoSamples } from '#lib/history/model.js';
import {
	getSettingsStore,
	settingsToCommon,
	applyCommonToSettings,
	sanitizeFlat,
	settingsPlatformExtras,
	DEFAULT_SETTINGS,
	type Settings
} from '#lib/settings/index.js';
import { getProfileStore } from '#lib/profiles/index.js';
import { getRecipeStore, type RecipeBackupMeta } from '#lib/brew/recipes.svelte.js';
import { getCustomMethodStore } from '#lib/brew/custom-methods.svelte.js';
import { getMaintenanceStore } from '#lib/maintenance/index.js';
import { visualizerSyncPrefs, applyVisualizerSyncPrefs } from '#lib/visualizer/sync-config.js';
import { getBeanImageStore, refForBean } from '#lib/bean/image-storage.js';
import { strToU8, strFromU8, zipSync } from 'fflate';
import { assertImportSize, unzipImportSync } from '#lib/utils/import-limits.js';
import { readStored, unreadableStores } from '#lib/utils/storage.js';
import { isAllowedWebhookUrl } from '#lib/services/webhook-url.js';
import { downloadBlob, filenameStamp } from '#lib/utils/download.js';
import type { Bean, Roaster } from '#lib/bean/model.js';
import type { StoredShot } from '#lib/history/index.js';
import type { CremaProfile } from '#lib/profiles/model.js';
import { PlanAction, Verdict, type RestorePlan } from '#lib/core/crema-core.js';
import {
	planNow,
	prepareBackup,
	runRestore,
	settingsPristine,
	type RestoreDeps,
	type RestoreOutcome,
	type SingletonSink
} from '#lib/restore/engine.js';
import type {
	BrewRecipe,
	CommonSettings,
	CustomBrewMethod,
	GuardedVisualizerPrefs,
	MaintenanceState,
	VisualizerSyncPrefs
} from '#lib/core/crema-core.js';

/** localStorage key for the user's custom profiles (a `CremaProfile[]`). Mirror
 *  of `profiles/store`'s `CUSTOM_KEY` — read directly so the bundle carries the
 *  exact custom set without filtering the built-ins back out of the merged list. */
const CUSTOM_PROFILES_KEY = 'crema.profiles.custom.v1';

/** Human label for the backup header + filename. A user-settable device name is
 *  a later polish (it pairs with Android's `deviceLabel()`); v1 is a constant. */
const WEB_DEVICE_LABEL = 'Crema (web)';

/** What a backup contains, for the UI's pre-export summary / empty check. */
export interface BackupContents {
	profiles: number;
	beans: number;
	roasters: number;
	shots: number;
	/** Rows a built backup left out because they can't be read. */
	omitted?: readonly OmittedBackupRow[];
	/** Shots a built backup carries without their profile, by id. */
	degraded?: readonly string[];
	/** Machine shots a built backup carries with no curve. */
	noCurves?: number;
	/** Stores that couldn't be read this session, so aren't in it, by name. */
	missing?: readonly string[];
}

/** One row a backup left out (the core's `OmittedBackupRow`). */
export interface OmittedBackupRow {
	/** The line kind: `shot`, `bean`, `roaster`, `recipe`, `brewMethod`, `profile`. */
	kind: string;
	/** Where the row sat in the list handed to the core. */
	index: number;
	id: string | null;
}

/** The core's `BackupExport`. */
interface BackupExport {
	jsonl: string;
	degraded: string[];
	omitted: OmittedBackupRow[];
}

/** A human one-liner for a completed backup. Falls back to "settings &
 *  favourites" when there's no library data (settings / pins always ride along). */
export function describeBackup(c: BackupContents): string {
	return c.profiles || c.beans || c.roasters || c.shots
		? `${c.profiles} profile(s), ${c.beans} bean(s), ${c.roasters} roaster(s), ${c.shots} shot(s)`
		: 'settings & favourites';
}

/** The sentence owed after a backup that left a row out or thinned one, with a
 *  leading space, or `''` for a clean backup. */
export function describeBackupGaps(c: BackupContents): string {
	const omitted = c.omitted ?? [];
	const degraded = c.degraded?.length ?? 0;
	const kinds = new Set(omitted.map((o) => o.kind));
	const noun = kinds.size === 1 ? ([...kinds][0] === 'brewMethod' ? 'method' : [...kinds][0]) : 'item';
	const left =
		omitted.length === 0
			? ''
			: ` ${omitted.length} ${noun}${omitted.length === 1 ? '' : 's'} couldn’t be read and ${omitted.length === 1 ? 'was' : 'were'} left out.`;
	const thin =
		degraded === 0
			? ''
			: ` ${degraded} shot${degraded === 1 ? ' was' : 's were'} backed up without ${degraded === 1 ? 'its' : 'their'} profile.`;
	const noCurves = c.noCurves ?? 0;
	const flat =
		noCurves === 0
			? ''
			: ` ${noCurves} shot${noCurves === 1 ? ' was' : 's were'} backed up without ${noCurves === 1 ? 'its' : 'their'} curve.`;
	const missing = c.missing ?? [];
	const names =
		missing.length <= 1 ? (missing[0] ?? '') : `${missing.slice(0, -1).join(', ')} and ${missing.at(-1)}`;
	const gone =
		missing.length === 0
			? ''
			: ` It doesn’t include your ${names}: ${missing.length === 1 ? 'it' : 'they'} couldn’t be read.`;
	return left + thin + flat + gone;
}

/**
 * Why a built backup shouldn't go to Drive, or `null`. A local file is the
 * user's to keep or not, but a restore from Drive starts at whatever is newest,
 * so a copy that lost its curves to a failed read must not land there.
 */
export function driveBackupRefusal(c: BackupContents): string | null {
	if (!getHistoryStore().seriesFailed || !c.noCurves) return null;
	return `Shot curves couldn’t be read, so ${c.noCurves} shot${c.noCurves === 1 ? '' : 's'} would go without ${c.noCurves === 1 ? 'its curve' : 'their curves'}. Nothing was uploaded. Reload the app and try again.`;
}

/**
 * The custom profiles as stored, with their ids as written. The profile store
 * has read this key by now and dealt with what it found, so a value that can't
 * be read is only left out here.
 */
function storedCustomProfiles(): unknown[] {
	const read = readStored(CUSTOM_PROFILES_KEY);
	return read.kind === 'loaded' && Array.isArray(read.value) ? read.value : [];
}

/** Count what an export would include — lets the UI disable "Back up" / show a
 *  summary without building the bundle. */
export function backupContents(): BackupContents {
	const beanStore = getBeanStore();
	const history = getHistoryStore();
	const customProfiles = storedCustomProfiles();
	return {
		profiles: customProfiles.length,
		beans: beanStore.beans.length,
		roasters: beanStore.roasters.length,
		shots: history.all.length
	};
}

/**
 * Build the `crema-backup/v1` bundle for the whole app and trigger a download.
 * Custom profiles only — the built-ins ship with the app, so bundling them
 * would only create duplicates on restore. Returns the per-type counts (or
 * `null` when there's nothing to back up).
 */
export interface BuiltBackup {
	jsonl: string;
	contents: BackupContents;
}

/**
 * Whether settings are still as a fresh install leaves them — used by the empty
 * check so a customised theme / units / brew default counts as backup-worthy
 * even before the user has created any profiles / beans / shots. The shared
 * part goes by the core's `common_is_pristine`, as a first boot fills the
 * hot-water fields and they never equal the raw defaults; the web extras by
 * their defaults.
 */
function settingsAreDefault(s: Settings): boolean {
	if (!settingsPristine()) return false;
	const d = settingsPlatformExtras(DEFAULT_SETTINGS) as Record<string, unknown>;
	const cur = settingsPlatformExtras(s) as Record<string, unknown>;
	return Object.keys(d).every((k) => JSON.stringify(cur[k]) === JSON.stringify(d[k]));
}

/**
 * Each of `rows` as its own JSON string, for the core to read one at a time, as
 * Android hands them over, or `null` where encoding threw, which the core
 * reports as left out. Strings cost the core far less memory than a parsed
 * tree of every row at once.
 */
function rowStrings(rows: readonly unknown[]): (string | null)[] {
	return rows.map((row) => {
		try {
			return JSON.stringify(row) ?? null;
		} catch {
			return null;
		}
	});
}

/**
 * Build the `crema-backup/v1` JSONL for everything local, WITHOUT downloading —
 * custom profiles only (built-ins ship with the app). Shared by the local
 * export and the Drive upload. Returns null when there's nothing to back up.
 * Waits for the stored series, so no shot goes out without its curve, and
 * throws when they don't arrive.
 */
export async function buildBackupJsonl(cremaVersion = __APP_VERSION__): Promise<BuiltBackup | null> {
	const history = getHistoryStore();
	if (!(await history.seriesReady())) {
		throw new Error('Shot data is still loading — try again in a moment.');
	}
	const beanStore = getBeanStore();
	const settings = getSettingsStore();
	const customProfiles = storedCustomProfiles();
	const beans = beanStore.beans;
	const roasters = beanStore.roasters;
	const shots = history.all;
	const meta = getProfileStore().backupMeta();
	// User brew recipes (copies + own) — built-ins ship with the app and are
	// never written (the core drops any that slip through).
	const recipeStore = getRecipeStore();
	const recipes = recipeStore.backupRecipes();
	const recipeMeta = recipeStore.backupMeta();
	// The user's own brew methods, tombstones included (`kind:"brewMethod"`).
	const customMethods = getCustomMethodStore().backupMethods();

	// "Nothing to back up" only when EVERYTHING the bundle carries is empty/default —
	// not just the four data types. Customised settings (theme, units, …) and
	// pinned / hidden built-in profiles are backup-worthy on their own, even with no
	// custom profiles / beans / shots yet.
	const noData =
		customProfiles.length === 0 &&
		beans.length === 0 &&
		roasters.length === 0 &&
		shots.length === 0 &&
		recipes.length === 0 &&
		customMethods.length === 0;
	const noProfileOrg =
		meta.pinned.length === 0 &&
		meta.hiddenBuiltins.length === 0 &&
		Object.keys(recipeMeta.defaults).length === 0 &&
		recipeMeta.hiddenBuiltins.length === 0;
	if (noData && noProfileOrg && settingsAreDefault(settings.current)) {
		return null;
	}

	const envelope = JSON.stringify({
		profiles: rowStrings(customProfiles),
		beans: rowStrings(beans),
		roasters: rowStrings(roasters),
		shots: rowStrings(shots),
		recipes: rowStrings(recipes),
		customMethods: rowStrings(customMethods),
		// Settings line: the shared cross-shell `common` block (CommonSettings —
		// both shells emit it identically, so common prefs restore web<->Android)
		// + this shell's `_shell` tag + the web-only platform extras (webhooks,
		// density, … — re-applied only on web).
		settings: {
			common: settingsToCommon(settings.current),
			_shell: 'web',
			...settingsPlatformExtras(settings.current)
		},
		// Profile organisation — pinned + hidden built-in ids (cross-shell) plus
		// the web-native overrides superset. Custom-profile pins ride in the
		// profile JSON above.
		profileMeta: getProfileStore().backupMeta(),
		// Recipe organisation — per-method default pointers + hidden built-in
		// recipe ids (cross-shell; built-in ids are the core's).
		recipeMeta,
		// Maintenance counters — a shared core type (`MaintenanceState`), fully
		// portable between shells.
		maintenance: getMaintenanceStore().current,
		// Visualizer SYNC preferences (never the OAuth token) — the shared core
		// `VisualizerSyncPrefs` shape both shells serialise identically, so this
		// line restores on web or Android with no per-shell tag.
		visualizerPrefs: visualizerSyncPrefs()
	});

	const built = JSON.parse(
		exportBackupBundle(envelope, Date.now(), cremaVersion, WEB_DEVICE_LABEL)
	) as BackupExport;
	// What was written, which the core's header counts too.
	const written = (kind: string, rows: number) =>
		rows - built.omitted.filter((o) => o.kind === kind).length;
	const left = new Set(built.omitted.filter((o) => o.kind === 'shot').map((o) => o.id));
	// A row here can be unreadable: that's what `omitted` is for.
	const noCurves = shots.filter(
		(s) => !left.has(s.id) && refusedNoSamples(s, s.record?.samples?.length ?? 0)
	).length;
	return {
		jsonl: built.jsonl,
		contents: {
			profiles: written('profile', customProfiles.length),
			beans: written('bean', beans.length),
			roasters: written('roaster', roasters.length),
			shots: written('shot', shots.length),
			omitted: built.omitted,
			degraded: built.degraded,
			noCurves,
			missing: unreadableStores()
		}
	};
}

/** Filename for a backup (local download + Drive upload share it). A `.crema.zip`
 *  is a real zip: `backup.jsonl` (line-delimited JSON) + `images/<beanId>` photo
 *  blobs. The `crema` prefix keeps it recognisable + is what Drive lists on. */
export function backupFileName(): string {
	return `crema-backup-web-${filenameStamp()}.crema.zip`;
}

/** The JSONL member name inside a `.crema.zip` (sits next to `images/<beanId>`). */
const BACKUP_JSONL_MEMBER = 'backup.jsonl';

/**
 * Build the full `.crema.zip` bundle: the `crema-backup/v1` JSONL plus every
 * bean photo (`images/<beanId>`, pulled from IndexedDB). A backup with no photos
 * is still a single-member zip, so the restore path is uniform. Returns null when
 * there's nothing to back up.
 */
export async function buildBackupZip(
	cremaVersion = __APP_VERSION__
): Promise<{ zip: Uint8Array; contents: BackupContents } | null> {
	const built = await buildBackupJsonl(cremaVersion);
	if (!built) return null;
	const entries: Record<string, Uint8Array> = { [BACKUP_JSONL_MEMBER]: strToU8(built.jsonl) };
	const imageStore = getBeanImageStore();
	for (const bean of getBeanStore().beans) {
		if (!bean.imageRef) continue;
		try {
			const blob = await imageStore.get(bean.imageRef);
			if (blob) entries[`images/${bean.id}`] = new Uint8Array(await blob.arrayBuffer());
		} catch {
			// IDB miss / read error — skip this bean's photo, don't fail the backup.
		}
	}
	return { zip: zipSync(entries), contents: built.contents };
}

/** Build + download a `.crema.zip` bundle of everything local (incl. bean photos). */
export async function exportBackup(cremaVersion = __APP_VERSION__): Promise<BackupContents | null> {
	const built = await buildBackupZip(cremaVersion);
	if (!built) return null;
	downloadBlob(backupFileName(), new Blob([built.zip as BlobPart], { type: 'application/zip' }));
	return built.contents;
}

/**
 * Run a backup and word what happened. A build that throws ends in a message
 * like any other, so the UI can't stay on "Building backup…".
 */
export async function backupNote(run: () => Promise<BackupContents | null> = exportBackup): Promise<string> {
	try {
		const result = await run();
		return result
			? `Backed up — ${describeBackup(result)}.${describeBackupGaps(result)}`
			: 'Nothing to back up yet.';
	} catch (err) {
		console.error('Backup failed', err);
		return `Backup failed: nothing was saved.${err instanceof Error ? ` ${err.message}` : ''}`;
	}
}

export type RestoreMode = 'merge' | 'wipe';

export interface RestoreSummary {
	/** The plan that ran, or was refused: the report words it. */
	outcome: RestoreOutcome;
	/** Changes the restore did NOT apply because they need the user's OK. */
	held: HeldRestoreChanges | null;
}

/**
 * Settings a backup carries that change where this device's data goes. A
 * backup can come from someone else, so a restore never applies these on its
 * own (review 05 P3-1): the caller shows them ({@link describeHeldRestoreChanges})
 * and applies them only on an explicit OK ({@link applyHeldRestoreChanges}).
 */
export interface HeldRestoreChanges {
	/** The backup's webhook settings, when they differ from this device's. */
	webhook: Pick<Settings, 'webhookEnabled' | 'webhookUrl' | 'webhookEvents'> | null;
	/** Visualizer prefs that would share more, with the backup's values. */
	visualizer: { fields: string[]; incoming: VisualizerSyncPrefs } | null;
}

/** The webhook settings — never applied by a restore without confirmation. */
const WEBHOOK_KEYS = ['webhookEnabled', 'webhookUrl', 'webhookEvents'] as const;

/**
 * A backup's webhook URL in the form it may be offered and stored: the
 * parsed `href`, `''` for a blank URL, or `null` when it must not be offered
 * at all (final review 05 N-4b). The WHATWG parser silently drops tabs and
 * newlines, so a raw string padded with them could read as one host in the
 * dialog while the parsed host is another; any control or format character
 * therefore rejects the URL, as does anything {@link isAllowedWebhookUrl}
 * refuses. Storing the `href` means the value applied is exactly the one shown.
 */
export function restorableWebhookUrl(raw: string): string | null {
	const trimmed = raw.trim();
	if (trimmed === '') return '';
	if (/[\p{Cc}\p{Cf}]/u.test(raw)) return null;
	if (!isAllowedWebhookUrl(trimmed)) return null;
	return new URL(trimmed).href;
}

/** Pick the backup's webhook settings when they would change this device's. */
function heldWebhook(
	incoming: Record<string, unknown>,
	current: Settings
): HeldRestoreChanges['webhook'] {
	if (!WEBHOOK_KEYS.some((k) => k in incoming)) return null;
	const incomingUrl =
		typeof incoming.webhookUrl === 'string' ? restorableWebhookUrl(incoming.webhookUrl) : current.webhookUrl;
	// A URL that cannot be shown honestly is never offered, nor is the rest of
	// the backup's webhook block (switching on a webhook it could not name).
	if (incomingUrl === null) return null;
	const next = {
		webhookEnabled:
			typeof incoming.webhookEnabled === 'boolean' ? incoming.webhookEnabled : current.webhookEnabled,
		webhookUrl: incomingUrl,
		webhookEvents:
			incoming.webhookEvents && typeof incoming.webhookEvents === 'object'
				? { ...current.webhookEvents, ...(incoming.webhookEvents as Settings['webhookEvents']) }
				: current.webhookEvents
	};
	const unchanged =
		next.webhookEnabled === current.webhookEnabled &&
		next.webhookUrl === current.webhookUrl &&
		JSON.stringify(next.webhookEvents) === JSON.stringify(current.webhookEvents);
	// Nothing would be sent anywhere new: no need to ask.
	if (unchanged || (!next.webhookEnabled && !current.webhookEnabled && next.webhookUrl.trim() === '')) {
		return null;
	}
	return next;
}

const VISUALIZER_FIELD_LABELS: Record<string, (p: VisualizerSyncPrefs) => string> = {
	privacy: (p) => `make Visualizer uploads ${p.privacy}`,
	includeNotes: () => 'include your tasting notes in Visualizer uploads',
	autoUpload: () => 'upload every new shot to Visualizer automatically',
	includeProfile: () => 'include the profile in Visualizer uploads',
	autoSync: () => 'sync shots, beans and roasters to Visualizer in the background',
	shotsDirection: (p) => `upload shots to Visualizer (shot sync: ${p.shotsDirection})`,
	beansDirection: (p) => `upload your beans to Visualizer (bean sync: ${p.beansDirection})`,
	roastersDirection: (p) => `upload your roasters to Visualizer (roaster sync: ${p.roastersDirection})`
};

/** The webhook lines: the parsed host on its own line first, then the full address. */
function describeWebhook(webhook: NonNullable<HeldRestoreChanges['webhook']>): string {
	let host: string | null = null;
	let href = webhook.webhookUrl.trim();
	try {
		const url = new URL(href);
		// `host` is the ASCII (punycode) form, so a lookalike name shows as `xn--…`.
		host = url.host;
		href = url.href;
	} catch {
		// A blank URL: nothing would be sent.
	}
	if (host === null) return '• Clear the webhook URL';
	return webhook.webhookEnabled
		? `• Send shot and machine events to a webhook at:\n    ${host}\n    Full address: ${href}`
		: `• Set the webhook address to ${host} (left switched off)\n    Full address: ${href}`;
}

/** Plain-language list of what applying `held` would change, for a confirm dialog. */
export function describeHeldRestoreChanges(held: HeldRestoreChanges): string {
	const lines: string[] = [];
	if (held.webhook) lines.push(describeWebhook(held.webhook));
	if (held.visualizer) {
		for (const f of held.visualizer.fields) {
			const label = VISUALIZER_FIELD_LABELS[f];
			if (label) lines.push(`• ${label(held.visualizer.incoming)}`);
		}
	}
	return lines.join('\n');
}

/** Apply the changes a restore held back — only after the user confirmed them. */
export function applyHeldRestoreChanges(held: HeldRestoreChanges): void {
	if (held.webhook) {
		const settingsStore = getSettingsStore();
		settingsStore.replaceAll({ ...settingsStore.current, ...held.webhook });
	}
	if (held.visualizer) {
		const current = visualizerSyncPrefs() as unknown as Record<string, unknown>;
		const incoming = held.visualizer.incoming as unknown as Record<string, unknown>;
		const next = { ...current };
		for (const f of held.visualizer.fields) if (f in incoming) next[f] = incoming[f];
		applyVisualizerSyncPrefs(next as unknown as VisualizerSyncPrefs);
	}
}

/**
 * The settings-like lines a restore applies at step 8, by the plan. A backup's
 * webhook and wider Visualizer sharing are never applied here: they come back
 * as {@link HeldRestoreChanges} for the user's OK, as a backup can come from
 * someone else. `common` is cross-shell; the web extras apply only when the
 * plan says the backup is the web's.
 */
export function webSingletons(): { sink: SingletonSink; held: () => HeldRestoreChanges | null } {
	let webhook: HeldRestoreChanges['webhook'] = null;
	let visualizer: HeldRestoreChanges['visualizer'] = null;
	const sink: SingletonSink = {
		settings(settings, plan, replace) {
			const store = getSettingsStore();
			// Replace starts from a reset; `LOC` entries always stay (the core's reset rule).
			if (replace) store.reset();
			const extras = plan.extras === PlanAction.Apply;
			if (settings.common) {
				const next = applyCommonToSettings(
					settings.common as unknown as CommonSettings,
					store.current
				) as unknown as Record<string, unknown>;
				if (extras) {
					for (const key of Object.keys(settingsPlatformExtras(store.current))) {
						if (key in settings && !isWebhookKey(key)) next[key] = settings[key];
					}
					webhook = heldWebhook(settings, store.current);
				}
				return store.replaceAll(next as unknown as typeof store.current);
			}
			if (!extras) return true;
			// A line from before `common`: the recognised keys, over this device's.
			const next = { ...store.current } as Record<string, unknown>;
			for (const key of Object.keys(store.current)) {
				if (key in settings && !isWebhookKey(key)) next[key] = settings[key];
			}
			webhook = heldWebhook(settings, store.current);
			return store.replaceAll(sanitizeFlat(next as unknown as typeof store.current));
		},
		maintenance(state) {
			return getMaintenanceStore().replaceAll(state as unknown as MaintenanceState);
		},
		visualizerPrefs(prefs) {
			const guarded = JSON.parse(
				guardRestoredVisualizerPrefs(JSON.stringify(visualizerSyncPrefs()), JSON.stringify(prefs))
			) as GuardedVisualizerPrefs;
			applyVisualizerSyncPrefs(guarded.apply);
			if (guarded.held.length > 0) {
				// The backup's values with any missing field filled from the core
				// defaults (guarding a blob against itself holds nothing).
				const incoming = JSON.parse(
					guardRestoredVisualizerPrefs(JSON.stringify(prefs), JSON.stringify(prefs))
				) as GuardedVisualizerPrefs;
				visualizer = { fields: guarded.held, incoming: incoming.apply };
			}
			return true;
		}
	};
	return {
		sink,
		held: () => (webhook || visualizer ? { webhook, visualizer } : null)
	};
}

function isWebhookKey(key: string): boolean {
	return (WEBHOOK_KEYS as readonly string[]).includes(key);
}

/**
 * Restore a backup from raw bytes or text, with no report first: the plan
 * runs if the core says it may (`ok`), or `confirm` is passed for a plan the
 * user has seen. The file picker and Drive go through the report in the
 * Settings section; this is the path for both once the user has said yes.
 */
export async function restoreBackupData(
	data: ArrayBuffer | Uint8Array | string,
	mode: RestoreMode,
	opts: { name?: string; confirmed?: RestorePlan | null; reviveDeleted?: boolean; deps?: Partial<RestoreDeps> } = {}
): Promise<RestoreSummary> {
	const prepared = prepareBackup(data, opts.name ?? 'a backup');
	const singletons = webSingletons();
	const outcome = await runRestore(
		prepared,
		mode,
		{ reviveDeleted: opts.reviveDeleted, expected: opts.confirmed ?? null },
		{ singletons: singletons.sink, ...opts.deps }
	);
	return { outcome, held: singletons.held() };
}

/** {@link restoreBackupData} for JSONL text, applying whatever the plan allows: for tests and imports. */
export async function restoreBackup(text: string, mode: RestoreMode): Promise<RestoreSummary> {
	const prepared = prepareBackup(text, 'a backup');
	const plan = await planNow(prepared, mode);
	if (plan.verdict === Verdict.Refused) {
		return { outcome: { kind: 'refused', plan }, held: null };
	}
	return restoreBackupData(text, mode, { confirmed: plan });
}
