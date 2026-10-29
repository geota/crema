import { beforeEach, describe, expect, it } from 'vitest';
import { getSettingsStore } from '#lib/settings/index.js';
import { readSyncConfig, updateSyncConfig } from '#lib/visualizer/sync-config.js';
import {
	applyHeldRestoreChanges,
	buildBackupJsonl,
	describeHeldRestoreChanges,
	restoreBackup
} from './index';

/**
 * Review 05 P3-1: restoring someone else's backup must not silently re-point
 * the webhook or widen Visualizer sharing. Those changes come back as `held`
 * and apply only through `applyHeldRestoreChanges` (after the user's OK).
 */

const ATTACKER = 'https://attacker.example/collect';

function deviceDefaults(): void {
	localStorage.clear();
	getSettingsStore().reset();
	updateSyncConfig({ privacy: 'private', includeNotes: false, autoUpload: false });
}

/** A backup built on a device whose webhook + Visualizer sharing are wide open. */
function senderBackup(): string {
	const settings = getSettingsStore();
	settings.set('webhookEnabled', true);
	settings.set('webhookUrl', ATTACKER);
	updateSyncConfig({ privacy: 'public', includeNotes: true, autoUpload: true });
	settings.set('density', 'compact' as never);
	const built = buildBackupJsonl();
	expect(built).not.toBeNull();
	return built!.jsonl;
}

describe('restore holds sharing changes for confirmation', () => {
	beforeEach(deviceDefaults);

	it.each(['merge', 'wipe'] as const)('%s restore does not change the webhook or widen Visualizer prefs', (mode) => {
		const jsonl = senderBackup();
		deviceDefaults();

		const summary = restoreBackup(jsonl, mode);

		const s = getSettingsStore().current;
		expect(s.webhookUrl).toBe('');
		expect(s.webhookEnabled).toBe(false);
		const viz = readSyncConfig();
		expect(viz.privacy).toBe('private');
		expect(viz.includeNotes).toBe(false);
		expect(viz.autoUpload).toBe(false);
		// Other settings still restore.
		expect(summary.settingsApplied).toBe(true);
		expect(s.density).toBe('compact');

		expect(summary.held?.webhook?.webhookUrl).toBe(ATTACKER);
		expect(summary.held?.visualizer?.fields.sort()).toEqual(['autoUpload', 'includeNotes', 'privacy']);
		const text = describeHeldRestoreChanges(summary.held!);
		expect(text).toContain(ATTACKER);
		expect(text).toContain('public');
	});

	it('applies the held changes only when asked to', () => {
		const jsonl = senderBackup();
		deviceDefaults();
		const { held } = restoreBackup(jsonl, 'merge');
		applyHeldRestoreChanges(held!);
		expect(getSettingsStore().current.webhookUrl).toBe(ATTACKER);
		expect(getSettingsStore().current.webhookEnabled).toBe(true);
		expect(readSyncConfig().privacy).toBe('public');
		expect(readSyncConfig().includeNotes).toBe(true);
	});

	it('holds nothing when the backup would not widen anything', () => {
		getSettingsStore().set('density', 'compact' as never);
		const built = buildBackupJsonl(); // same device, same sharing settings
		expect(built).not.toBeNull();
		expect(restoreBackup(built!.jsonl, 'merge').held).toBeNull();
	});
});
