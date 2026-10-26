import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { IDBFactory } from 'fake-indexeddb';
import { CREMA_DATABASES, factoryResetLocalData } from './factory-reset';
import { getCaptureStore } from '#lib/capture/store.js';
import { putSeries } from '#lib/history/series-store.js';
import { createIndexedDbKeyProvider } from './secret-box';

/**
 * Review 05 P2-1: a factory reset must erase what the dialog and the privacy
 * page promise — every Crema IndexedDB database and every `crema.*` key in
 * both web storages — and a failed revoke must not stop the wipe. Runs the
 * real stores against an in-memory IndexedDB.
 */

const realIdb = (globalThis as { indexedDB?: IDBFactory }).indexedDB;
let idb: IDBFactory;

beforeEach(() => {
	idb = new IDBFactory();
	(globalThis as { indexedDB?: IDBFactory }).indexedDB = idb;
	localStorage.clear();
	sessionStorage.clear();
});
afterEach(() => {
	(globalThis as { indexedDB?: IDBFactory }).indexedDB = realIdb;
});

async function cremaDatabases(): Promise<string[]> {
	return (await idb.databases())
		.map((d) => d.name ?? '')
		.filter((n) => n.startsWith('crema-'))
		.sort();
}

async function createDb(name: string): Promise<void> {
	await new Promise<void>((resolve, reject) => {
		const req = idb.open(name, 1);
		req.onupgradeneeded = () => req.result.createObjectStore('s');
		req.onsuccess = () => {
			req.result.close();
			resolve();
		};
		req.onerror = () => reject(req.error);
	});
}

describe('factoryResetLocalData', () => {
	it('lists every database the app creates', () => {
		expect([...CREMA_DATABASES].sort()).toEqual([
			'crema-bean-images',
			'crema-captures',
			'crema-history-series',
			'crema-keys'
		]);
	});

	it('leaves no Crema IndexedDB database and no crema.* storage key behind', async () => {
		// Real stores write their data — the capture store keeps its connection
		// open (cached), which must not block the delete.
		await getCaptureStore().put('shot-1', []);
		await putSeries('shot-1', []);
		expect(await createIndexedDbKeyProvider(() => idb).getKey()).not.toBeNull();
		await createDb('crema-bean-images');
		await createDb('crema-some-future-store');
		await createDb('unrelated-db');
		localStorage.setItem('crema.settings.v1', '{}');
		localStorage.setItem('crema.decent.v1', '{"token":"sb1:x"}');
		localStorage.setItem('other.app', 'keep');
		sessionStorage.setItem('crema.drive.token.v1', '{"accessToken":"ya29"}');
		sessionStorage.setItem('crema.visualizer.oauth.pkce.v1', 'verifier');
		expect((await cremaDatabases()).length).toBe(5);

		const result = await factoryResetLocalData({ deleteTimeoutMs: 2_000 });

		expect(await cremaDatabases()).toEqual([]);
		expect(result.databasesFailed).toEqual([]);
		expect((await idb.databases()).map((d) => d.name)).toEqual(['unrelated-db']);
		expect(Object.keys({ ...localStorage })).not.toContain('crema.settings.v1');
		expect(localStorage.getItem('crema.decent.v1')).toBeNull();
		expect(localStorage.getItem('other.app')).toBe('keep');
		expect(sessionStorage.getItem('crema.drive.token.v1')).toBeNull();
		expect(sessionStorage.getItem('crema.visualizer.oauth.pkce.v1')).toBeNull();
		expect(result.storageKeysRemoved).toBe(4);
	});

	it('runs every revoke before wiping and wipes even when a revoke fails or hangs', async () => {
		localStorage.setItem('crema.visualizer.tokens.v1', '{}');
		await createDb('crema-keys');
		const seen: string[] = [];
		const result = await factoryResetLocalData({
			revokeTimeoutMs: 50,
			revoke: [
				async () => {
					// Revocation must still be able to read the tokens + key.
					seen.push(localStorage.getItem('crema.visualizer.tokens.v1') ?? 'gone');
					seen.push((await cremaDatabases()).join(','));
					throw new Error('network down');
				},
				() => new Promise<void>(() => {}) // never settles
			]
		});
		expect(seen).toEqual(['{}', 'crema-keys']);
		expect(result.revokeFailures).toBe(2);
		expect(localStorage.getItem('crema.visualizer.tokens.v1')).toBeNull();
		expect(await cremaDatabases()).toEqual([]);
	});

	it('reports a database another tab keeps open instead of hanging', async () => {
		const held = await new Promise<IDBDatabase>((resolve) => {
			const req = idb.open('crema-captures', 1);
			req.onupgradeneeded = () => req.result.createObjectStore('s');
			req.onsuccess = () => resolve(req.result); // no versionchange handler
		});
		const result = await factoryResetLocalData({ deleteTimeoutMs: 100 });
		expect(result.databasesFailed).toEqual(['crema-captures']);
		held.close();
	});
});
