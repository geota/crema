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
import { getSettingsStore } from '$lib/settings';
import { readJson } from '$lib/utils/storage';
import { downloadBlob, filenameStamp } from '$lib/utils/download';

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
export function exportBackup(cremaVersion = '0.0.1'): BackupContents | null {
	const beanStore = getBeanStore();
	const history = getHistoryStore();
	const settings = getSettingsStore();
	const customProfiles = readJson<unknown[]>(CUSTOM_PROFILES_KEY, []);
	const beans = beanStore.beans;
	const roasters = beanStore.roasters;
	const shots = history.all;

	if (customProfiles.length === 0 && beans.length === 0 && roasters.length === 0 && shots.length === 0) {
		return null;
	}

	const envelope = JSON.stringify({
		profiles: customProfiles,
		beans,
		roasters,
		shots,
		// Whole settings bundle is portable on web; tag the source shell so a
		// restore only re-applies it on web (Android's settings are a different
		// shape). The DATA above is shared core types — fully cross-shell.
		settings: { ...settings.current, _shell: 'web' }
	});

	const jsonl = exportBackupJsonl(envelope, Date.now(), cremaVersion, WEB_DEVICE_LABEL);
	downloadBlob(
		`crema-backup-web-${filenameStamp()}.crema`,
		new Blob([jsonl], { type: 'application/x-ndjson' })
	);
	return {
		profiles: customProfiles.length,
		beans: beans.length,
		roasters: roasters.length,
		shots: shots.length
	};
}
