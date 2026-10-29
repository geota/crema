import { beforeEach, describe, expect, it } from 'vitest';
import { getSettingsStore } from '#lib/settings/index.js';
import { readSyncConfig, updateSyncConfig } from '#lib/visualizer/sync-config.js';
import {
	applyHeldRestoreChanges,
	buildBackupJsonl,
	describeHeldRestoreChanges,
	restoreBackup,
	restorableWebhookUrl
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

	// Final review 05 N-4a: a direction that starts pushing, background sync and
	// the profile flag turning on all start sending data, so they are held too.
	it('holds a shots direction that starts pushing, autoSync and includeProfile', () => {
		updateSyncConfig({
			autoUpload: true,
			autoSync: true,
			includeProfile: true,
			direction: { shots: 'two-way', beans: 'backup', roasters: 'two-way' }
		});
		getSettingsStore().set('density', 'compact' as never); // something to back up
		const jsonl = buildBackupJsonl()!.jsonl;
		localStorage.clear();
		getSettingsStore().reset();
		// The device left auto-upload ON but switched shot sync off.
		updateSyncConfig({
			autoUpload: true,
			autoSync: false,
			includeProfile: false,
			direction: { shots: 'off', beans: 'pull', roasters: 'two-way' }
		});

		const { held } = restoreBackup(jsonl, 'merge');

		const viz = readSyncConfig();
		expect(viz.direction.shots).toBe('off');
		expect(viz.direction.beans).toBe('pull');
		expect(viz.autoSync).toBe(false);
		expect(viz.includeProfile).toBe(false);
		expect(held?.visualizer?.fields.sort()).toEqual(
			['autoSync', 'beansDirection', 'includeProfile', 'shotsDirection'].sort()
		);
		const text = describeHeldRestoreChanges(held!);
		expect(text).toContain('upload shots to Visualizer (shot sync: two-way)');
		expect(text).toContain('include the profile');
		applyHeldRestoreChanges(held!);
		expect(readSyncConfig().direction.shots).toBe('two-way');
		expect(readSyncConfig().autoSync).toBe(true);
	});
});

// Final review 05 N-4b: the dialog must name the host the URL actually reaches.
describe('held webhook URL display', () => {
	beforeEach(deviceDefaults);

	function backupWithWebhook(url: string): string {
		const settings = getSettingsStore();
		settings.set('webhookEnabled', true);
		settings.set('webhookUrl', url);
		const jsonl = buildBackupJsonl()!.jsonl;
		deviceDefaults();
		return jsonl;
	}

	it('never offers a URL padded with newlines or tabs', () => {
		const disguised = 'https://hooks.example.com\n\n\n\n.attacker.example/collect';
		expect(restorableWebhookUrl(disguised)).toBeNull();
		expect(restorableWebhookUrl('https://ok.example/\tx')).toBeNull();
		expect(restorableWebhookUrl('https://ok.example/\u202Ex')).toBeNull();
		const { held } = restoreBackup(backupWithWebhook(disguised), 'merge');
		expect(held?.webhook ?? null).toBeNull();
		expect(getSettingsStore().current.webhookUrl).toBe('');
	});

	it('never offers a URL the send path would refuse', () => {
		expect(restorableWebhookUrl('http://evil.example/x')).toBeNull();
		expect(restorableWebhookUrl('https://user@evil.example/x')).toBeNull();
		expect(restorableWebhookUrl('javascript:alert(1)')).toBeNull();
		expect(restorableWebhookUrl('  ')).toBe('');
	});

	it('shows the parsed host first, on its own line, and stores the normalised URL', () => {
		const { held } = restoreBackup(backupWithWebhook('HTTPS://Hooks.Example.COM:8443/a b'), 'merge');
		expect(held?.webhook?.webhookUrl).toBe('https://hooks.example.com:8443/a%20b');
		const lines = describeHeldRestoreChanges(held!).split('\n');
		expect(lines[1].trim()).toBe('hooks.example.com:8443');
		expect(lines[2]).toContain('Full address: https://hooks.example.com:8443/a%20b');
	});

	it('shows a lookalike (IDN) host in its punycode form', () => {
		const { held } = restoreBackup(backupWithWebhook('https://ex\u0430mple.com/hook'), 'merge');
		const text = describeHeldRestoreChanges(held!);
		expect(text).toContain('xn--');
		expect(text).not.toContain('ex\u0430mple');
	});
});
