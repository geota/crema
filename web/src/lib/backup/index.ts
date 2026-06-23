/**
 * `$lib/backup` — whole-app local backup & restore, the web side of the
 * cross-shell `crema-backup/v1` bundle (core `export_backup_jsonl_from_json` /
 * the line-tagged JSONL; mirrors Android's `MainViewModel.backupBundleJson`).
 *
 * One `.crema` file = custom profiles + beans/roasters + shot history + the
 * portable settings subset, serialised by the Rust core so web and Android
 * emit **identical bytes** — a backup moves between devices. Photos (IndexedDB
 * blobs) are NOT bundled here; they stay device-local (a future `.crema.zip`
 * wrapper can carry them, as `$lib/bean/export` already does for the library).
 *
 * Excluded from the bundle: Visualizer OAuth tokens (separate storage) and any
 * per-device state. The web has no persisted BLE address (Web Bluetooth
 * re-pairs each session), so the whole settings bundle is portable; it's tagged
 * `_shell: 'web'` so a restore only re-applies settings on a matching shell
 * (the cross-shell DATA still restores either way).
 */

import { exportBackupJsonl } from '$lib/wasm/de1_wasm';
import { getBeanStore } from '$lib/bean';
import { getHistoryStore } from '$lib/history';
import {
	getSettingsStore,
	settingsToCommon,
	applyCommonToSettings,
	settingsPlatformExtras
} from '$lib/settings';
import { getProfileStore } from '$lib/profiles';
import { getMaintenanceStore } from '$lib/maintenance';
import { visualizerSyncPrefs, applyVisualizerSyncPrefs } from '$lib/visualizer/sync-config';
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

/**
 * Build the `crema-backup/v1` JSONL for everything local, WITHOUT downloading —
 * custom profiles only (built-ins ship with the app). Shared by the local
 * export and the Drive upload. Returns null when there's nothing to back up.
 */
export function buildBackupJsonl(cremaVersion = '0.0.1'): BuiltBackup | null {
	const beanStore = getBeanStore();
	const history = getHistoryStore();
	const settings = getSettingsStore();
	const customProfiles = readJson<unknown[]>(CUSTOM_PROFILES_KEY, []);
	const beans = beanStore.beans;
	const roasters = beanStore.roasters;
	const shots = history.all;

	if (
		customProfiles.length === 0 &&
		beans.length === 0 &&
		roasters.length === 0 &&
		shots.length === 0
	) {
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

/** Filename for a `.crema` backup (local download + Drive upload share it). */
export function backupFileName(): string {
	return `crema-backup-web-${filenameStamp()}.crema`;
}

/** Build + download a `.crema` bundle of everything local. */
export function exportBackup(cremaVersion = '0.0.1'): BackupContents | null {
	const built = buildBackupJsonl(cremaVersion);
	if (!built) return null;
	downloadBlob(backupFileName(), new Blob([built.jsonl], { type: 'application/x-ndjson' }));
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
	const profiles: CremaProfile[] = [];
	const beans: Bean[] = [];
	const roasters: Roaster[] = [];
	const shots: StoredShot[] = [];
	let settings: Record<string, unknown> | null = null;
	let profileMeta: Record<string, unknown> | null = null;
	let maintenance: Record<string, unknown> | null = null;
	let visualizerPrefs: Record<string, unknown> | null = null;
	let sawHeader = false;

	for (const rawLine of text.split('\n')) {
		const line = rawLine.trim();
		if (line.length < 2 || line[0] !== '{') continue;
		let obj: Record<string, unknown>;
		try {
			obj = JSON.parse(line) as Record<string, unknown>;
		} catch {
			continue;
		}
		const kind = obj.kind;
		const rest: Record<string, unknown> = { ...obj };
		delete rest.kind;
		switch (kind) {
			case 'crema-backup/v1':
				sawHeader = true;
				break;
			case 'settings':
				settings = rest;
				break;
			case 'profileMeta':
				profileMeta = rest;
				break;
			case 'maintenance':
				maintenance = rest;
				break;
			case 'visualizerPrefs':
				visualizerPrefs = rest;
				break;
			case 'roaster':
				roasters.push(rest as unknown as Roaster);
				break;
			case 'bean':
				beans.push(rest as unknown as Bean);
				break;
			case 'shot':
				shots.push(rest as unknown as StoredShot);
				break;
			case 'profile':
				profiles.push(rest as unknown as CremaProfile);
				break;
		}
	}
	if (!sawHeader) throw new Error('Not a Crema backup file.');

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
		const setAny = settingsStore.set.bind(settingsStore) as (k: string, v: unknown) => void;
		if (settings.common) {
			const next = applyCommonToSettings(
				settings.common as unknown as CommonSettings,
				settingsStore.current
			);
			for (const [key, value] of Object.entries(next)) setAny(key, value);
			if (settings._shell === 'web') {
				for (const key of Object.keys(settingsPlatformExtras(settingsStore.current))) {
					if (key in settings) setAny(key, settings[key]);
				}
			}
			settingsApplied = true;
		} else if (settings._shell === 'web') {
			const current = settingsStore.current as unknown as Record<string, unknown>;
			for (const key of Object.keys(current)) {
				if (key in settings) setAny(key, settings[key]);
			}
			settingsApplied = true;
		}
	}

	// Profile organisation — pins + hidden built-ins (cross-shell). A wipe
	// restore replaces the sets; a merge unions them.
	if (profileMeta) {
		profileStore.applyBackupMeta(profileMeta, mode === 'wipe');
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
