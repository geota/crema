/**
 * `$lib/backup` — whole-app local backup & restore, the web side of the
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

import { exportBackupJsonl, importBackupJsonl } from '$lib/wasm/de1_wasm';
import { getBeanStore } from '$lib/bean';
import { getHistoryStore } from '$lib/history';
import {
	getSettingsStore,
	settingsToCommon,
	applyCommonToSettings,
	settingsPlatformExtras,
	DEFAULT_SETTINGS,
	type Settings
} from '$lib/settings';
import { getProfileStore } from '$lib/profiles';
import { getMaintenanceStore } from '$lib/maintenance';
import { visualizerSyncPrefs, applyVisualizerSyncPrefs } from '$lib/visualizer/sync-config';
import { getBeanImageStore, refForBean } from '$lib/bean/image-storage';
import { strToU8, strFromU8, zipSync, unzipSync } from 'fflate';
import { readJson } from '$lib/utils/storage';
import { downloadBlob, filenameStamp } from '$lib/utils/download';
import type { Bean, Roaster } from '$lib/bean/model';
import type { StoredShot } from '$lib/history';
import type { CremaProfile } from '$lib/profiles/model';
import type { CommonSettings, MaintenanceState, VisualizerSyncPrefs } from '$lib/core/crema-core';

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
}

/** A human one-liner for a completed backup. Falls back to "settings &
 *  favourites" when there's no library data (settings / pins always ride along). */
export function describeBackup(c: BackupContents): string {
	return c.profiles || c.beans || c.roasters || c.shots
		? `${c.profiles} profile(s), ${c.beans} bean(s), ${c.roasters} roaster(s), ${c.shots} shot(s)`
		: 'settings & favourites';
}

/** Count what an export would include — lets the UI disable "Back up" / show a
 *  summary without building the bundle. */
