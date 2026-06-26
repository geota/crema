import { describe, it, expect, beforeEach } from 'vitest';
import { getBeanStore, blankBean, blankRoaster } from '$lib/bean';
import { getHistoryStore, type StoredShot } from '$lib/history';
import { getProfileStore } from '$lib/profiles';
import { blankProfile } from '$lib/profiles/model';
import { getSettingsStore } from '$lib/settings';
import { buildBackupJsonl, restoreBackup } from './index';

/**
 * Round-trip tests for the whole-app backup/restore (review #07). The runes
 * store singletons + the wasm `exportBackupJsonl` both work under jsdom, so this
 * drives the real `buildBackupJsonl` → `restoreBackup` path — no mocks. Backup is
 * data-loss-adjacent; a line-parser or merge/wipe dedup regression would silently
 * drop or duplicate records, which these pin.
 */

function makeShot(id: string): StoredShot {
	return {
		formatVersion: 3,
		id,
		completedAt: 1_700_000_000_000,
		profileName: 'Test Profile',
		profile: null,
		stopReason: null,
		metadata: {
			dose: 18,
			yieldOut: 36,
			beans: null,
			grinderSetting: null,
			notes: 'tasty',
			rating: 4,
			tds: null,
			extractionYield: null
		},
		record: { duration: 30_000, samples: [] },
		bean: null
	};
}

function resetAll(): void {
	getBeanStore().clearAll();
	getHistoryStore().clearAllShots();
	getProfileStore().clearAllCustom();
	getSettingsStore().reset();
	localStorage.clear();
}

/** Seed one of each data type; returns the ids so a restore can be asserted. */
function seed(): { beanId: string; roasterId: string; shotId: string; profileId: string } {
	const bean = { ...blankBean('bean-1'), name: 'Ethiopia', roasterId: 'roaster-1' };
	const roaster = blankRoaster('Test Roaster', 'roaster-1');
	getBeanStore().bulkAdd([bean], [roaster]);
	const shot = makeShot('shot:round-trip-1');
	getHistoryStore().insertPulled(shot);
	const profile = { ...blankProfile(), name: 'My Custom' };
	getProfileStore().save(profile);
	return { beanId: bean.id, roasterId: roaster.id, shotId: shot.id, profileId: profile.id };
}

describe('backup round-trip (review #07)', () => {
	beforeEach(resetAll);

	it('round-trips beans, roasters, shots + profiles through a wipe restore', () => {
		const ids = seed();
		const built = buildBackupJsonl();
		expect(built).not.toBeNull();

		resetAll(); // simulate restoring onto a fresh device
		const summary = restoreBackup(built!.jsonl, 'wipe');

		expect(summary).toMatchObject({ beans: 1, roasters: 1, shots: 1, profiles: 1 });
		expect(getBeanStore().beans.map((b) => b.id)).toContain(ids.beanId);
		expect(getBeanStore().roasters.map((r) => r.id)).toContain(ids.roasterId);
		expect(getHistoryStore().get(ids.shotId)?.id).toBe(ids.shotId);
		expect(getProfileStore().get(ids.profileId)?.id).toBe(ids.profileId);
	});

	it('merge adds new records but preserves existing ones', () => {
		seed();
		const built = buildBackupJsonl()!;

		resetAll();
		getBeanStore().bulkAdd([{ ...blankBean('bean-2'), name: 'Kenya' }], []); // a different bag
		const summary = restoreBackup(built.jsonl, 'merge');

		expect(summary.beans).toBe(1); // bean-1 added; bean-2 untouched
		expect(getBeanStore().beans.map((b) => b.id).sort()).toEqual(['bean-1', 'bean-2']);
	});

	it('merge skips records whose ids are already present (idempotent re-restore)', () => {
		seed();
		const built = buildBackupJsonl()!;

		// Restore the same bundle without clearing — every id already exists.
		const summary = restoreBackup(built.jsonl, 'merge');

		expect(summary).toMatchObject({ beans: 0, roasters: 0, shots: 0, profiles: 0 });
		expect(getBeanStore().beans.length).toBe(1);
		expect(getHistoryStore().all.length).toBe(1);
	});

	it('skips a malformed JSONL line without dropping the rest', () => {
		seed();
		const built = buildBackupJsonl()!;

		resetAll();
		const corrupted = built.jsonl.replace('\n', '\n{ this is not valid json \n');
		expect(() => restoreBackup(corrupted, 'wipe')).not.toThrow();
		expect(getBeanStore().beans.length).toBe(1);
		expect(getHistoryStore().all.length).toBe(1);
	});

	it('throws on a file without the crema-backup header', () => {
		expect(() => restoreBackup('{"kind":"something-else"}\n', 'merge')).toThrow(
			/not a crema backup/i
		);
	});
});
