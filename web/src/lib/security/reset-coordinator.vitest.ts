import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { IDBFactory } from 'fake-indexeddb';
import {
	RESET_CHANNEL,
	RESET_REPORT_KEY,
	announceReset,
	cremaWritesFrozen,
	freezeCremaWrites,
	installResetListener,
	resetCoordinatorForTest,
	resetReportMessage,
	stashResetReport,
	takeResetReport
} from './reset-coordinator';

/**
 * Final review 05 N-3 + 06 #12: while a factory reset runs, neither this tab
 * nor another open Crema tab may write Crema data back; other tabs reload
 * once the reset is done.
 */

class FakeStorageProto {
	data = new Map<string, string>();
	setItem(key: string, value: string): void {
		this.data.set(key, value);
	}
}

const tick = (ms = 20) => new Promise((r) => setTimeout(r, ms));

beforeEach(() => {
	resetCoordinatorForTest();
	sessionStorage.clear();
});

describe('freezeCremaWrites', () => {
	it('drops crema.* storage writes and refuses crema-* databases, nothing else', async () => {
		const proto = new FakeStorageProto();
		const idb = new IDBFactory();
		proto.setItem('crema.settings.v1', 'before');

		freezeCremaWrites({ storageProto: proto, indexedDB: idb });
		freezeCremaWrites({ storageProto: proto, indexedDB: idb }); // idempotent

		proto.setItem('crema.settings.v1', 'after');
		proto.setItem('other.app', 'kept');
		expect(proto.data.get('crema.settings.v1')).toBe('before');
		expect(proto.data.get('other.app')).toBe('kept');
		expect(() => idb.open('crema-captures', 1)).toThrow(/resetting/);
		const other = idb.open('unrelated', 1);
		await new Promise<void>((resolve) => {
			other.onsuccess = () => {
				other.result.close();
				resolve();
			};
		});
		// A delete still works: the wipe itself runs after the freeze.
		expect(idb.deleteDatabase('crema-captures')).toBeDefined();
		expect(cremaWritesFrozen()).toBe(true);
	});
});

describe('installResetListener', () => {
	let otherTab: BroadcastChannel;
	beforeEach(() => {
		otherTab = new BroadcastChannel(RESET_CHANNEL);
	});
	afterEach(() => otherTab.close());

	it('freezes on "started" and reloads on "done"', async () => {
		const freeze = vi.fn();
		const reload = vi.fn();
		const stop = installResetListener({ freeze, reload, fallbackMs: 10_000 });
		otherTab.postMessage({ type: 'started' });
		await tick();
		expect(freeze).toHaveBeenCalled();
		expect(reload).not.toHaveBeenCalled();
		otherTab.postMessage({ type: 'done' });
		await tick();
		expect(reload).toHaveBeenCalledTimes(1);
		stop();
	});

	it('reloads anyway when the resetting tab never says "done"', async () => {
		const reload = vi.fn();
		const stop = installResetListener({ freeze: () => {}, reload, fallbackMs: 30 });
		otherTab.postMessage({ type: 'started' });
		await tick(80);
		expect(reload).toHaveBeenCalledTimes(1);
		stop();
	});

	it('the resetting tab does not freeze itself early on its own broadcast', async () => {
		// The revokes still need the secret-box key, which a freeze would block.
		const freeze = vi.fn();
		const reload = vi.fn();
		const stop = installResetListener({ freeze, reload });
		announceReset({ type: 'started' });
		await tick();
		announceReset({ type: 'done' });
		await tick();
		expect(freeze).not.toHaveBeenCalled();
		expect(reload).not.toHaveBeenCalled();
		stop();
	});
});

describe('reset report', () => {
	it('survives the reload once and names what to do', () => {
		expect(takeResetReport()).toBeNull();
		stashResetReport({ databasesFailed: ['crema-captures'], revokeFailures: 0 });
		const report = takeResetReport();
		expect(report).toEqual({ databasesFailed: ['crema-captures'], revokeFailures: 0 });
		expect(sessionStorage.getItem(RESET_REPORT_KEY)).toBeNull();
		expect(resetReportMessage(report!)).toMatch(/another tab/);
		expect(resetReportMessage({ databasesFailed: [], revokeFailures: 1 })).toMatch(/revoke/);
		expect(resetReportMessage({ databasesFailed: [], revokeFailures: 0 })).toBeNull();
	});
});

// Last on purpose: freezes the real globals (this file's own jsdom).
describe('freeze on the real stores', () => {
	it('a store write after the freeze leaves no trace and a database open fails', async () => {
		const realIdb = (globalThis as { indexedDB?: IDBFactory }).indexedDB;
		const idb = new IDBFactory();
		(globalThis as { indexedDB?: IDBFactory }).indexedDB = idb;
		try {
			localStorage.clear();
			freezeCremaWrites({ indexedDB: idb });
			const { getSettingsStore } = await import('#lib/settings/index.js');
			getSettingsStore().set('density', 'compact' as never);
			expect(Object.keys({ ...localStorage }).filter((k) => k.startsWith('crema.'))).toEqual([]);
			stashResetReport({ databasesFailed: [], revokeFailures: 0 });
			expect(sessionStorage.getItem(RESET_REPORT_KEY)).not.toBeNull();
			const { getCaptureStore } = await import('#lib/capture/store.js');
			await expect(getCaptureStore().put('shot-1', [])).rejects.toBeDefined();
			expect((await idb.databases()).map((d) => d.name)).toEqual([]);
		} finally {
			(globalThis as { indexedDB?: IDBFactory }).indexedDB = realIdb;
		}
	});
});