export function backupContents(): BackupContents {
	const beanStore = getBeanStore();
	const history = getHistoryStore();
	const customProfiles = readJson<unknown[]>(CUSTOM_PROFILES_KEY, []);
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

/** Whether settings are still at their canonical defaults — used by the empty
 *  check so a customised theme / units / brew default counts as backup-worthy
 *  even before the user has created any profiles / beans / shots. */
function settingsAreDefault(s: Settings): boolean {
	const d = DEFAULT_SETTINGS as unknown as Record<string, unknown>;
	const cur = s as unknown as Record<string, unknown>;
	return Object.keys(d).every((k) => JSON.stringify(cur[k]) === JSON.stringify(d[k]));
}

/**
 * Build the `crema-backup/v1` JSONL for everything local, WITHOUT downloading —
 * custom profiles only (built-ins ship with the app). Shared by the local
 * export and the Drive upload. Returns null when there's nothing to back up.
 */
export function buildBackupJsonl(cremaVersion = __APP_VERSION__): BuiltBackup | null {
	const beanStore = getBeanStore();
	const history = getHistoryStore();
	const settings = getSettingsStore();
	const customProfiles = readJson<unknown[]>(CUSTOM_PROFILES_KEY, []);
	const beans = beanStore.beans;
	const roasters = beanStore.roasters;
	const shots = history.all;
	const meta = getProfileStore().backupMeta();

	// "Nothing to back up" only when EVERYTHING the bundle carries is empty/default —
	// not just the four data types. Customised settings (theme, units, …) and
	// pinned / hidden built-in profiles are backup-worthy on their own, even with no
	// custom profiles / beans / shots yet.
	const noData =
		customProfiles.length === 0 && beans.length === 0 && roasters.length === 0 && shots.length === 0;
	const noProfileOrg = meta.pinned.length === 0 && meta.hiddenBuiltins.length === 0;
	if (noData && noProfileOrg && settingsAreDefault(settings.current)) {
		return null;
	}

	const envelope = JSON.stringify({
		profiles: customProfiles,
		beans,
		roasters,
		shots,
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
		// Maintenance counters — a shared core type (`MaintenanceState`), fully
		// portable between shells.
		maintenance: getMaintenanceStore().current,
		// Visualizer SYNC preferences (never the OAuth token) — the shared core
		// `VisualizerSyncPrefs` shape both shells serialise identically, so this
		// line restores on web or Android with no per-shell tag.
		visualizerPrefs: visualizerSyncPrefs()
	});

	return {
		jsonl: exportBackupJsonl(envelope, Date.now(), cremaVersion, WEB_DEVICE_LABEL),
		contents: {
			profiles: customProfiles.length,
			beans: beans.length,
			roasters: roasters.length,
			shots: shots.length
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
	const built = buildBackupJsonl(cremaVersion);
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

export type RestoreMode = 'merge' | 'wipe';

export interface RestoreSummary {
	profiles: number;
	beans: number;
	roasters: number;
	shots: number;
	settingsApplied: boolean;
}

/**
 * Restore a `crema-backup` bundle. Parses the line-tagged JSONL directly into
 * typed records (lossless — matching Android's restore), then applies it:
 *
 *  - **merge** — adds only records whose id isn't already present (a lossless
 *    re-restore): `bulkAdd` dedups beans/roasters; profiles + shots key on id.
 *  - **wipe** — clears the local library / history / custom profiles + resets
 *    settings first (destructive — the caller gates it), then loads the bundle.
 *
 * Settings apply only from a `_shell: 'web'` bundle (Android's `AppPrefs` shape
 * differs); the DATA restores cross-shell either way.
 *
 * @throws if the text has no `crema-backup/v1` header (not a Crema backup).
 */
export function restoreBackup(text: string, mode: RestoreMode): RestoreSummary {
	// Guard the header ourselves: the core parser is tolerant (a foreign or empty
	// file parses to an empty plan), so it can't distinguish "not a Crema backup"
	// from "an empty one". The header line is the sole carrier of `crema-backup/v1`.
	if (!/"kind"\s*:\s*"crema-backup\/v1"/.test(text)) {
		throw new Error('Not a Crema backup file.');
	}

	// F1 (review #06): parse through the shared core (`parse_backup_jsonl`) instead
	// of a hand-rolled line loop. Beans/roasters/shots come back typed + lossless —
	// each shot keeps its full `bean` snapshot (the library importer's ImportedShot
	// wrapper would bury it under `storedShot` and reflatten it to loose strings) —
	// while profiles + the config blobs ride as the verbatim JSON the shell owns.
	// Android's restore parses the IDENTICAL bytes off this same core function.
	const plan = JSON.parse(importBackupJsonl(text)) as {
		profiles?: CremaProfile[];
		beans?: Bean[];
		roasters?: Roaster[];
		shots?: StoredShot[];
		settings?: Record<string, unknown> | null;
		profileMeta?: Record<string, unknown> | null;
		maintenance?: Record<string, unknown> | null;
		visualizerPrefs?: Record<string, unknown> | null;
	};
	const profiles = plan.profiles ?? [];
	const beans = plan.beans ?? [];
	const roasters = plan.roasters ?? [];
	const shots = plan.shots ?? [];
	const settings = plan.settings ?? null;
	const profileMeta = plan.profileMeta ?? null;
	const maintenance = plan.maintenance ?? null;
	const visualizerPrefs = plan.visualizerPrefs ?? null;

	const beanStore = getBeanStore();
	const history = getHistoryStore();
	const profileStore = getProfileStore();
	const settingsStore = getSettingsStore();

	if (mode === 'wipe') {
		beanStore.clearAll();
		history.clearAllShots();
		profileStore.clearAllCustom();
		settingsStore.reset();
	}

	// Profiles — add customs whose id isn't already known.
	let addedProfiles = 0;
	for (const p of profiles) {
		if (p.id && !profileStore.get(p.id)) {
			profileStore.save(p);
			addedProfiles++;
		}
	}
	// Beans + roasters — bulkAdd dedups by id (idempotent merge).
	const beforeBeans = beanStore.beans.length;
	const beforeRoasters = beanStore.roasters.length;
	beanStore.bulkAdd(beans, roasters);
	const addedBeans = beanStore.beans.length - beforeBeans;
	const addedRoasters = beanStore.roasters.length - beforeRoasters;
	// Shots — insert those not already present.
	let addedShots = 0;
	for (const s of shots) {
		if (!history.get(s.id)) {
			history.insertPulled(s);
			addedShots++;
		}
	}
	// Settings. The unified line carries `common` (shared CommonSettings — applied
	// cross-shell, no tag) plus the web-only platform extras (applied only from a
	// `_shell:'web'` bundle). A pre-unification line had no `common` key — fall back
	// to the old flat-apply, still web-tagged.
	let settingsApplied = false;
	if (settings) {
		if (settings.common) {
			// Assemble the full target Settings, then write once (replaceAll) rather
			// than a persist() per key. `common` is cross-shell; the web-only platform
			// extras apply only from a web bundle.
			const next = applyCommonToSettings(
				settings.common as unknown as CommonSettings,
				settingsStore.current
			) as unknown as Record<string, unknown>;
			if (settings._shell === 'web') {
				for (const key of Object.keys(settingsPlatformExtras(settingsStore.current))) {
					if (key in settings) next[key] = settings[key];
				}
			}
			settingsStore.replaceAll(next as unknown as typeof settingsStore.current);
			settingsApplied = true;
		} else if (settings._shell === 'web') {
			// Pre-unification flat line — overlay the recognised keys, write once.
			const next = { ...settingsStore.current } as Record<string, unknown>;
			for (const key of Object.keys(settingsStore.current)) {
				if (key in settings) next[key] = settings[key];
			}
			settingsStore.replaceAll(next as unknown as typeof settingsStore.current);
			settingsApplied = true;
		}
	}

	// Profile organisation — pins + hidden built-ins (cross-shell). A wipe restore
	// replaces the sets; a merge unions them. On wipe with no profileMeta line (an
	// older bundle), still clear the existing pins/hidden via an empty replace.
	if (profileMeta || mode === 'wipe') {
		profileStore.applyBackupMeta(profileMeta ?? {}, mode === 'wipe');
	}
	// Maintenance counters — a shared core type, applied verbatim either shell.
	if (maintenance) {
		getMaintenanceStore().replaceAll(maintenance as unknown as MaintenanceState);
	}
	// Visualizer sync prefs — a shared core type, applied verbatim either shell
	// (no per-shell tag); never the OAuth token (re-authorise after a restore).
	if (visualizerPrefs) {
		applyVisualizerSyncPrefs(visualizerPrefs as unknown as VisualizerSyncPrefs);
	}

	return {
		profiles: addedProfiles,
		beans: addedBeans,
		roasters: addedRoasters,
		shots: addedShots,
		settingsApplied
	};
}

/** Local-file `.crema.zip` magic: `PK\x03\x04`. */
function isZip(bytes: Uint8Array): boolean {
	return (
		bytes.length >= 4 &&
		bytes[0] === 0x50 &&
		bytes[1] === 0x4b &&
		bytes[2] === 0x03 &&
		bytes[3] === 0x04
	);
}

/**
 * Restore a backup from raw bytes (or text) — the entry point the file picker +
 * Drive download use. Detects a `.crema.zip` (unzips → restores `backup.jsonl` +
 * replays bean photos into IndexedDB) vs a legacy `.crema.jsonl` / `.crema` text
 * bundle. Photos key on bean id, so they survive a cross-device restore.
 */
export async function restoreBackupData(
	data: ArrayBuffer | Uint8Array | string,
	mode: RestoreMode
): Promise<RestoreSummary> {
	if (typeof data === 'string') return restoreBackup(data, mode);
	const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
	if (!isZip(bytes)) return restoreBackup(strFromU8(bytes), mode);

	const files = unzipSync(bytes);
	const jsonl = files[BACKUP_JSONL_MEMBER] ?? files['crema.jsonl'];
	if (!jsonl) throw new Error('Not a Crema backup zip (no backup.jsonl inside).');
	const summary = restoreBackup(strFromU8(jsonl), mode);

	// Replay bean photos into IndexedDB, keyed by bean id (the restored bean's
	// imageRef already points at this canonical web key for a web-origin backup).
	const imageStore = getBeanImageStore();
	for (const [path, fileBytes] of Object.entries(files)) {
		if (!path.startsWith('images/') || fileBytes.length === 0) continue;
		const beanId = path.slice('images/'.length);
		try {
			await imageStore.put(refForBean(beanId), new Blob([fileBytes as BlobPart]));
		} catch {
			// IDB write error — skip this photo, the rest of the restore stands.
		}
	}
	return summary;
}
